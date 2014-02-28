/* Provides info on the current user.
 * Copyright (C) 2010 - 2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

(function() {

var d = { i: debiki.internal, u: debiki.v0.util };
var $ = d.i.$;


// Returns a user object, with functions refreshProps, getName,
// isLoggedIn, getLoginId and getUserId.
d.i.makeCurUser = function() {
  // Cache user properties — parsing the session id cookie over and
  // over again otherwise takes 70 - 80 ms on page load, but only
  // 2 ms when cached. (On my 6 core 2.8 GHz AMD, for a page with
  // 100 posts. The user id is checked frequently, to find out which
  // posts have the current user written.)
  var userProps;
  var emailPrefs = undefined;
  var emailSpecified = false;
  var permsOnPage = {};

  function refreshProps() {
    parseSidCookie();
    parseConfigCookie();
  }

  // Warning: Never use the user's name as html, that'd allow xss attacks.
  // (loginId and userId are generated by the server.)
  function parseSidCookie() {
    // sid example:
    //   Y1pBlH7vY4JW9A.23.11.Magnus.1316266102779.15gl0p4xf7
    var sid = $.cookie('dwCoSid');
    if (!sid) {
      userProps = { loginId: undefined, userId: undefined, name: undefined };
      return;
    }
    var arr = sid.split('.');
    userProps = {
      // [0] is a hash
      loginId: arr[1],
      userId: arr[2],
      name: arr[3].replace('_', '.')
      // [4] is login time
      // [5] is a random value
    };
  }

  function parseConfigCookie() {
    var val = $.cookie('dwCoConf');
    emailPrefs = undefined;
    emailSpecified = false;
    if (!val) return;
    if (val.indexOf('EmNtR') !== -1) emailPrefs = 'Receive';
    if (val.indexOf('EmNtN') !== -1) emailPrefs = 'DontReceive';
    if (val.indexOf('EmNtF') !== -1) emailPrefs = 'ForbiddenForever';
    if (val.indexOf('EmSp') !== -1) emailSpecified = true;
  }

  function fireLoginIfNewSession(opt_loginIdBefore) {
    // Sometimes an event object is passed instead of a login id.
    var loginIdBefore = typeof opt_loginIdBefore == 'string' ?
        opt_loginIdBefore : userProps.loginId;
    refreshProps();
    if (loginIdBefore !== userProps.loginId) {
      if (api.isLoggedIn()) api.fireLogin();
      else api.fireLogout();
      // If the login/logout happened in another browser tab:
      // COULD pop up a modal dialog informing the user that s/he has
      // been logged in/out, because of something s/he did in *another* tab.
      // And that any posts s/he submits will be submitted as the new user.
    }
  }

  /**
   * Clears e.g. highlightings of the user's own posts and ratings.
   */
  function clearMyPageInfo() {
    $('.dw-p-by-me').removeClass('dw-p-by-me');
    $('.dw-p-r-by-me').remove();
    setPermsOnPage({});
  }

  function setPermsOnPage(newPerms) {
    permsOnPage = newPerms;
    d.i.showAllowedActionsOnly();
  }

  /**
   * Highlights e.g. the user's own posts and ratings.
   *
   * Loads user specific info from the server, e.g. info on
   * which posts the current user has authored or rated,
   * and the user's permissions on this page.
   *
   * If, however, the server has already included the relevant data
   * in a certain hidden .dw-user-page-data node on the page, then use
   * that data, but only once (thereafter always query the server).
   * — So the first invokation happens synchronously, subsequent
   * invokations happens asynchronously.
   */
  function loadAndHandleUserPageData() {
    // Do nothing if this isn't a normal page. For example, perhaps
    // this is the search results listing page. There's no user
    // specific data related to that page.
    if (!d.i.pageId)
      return;

    // Avoid a roundtrip by using any json data already inlined on the page.
    // Then delete it because it's only valid on page load.
    var hiddenUserDataTag = $('.dw-user-page-data');
    if (hiddenUserDataTag.length) {
      handleUserPageData(hiddenUserDataTag.text());
      hiddenUserDataTag.hide().removeClass('dw-user-page-data');
    }
    else { // if (pageExists) {
      // ... We currently don't know if the page exists. There's a data-page_exists
      // attribute on the .dw-page <div> but it's not updated if an embedded
      // comments page is lazily created. Therefore, for now, attempt to load
      // user credentials always even if it'll fail.

      // Query the server.
      var url = d.i.serverOrigin + '/-/load-my-page-activity?pageId=' + d.i.pageId;
      $.get(url, 'text')
          .fail(showErrorIfPageExists)
          .done(function(jsonData) {
        handleUserPageData(jsonData);
      });

      function showErrorIfPageExists() {
        // This is a bit hacky but it'll go away when I've rewritten the client in Angular-Dart.
        var pageExistsForSure = $('.dw-page[data-page_exists="true"]').length > 0;
        if (pageExistsForSure)
          d.i.showServerResponseDialog(arguments);
      }
    }

    function handleUserPageData(jsonData) {
      var myPageData = JSON.parse(jsonData);
      setPermsOnPage(myPageData.permsOnPage || {});
      markMyActions(myPageData);
      // In `myPageData.threadsByPageId` are any not-yet-approved comments
      // by the current user. Insert them into the page:
      d.i.patchPage(myPageData);
    }

    function markMyActions(actions) {
      $('.dw-a.dw-my-vote').removeClass('dw-my-vote');
      if (actions.ratings) $.each(actions.ratings, showMyRatings);
      if (actions.authorOf) $.each(actions.authorOf, function(ix, postId) {
        d.i.markMyPost(postId);
      });
    }
  }

  function deletePageInfoInServerReply() {
    var hiddenUserDataTag = $('.dw-user-page-data');
    hiddenUserDataTag.hide().removeClass('dw-user-page-data');
  };

  var api = {
    // Call whenever the SID changes: on page load, on login and logout.
    refreshProps: refreshProps,
    clearMyPageInfo: clearMyPageInfo,
    loadAndHandleUserPageData: loadAndHandleUserPageData,
    deletePageInfoInServerReply: deletePageInfoInServerReply,
    fireLogin: function() { fireLoginImpl(api); },
    fireLogout: function() { fireLogoutImpl(api); },
    // Call when a re-login might have happened, e.g. if focusing
    // another browser tab and then returning to this tab.
    fireLoginIfNewSession: fireLoginIfNewSession,

    // Warning: Never ever use this name as html, that'd open for
    // xss attacks. E.g. never do: $(...).html(Me.getName()), but the
    // following should be okay though: $(...).text(Me.getName()).
    getName: function() { return userProps.name; },
    isLoggedIn: function() { return userProps.loginId ? true : false; },
    getLoginId: function() { return userProps.loginId; },
    getUserId: function() { return userProps.userId; },
    isAuthenticated: function() {
      // If starts with '-', then not authenticated. (In the future:
      // if *ends* with '-'? Well works anyway.)
      return !!userProps.userId.match(/^[a-z0-9]+$/);
    },
    getPermsOnPage: function() { return permsOnPage; },
    mayEdit: function($post) {
      return userProps.userId === $post.dwAuthorId() ||
          permsOnPage.editPage ||
          (permsOnPage.editAnyReply && $post.dwIsReply()) ||
          (permsOnPage.editGuestReply && $post.dwIsGuestReply());
    },
    getEmailNotfPrefs: function() { return emailPrefs; },
    isEmailKnown: function() { return emailSpecified; },

    // Returns an array with a combination of: 'VoteLike', 'VoteWrong', 'VoteOffTopic'.
    getVotes: function(postId) { return []; }  // for now
  };

  return api;
};


