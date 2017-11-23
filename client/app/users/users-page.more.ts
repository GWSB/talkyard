/**
 * Copyright (c) 2014-2017 Kaj Magnus Lindberg
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

//xx <reference path="../../typedefs/moment/moment.d.ts" /> — disappeared
declare var moment: any;
/// <reference path="../slim-bundle.d.ts" />
/// <reference path="user-invites.more.ts" />
/// <reference path="user-notifications.more.ts" />
/// <reference path="user-preferences.more.ts" />
/// <reference path="user-activity.more.ts" />
/// <reference path="user-summary.more.ts" />

//------------------------------------------------------------------------------
   namespace debiki2.users {
//------------------------------------------------------------------------------

const r = React.DOM;
const Nav = rb.Nav;
const NavItem = rb.NavItem;
const UsersRoot = '/-/users/';
const UsersRootAndIdParamSlash = UsersRoot + ':usernameOrId/';


// Make the components async? So works also if more-bundle.js not yet loaded? [4WP7GU5]
export function routes() {
  return (
    // Let's keep this, although just one route — because maybe will move up to an "upper base route".
    Route({ path: UsersRoot, component: UsersHomeComponent }));
}



const UsersHomeComponent = React.createClass(<any> {
  displayName: 'UsersHomeComponent',

  render: function() {
    return (
      r.div({},
        reactelements.TopBar({ customTitle: "About User",
            backToSiteButtonTitle: "Back from user profile", extraMargin: true }),
        Switch({},
          Route({ path: UsersRoot, component: BadUrlComponent, exact: true }),
          Route({ path: UsersRoot + ':usernameOrId', exact: true, render: ({ match }) => {
            return Redirect({ to: UsersRoot + match.params.usernameOrId + '/activity' });
          }}),
          Route({ path: UsersRoot + ':usernameOrId', component: UserPageComponent }))));
  }
});



const BadUrlComponent = React.createClass(<any> {
  displayName: 'BadUrlComponent',

  render: function() {
    return r.div({}, 'Unexpected URL [DwE7E1W31]');
  }
});



const UserPageComponent = React.createClass(<any> {
  displayName: 'UserPageComponent',
  mixins: [debiki2.StoreListenerMixin],

  getInitialState: function() {
    return {
      store: debiki2.ReactStore.allData(),
      myId: null,
      user: null,
      // Backw compat with react-router v3: (.slice removes '/-/' in '/-/users')
      routes: this.props.location.pathname.split('/').slice(2),  // (4GKQS2)
    };
  },

  onChange: function() {
    let myOldId = this.state.myId;
    let store: Store = debiki2.ReactStore.allData();
    this.setState({
      store: store,
      myId: store.me.id,
    });
    if (myOldId !== store.me.id) {
      // Now we might have access to more/less data about the user, so refresh.
      this.loadUserAnyDetails();
    }
  },

  componentWillReceiveProps: function(newProps) {
    this.setState({
      routes: newProps.location.pathname.split('/').slice(2),  // (4GKQS2)
    });
  },

  componentDidMount: function() {
    this.loadUserAnyDetails();
  },

  componentDidUpdate: function(prevProps) {
    if (this.props.location.pathname !== prevProps.location.pathname) {
      this.loadUserAnyDetails();
    }
  },

  componentWillUnmount: function() {
    this.isGone = true;
  },

  transitionTo: function(subPath) {
    this.props.history.push('/-/users/' + this.props.match.params.usernameOrId + '/' + subPath);
  },

  loadUserAnyDetails: function(redirectToCorrectUsername) {
    const usernameOrId: string | number = this.props.match.params.usernameOrId;
    Server.loadUserAnyDetails(usernameOrId, (user: MemberInclDetails, stats: UserStats) => {
      if (this.isGone) return;
      this.setState({ user: user, stats: stats });
      // 1) In case the user has changed his/her username, and userIdOrUsername is his/her *old*
      // name, user.username will be the current name — then show current name in the url [8KFU24R].
      // Also 2) if user id specified, and the user is a member (they have usernames) show
      // username instead,
      const isNotLowercase = _.isString(usernameOrId) && usernameOrId !== usernameOrId.toLowerCase();
      if (user.username && (user.username.toLowerCase() !== usernameOrId || isNotLowercase) &&
          redirectToCorrectUsername !== false) {
        this.props.history.replace('/-/users/' + user.username.toLowerCase());
      }
      this.maybeOpenMessageEditor(user.id);
    }, () => {
      if (this.isGone) return;
      // Error. We might not be allowed to see this user, so null it even if it was shown before.
      this.setState({ user: null });
    });
  },

  maybeOpenMessageEditor: function(userId: number) {
    if (window.location.hash.indexOf('#writeMessage') >= 0 && !this.hasOpenedEditor) {
      this.hasOpenedEditor = true;
      dieIf(userId_isGuest(userId), 'EdE6JKY20');
      const myUserId = ReactStore.getMe().id;
      if (userId !== myUserId) {
        editor.openToWriteMessage(userId);
      }
    }
  },

  render: function() {
    const store: Store = this.state.store;
    const me: Myself = store.me;
    const user: UserAnyDetails = this.state.user;
    if (!user || !me)
      return r.p({}, 'Loading...');

    dieIf(!this.state.routes || !this.state.routes[2], 'EsE5GKUW2');

    const showPrivateStuff = isStaff(me) || (me.isAuthenticated && me.id === user.id);

    const activityNavItem = user.isGroup ? null :
      NavItem({ eventKey: 'activity', className: 'e_UP_ActivityB' }, "Activity");

    const summaryNavItem = user.isGroup ? null :
      NavItem({ eventKey: 'summary', className: 'e_UP_SummaryB' }, "Summary");

    const notificationsNavItem = !showPrivateStuff || user.isGroup ? null :
      NavItem({ eventKey: 'notifications', className: 'e_UP_NotfsB' }, "Notifications");

    const preferencesNavItem = !showPrivateStuff ? null :
      NavItem({ eventKey: 'preferences', id: 'e2eUP_PrefsB' }, "Preferences");

    const invitesNavItem = !showPrivateStuff || !maySendInvites(user).value ? null :
      NavItem({ eventKey: 'invites', id: 'e2eUP_InvitesB' }, "Invites");

    const childProps = {
      store: store,
      me: me, // try to remove, incl already in `store`
      user: user,
      routes: this.state.routes,
      stats: this.state.stats,
      reloadUser: this.loadUserAnyDetails,
      transitionTo: this.transitionTo
    };

    let activeRouteName = this.state.routes[2];
    const u = UsersRootAndIdParamSlash;

    const childRoutes = Switch({},
      Route({ path: u + 'activity', exact: true, render: ({ match }) => {
        return Redirect({ to: UsersRoot + match.params.usernameOrId + '/activity/posts' });
      }}),
      Route({ path: u + 'activity', render: (ps) => UsersActivityComponent({ ...childProps, ...ps }) }),
      Route({ path: u + 'summary', render: () => UserSummaryComponent(childProps) }),
      Route({ path: u + 'notifications', render: () => UserNotificationsComponent(childProps) }),
      Route({ path: u + 'preferences', render: () => UserPreferencesComponent(childProps) }),
      Route({ path: u + 'invites', render: () => UserInvitesComponent(childProps) }));

    return (
      r.div({ className: 'container esUP' },
        AvatarAboutAndButtons(childProps),
        Nav({ bsStyle: 'pills', activeKey: activeRouteName,
            onSelect: this.transitionTo, className: 'dw-sub-nav' },
          activityNavItem,
          summaryNavItem,
          notificationsNavItem,
          invitesNavItem,
          preferencesNavItem),
        childRoutes));
  }
});



const AvatarAboutAndButtons = createComponent({
  displayName: 'AvatarAboutAndButtons',

  getInitialState: function() {
    return {
      isUploadingProfilePic: false,
    };
  },

  componentDidMount: function() {
    Server.loadEditorAndMoreBundles(this.createUploadAvatarButton);
  },

  selectAndUploadAvatar: function() {
    this.refs.chooseAvatarInput.click();
  },

  createUploadAvatarButton: function() {
    if (!this.refs.chooseAvatarInput)
      return;

    const inputElem = this.refs.chooseAvatarInput;
    const FileAPI = window['FileAPI'];
    FileAPI.event.on(inputElem, 'change', (evt) => {
      const files = FileAPI.getFiles(evt);
      if (!files.length)
        return; // file dialog cancelled?

      // Perhaps there's some better way to test if the file is ok than using filter(). Oh well.
      FileAPI.filterFiles(files, (file, info) => {
        if( /^image/.test(file.type) ){
          const largeEnough = info.width >= 100 && info.height >= 100;
          dieIf(!largeEnough, "Image too small: should be at least 100 x 100 [EsE8PYM21]");
        }
        else {
          die("Not an image [EsE5GPU3]");
        }
        return true;
      }, (files, rejected) => {
        dieIf(files.length !== 1, 'DwE5UPM2');
        FileAPI.upload({   // a bit dupl code [2UK503]
          url: '/-/upload-avatar?userId=' + this.props.user.id,
          headers: { 'X-XSRF-TOKEN': getSetCookie('XSRF-TOKEN') },
          files: { images: files },
          imageOriginal: false,
          imageTransform: {
            'tiny': { width: 25, height: 25, type: 'image/jpeg', quality: 0.95 },
            'small': { width: 48, height: 48, type: 'image/jpeg', quality: 0.95 },
            'medium': { maxWidth: 350, maxHeight: 350, type: 'image/jpeg', quality: 0.8 },
          },
          // This is per file.
          fileprogress: (event, file, xhr, options) => {
            if (!this.state.isUploadingProfilePic) {
              this.setState({ isUploadingProfilePic: true });
              pagedialogs.getProgressBarDialog().open("Uploading...", () => {
                this.setState({ uploadCancelled: true });
                xhr.abort("Intentionally cancelled [EsM2FL54]");
              });
            }
            else {
              const percent = event.loaded / event.total * 100;
              pagedialogs.getProgressBarDialog().setDonePercent(percent);
            }
          },
          // This is when all files have been uploaded — but we're uploading just one.
          complete: (error, xhr) => {
            if (error && !this.state.uploadCancelled) {
              pagedialogs.getServerErrorDialog().open(xhr);
            }
            // Reload in any case — perhaps the error happened after the whole image had been
            // uploaded already.
            this.props.reloadUser();
            pagedialogs.getProgressBarDialog().close();
            this.setState({
              isUploadingProfilePic: false,
              uploadCancelled: false
            });
          },
        });
      });
    });
  },

  sendMessage: function() {
    editor.openToWriteMessage(this.props.user.id);
  },

  render: function() {
    const user: MemberInclDetails = this.props.user;
    const stats: UserStats = this.props.stats;
    const me: Myself = this.props.me;
    let suspendedInfo;
    if (user.suspendedAtEpoch) {
      const whatAndUntilWhen = (<number | string> user.suspendedTillEpoch) === 'Forever'
          ? 'banned'
          : 'suspended until ' + moment(user.suspendedTillEpoch).format('YYYY-MM-DD HH:mm') + ' UTC';
      suspendedInfo = r.div({},
          'This user is ' + whatAndUntilWhen, r.br(),
          'Reason: ' + user.suspendedReason);
    }

    const isMe = me.id === user.id;

    let isAGroup;
    if (user.isGroup) {
      isAGroup = " (a group)";
    }

    let isWhatInfo = null;
    if (isGuest(user)) {
      isWhatInfo = ' — a guest user, could be anyone';
    }
    if (user.isModerator) {
      isWhatInfo = ' – moderator';
    }
    if (user.isAdmin) {
      isWhatInfo = ' – administrator';
    }
    if (isWhatInfo) {
      isWhatInfo = r.span({ className: 'dw-is-what' }, isWhatInfo);
    }

    const thatIsYou = !isMe ? null :
      r.span({ className: 'esProfile_isYou' }, "(you)");

    const avatar = user.mediumAvatarUrl
        ? r.img({ src: user.mediumAvatarUrl })
        : debiki2.avatar.Avatar({ user: user, large: true, ignoreClicks: true });

    const uploadAvatarBtnText = user.mediumAvatarUrl ? "Change photo" : "Upload photo";
    const avatarMissingClass = user.mediumAvatarUrl ? '' : ' esMedAvtr-missing';

    const anyUploadPhotoBtn = (isMe || isStaff(me)) && !isGuest(user)
      ? r.div({},
          // File inputs are ugly, so we hide the file input (size 0 x 0) and activate
          // it by clicking a beautiful button instead:
          PrimaryButton({ id: 'e2eChooseAvatarInput', className: 'esMedAvtr_uplBtn',
              onClick: this.selectAndUploadAvatar }, uploadAvatarBtnText),
          r.input({ name: 'files', type: 'file', multiple: false, // dupl code [2UK503]
              ref: 'chooseAvatarInput',
              style: { width: 0, height: 0, position: 'absolute', left: -999 }}))
      : null;


    const adminButton = !isStaff(me) || isGuest(user) ? null :
        LinkButton({ href: linkToUserInAdminArea(user.id), className: 's_UP_AdminB' },
          "View in Admin Area");

    const sendMessageButton = !me_maySendDirectMessageTo(me, user) ? null :
        PrimaryButton({ onClick: this.sendMessage, className: 's_UP_SendMsgB' },
          "Send Message");

    // COULD prefix everything inside with s_UP_Ab(out) instead of just s_UP.
    return r.div({ className: 's_UP_Ab dw-user-bar clearfix' },
      // This + display: table-row makes the avatar image take less space,
      // and the name + about text get more space, if the avatar is narrow.
      r.div({ className: 's_UP_AvtrAboutBtns' },
        r.div({ className: 's_UP_Avtr' },
          r.div({ className: 'esMedAvtr' + avatarMissingClass },
            avatar,
            anyUploadPhotoBtn)),
        r.div({ className: 's_UP_AboutBtns' },
          sendMessageButton,
          adminButton,
          r.h1({ className: 'esUP_Un' }, user.username, thatIsYou, isAGroup),
          r.h2({ className: 'esUP_FN' }, user.fullName, isWhatInfo),
          r.div({ className: 's_UP_About' }, user.about),
          suspendedInfo)),
        !stats ? null : r.div({ className: 's_UP_Ab_Stats' },
          r.div({ className: 's_UP_Ab_Stats_Stat' },
            "Joined: " + moment(stats.firstSeenAt).fromNow()),
          r.div({ className: 's_UP_Ab_Stats_Stat' },user.isGroup ? null :
            "Posts made: " + userStats_totalNumPosts(stats)),
          !stats.lastPostedAt ? null : r.div({ className: 's_UP_Ab_Stats_Stat' },
            "Last post: " + moment(stats.lastPostedAt).fromNow()),
          r.div({ className: 's_UP_Ab_Stats_Stat' },
            "Last seen: " + moment(stats.lastSeenAt).fromNow()),
          r.div({ className: 's_UP_Ab_Stats_Stat' },
            "Trust level: " + trustLevel_toString(user.effectiveTrustLevel))));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
