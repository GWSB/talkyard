/*
 * Copyright (c) 2015, 2017 Kaj Magnus Lindberg
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

/// <reference path="../plain-old-javascript.d.ts" />

//------------------------------------------------------------------------------
   module debiki2.login {
//------------------------------------------------------------------------------

const d = { i: debiki.internal };


// From before React.js.
export let anyContinueAfterLoginCallback = null;


export function loginIfNeededReturnToPost(
      loginReason: LoginReason | string, postNr: PostNr, success: () => void) {
  loginIfNeededReturnToAnchor('LoginToEdit', '#post-' + postNr, success);
}


export function loginIfNeededReturnToAnchor(
      loginReason: LoginReason | string, anchor: string, success: () => void) {
  const returnToUrl = makeReturnToPageHashForVerifEmail(anchor);
  const d = { i: debiki.internal };
  success = success || function() {};
  if (ReactStore.getMe().isLoggedIn) {
    success();
  }
  else if (d.i.isInIframe) {
    anyContinueAfterLoginCallback = success;
    // Don't open a dialog inside the iframe; open a popup instead.
    // Need to open the popup here immediately, before loading any scripts, because if
    // not done immediately after mouse click, the popup gets blocked (in Chrome at least).
    // And when opening in a popup, we don't need any more scripts here in the main win anyway.
    const url = d.i.serverOrigin + '/-/login-popup?mode=' + loginReason +
      '&isInLoginPopup&returnToUrl=' + returnToUrl;
    d.i.createLoginPopup(url)
  }
  else {
    morebundle.loginIfNeeded(loginReason, returnToUrl, success);
  }
}


function makeReturnToPageHashForVerifEmail(hash) {
  // The magic '__Redir...' string tells the server to use the return-to-URL only if it
  // needs to send an email address verification email (it'd include the return
  // to URL on a welcome page show via a link in the email).
  // '__dwHash__' is an encoded hash that won't be lost when included in a GET URL.
  // The server replaces it with '#' later on.
  // `d.i.iframeBaseUrl` is for embedded comments in an <iframe>: it's the URL of
  // the embedding parent page.
  const pageUrl = d.i.iframeBaseUrl ? d.i.iframeBaseUrl : window.location.toString();
  let returnToUrl = '_RedirFromVerifEmailOnly_' + pageUrl.replace(/#.*/, '');
  if (hash) {
    hash = hash.replace(/^#/, '');
    returnToUrl += '__dwHash__' + hash;
  }
  return returnToUrl;
}


export function continueAfterLogin(anyReturnToUrl?: string) {
  if (debiki.internal.isInLoginWindow) {
    // We're in an admin section login page, or an embedded comments page login popup window.
    if (anyReturnToUrl && anyReturnToUrl.indexOf('_RedirFromVerifEmailOnly_') === -1) {
      window.location.assign(anyReturnToUrl);
    }
    else {
      // (Also see LoginWithOpenIdController, search for [509KEF31].)
      window.opener['debiki'].internal.handleLoginResponse({ status: 'LoginOk' });
      // This should be a login popup. Close the whole popup window.
      close();
    }
  }
  else {
    // We're on a normal page (but not in a login popup window for an embedded comments page).
    // (The login dialogs close themselves when the login event gets fired.)
    debiki2.ReactActions.loadMyself(anyContinueAfterLoginCallback);
  }
}


// Backwards compatibility, for now:
/**
 * Prefix `RedirFromVerifEmailOnly` to the return-to-url, to indicate that
 * the redirect should happen only if an email address verification email is sent,
 * and via a link in that email.
 *
 * userData: { name, email, authDataCacheKey }
debiki.internal.showCreateUserDialog = function(userData, anyReturnToUrl) {
  getCreateUserDialogs().open(userData, anyReturnToUrl);
};
 */


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