function fireLoginImpl(Me) {
  // The server has set new XSRF (and SID) cookie, and we need to
  // ensure <form> XSRF <input>:s are synced with the new cookie. But 1) the
  // $.ajaxSetup complete() handler that does tnis (in debiki.js) won't
  // have been triggered, if we're loggin in with OpenID — since such
  // a login happens in another browser tab. And 2) some e2e tests
  // cheat-login via direct calls to the database
  // and to `fireLogin` (e.g. so the tests don't take long to run).
  // And those tests assume we refresh XSRF tokens here.
  // So sync hidden form XSRF <input>s:
  d.i.refreshFormXsrfTokens();

  Me.refreshProps();
  $('.dw-u-info').show()
      .find('.dw-u-name').text(Me.getName());
  $('.dw-a-logout').show();
  $('.dw-a-login').hide();

  // Let Post as ... and Save as ... buttons update themselves:
  // they'll replace '...' with the user name.
  $('.dw-loginsubmit-on-click')
      .trigger('dwEvLoggedInOut', [Me.getName()]);

  Me.clearMyPageInfo();
  Me.loadAndHandleUserPageData();

  // Tell the parts of this page that uses AngularJS about the current user.
  d.i.angularApply(function($rootScope) {
    // Why don't I expose a nice user = { name:, loginId:, ... } object?
    // Instead:
    $rootScope.setCurrentUser({
      displayName: Me.getName(),
      loginId: Me.getLoginId(),
      userId: Me.getUserId(),
      permsOnPage: Me.getPermsOnPage(),
      emailNotfPrefs: Me.getEmailNotfPrefs(),
      isEmailKnown: Me.isEmailKnown(),
      isAuthenticated: Me.isAuthenticated()
    });
  });
};


// Updates cookies and elements to show the user name, email etc.
// as appropriate. Unless !propsUnsafe, throws if name or email missing.
// Fires the dwEvLoggedInOut event on all .dw-loginsubmit-on-click elems.
// Parameters:
//  props: {name, email, website}, will be sanitized unless
//  sanitize: unless `false', {name, email, website} will be sanitized.
function fireLogoutImpl(Me) {
  Me.refreshProps();
  $('.dw-u-info').hide();
  $('.dw-a-logout').hide();
  $('.dw-a-login').show();

  // Let `Post as <username>' etc buttons update themselves:
  // they'll replace <username> with `...'.
  $('.dw-loginsubmit-on-click').trigger('dwEvLoggedInOut', [undefined]);

  Me.clearMyPageInfo();
  Me.deletePageInfoInServerReply(); // so not reused if logging in later

  // If AngularJS has been started, tell the parts of this page that
  // uses AngularJS about the logout.
  d.i.anyAngularApply(function($rootScope) {
    $rootScope.clearCurrentUser();
  });
};


function showMyRatings(postId, ratings) {
  var thread = d.i.findThread$(postId);
  if (ratings.indexOf('VoteLike') !== -1) {
    thread.find('> .dw-p-as .dw-a-like').addClass('dw-my-vote');
  }
  if (ratings.indexOf('VoteWrong') !== -1) {
    thread.find('> .dw-p-as .dw-a-wrong').addClass('dw-my-vote');
  }
  if (ratings.indexOf('VoteOffTopic') !== -1) {
    thread.find('> .dw-p-as .dw-a-offtopic').addClass('dw-my-vote');
  }
};


d.i.markMyPost = function(postId) {
  var $header = d.i.findPostHeader$(postId);
  $header.children('.dw-p-by').addClass('dw-p-by-me');
};


/** Enables and disables action links, based on the user's `permsOnPage`.
  */
d.i.showAllowedActionsOnly = function(anyRootPost) {
  var permsOnPage = d.i.Me.getPermsOnPage();
  function showHideActionLinks(permission, selector) {
    var $actionLinks = anyRootPost
        ? $(anyRootPost).parent().children('.dw-p-as').find(selector)
        : $(selector);
    if (permsOnPage[permission]) $actionLinks.show();
    else $actionLinks.hide();
  }

  showHideActionLinks(
      'collapseThings',
        '.dw-a-collapse-tree, .dw-a-collapse-post, ' +
        '.dw-a-uncollapse-tree, .dw-a-uncollapse-post,' +
        '.dw-a-close-tree');
  showHideActionLinks(
      'deleteAnyReply', '.dw-a-delete, .dw-a-undelete');
  showHideActionLinks(
      'pinReplies', '.dw-a-pin');
}


})();

// vim: fdm=marker et ts=2 sw=2 fo=tcqwn list
