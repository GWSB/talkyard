/*
 * Copyright (C) 2015 Kaj Magnus Lindberg
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
/// <reference path="../slim-bundle.d.ts" />
/// <reference path="../utils/PatternInput.more.ts" />
/// <reference path="../util/FullNameInput.more.ts" />
/// <reference path="../util/EmailInput.more.ts" />
/// <reference path="../util/stupid-dialog.more.ts" />
/// <reference path="./new-password-input.more.ts" />

//------------------------------------------------------------------------------
   module debiki2.login {
//------------------------------------------------------------------------------

var d = { i: debiki.internal, u: debiki.v0.util };
var r = React.DOM;
var Modal = rb.Modal;
var ModalBody = rb.ModalBody;
var ModalFooter = rb.ModalFooter;
var ModalHeader = rb.ModalHeader;
var ModalTitle = rb.ModalTitle;
var PatternInput = utils.PatternInput;
var FullNameInput = util.FullNameInput;
var EmailInput = util.EmailInput;


var createUserDialog;
var addressVerificationEmailSentDialog;


function getCreateUserDialogs() {
  if (!createUserDialog) {
    createUserDialog = ReactDOM.render(CreateUserDialog(), utils.makeMountNode());
  }
  return createUserDialog;
}


function getAddressVerificationEmailSentDialog() {
  if (!addressVerificationEmailSentDialog) {
    addressVerificationEmailSentDialog =
        ReactDOM.render(AddressVerificationEmailSentDialog(), utils.makeMountNode());
  }
  return addressVerificationEmailSentDialog;
}


// Backwards compatibility, for now:
/**
 * Prefix `RedirFromVerifEmailOnly` to the return-to-url, to indicate that
 * the redirect should happen only if an email address verification email is sent,
 * and via a link in that email.
 *
 * userData: { name, email, authDataCacheKey }
 */
debiki.internal.showCreateUserDialog = function(userData, anyReturnToUrl) {
  getCreateUserDialogs().open(userData, anyReturnToUrl);
};


var CreateUserDialog = createClassAndFactory({
  getInitialState: function () {
    return { isOpen: false, userData: {} };
  },
  open: function(userData, anyReturnToUrl: string) {
    // In case any login dialog is still open:
    login.getLoginDialog().close();
    this.setState({
      isOpen: true,
      userData: userData,
      anyReturnToUrl: anyReturnToUrl,
      store: ReactStore.allData(),
    });
  },
  close: function() {
    this.setState({ isOpen: false, userData: {} });
  },
  render: function () {
    var childProps = _.clone(this.state.userData);
    childProps.anyReturnToUrl = this.state.anyReturnToUrl;
    childProps.store = this.state.store;
    childProps.closeDialog = this.close;
    childProps.ref = 'content';
    return (
      Modal({ show: this.state.isOpen, onHide: this.close, keyboard: false,
          dialogClassName: 'esCreateUserDlg' },
        ModalHeader({}, ModalTitle({}, "Create User")),
        ModalBody({}, CreateUserDialogContent(childProps))));
  }
});


export var CreateUserDialogContent = createClassAndFactory({
  getInitialState: function() {
    return {
      okayStatuses: {
        fullName: true,
        email: this.props.providerId && this.props.email && this.props.email.length,
        username: false,
        password: !this.props.createPasswordUser,
      },
      userData: {
        fullName: this.props.name,
        email: this.props.email,
        username: ''
      },
    };
  },

  updateValueOk: function(what, value, isOk) {
    var data = this.state.userData;
    var okayStatuses = this.state.okayStatuses;
    data[what] = value;
    okayStatuses[what] = isOk;
    this.setState({ userData: data, okayStatuses: okayStatuses });

    // Check strength again since e.g. fullName might now be (or no longer be)
    // part of the password. But not until we've rerendered everything and sent new
    // props to the password input.
    if (what !== 'password' && this.refs.password) {
      setTimeout(this.refs.password.checkPasswordStrength, 1);
    }
  },

  setEmailOk: function(value, isOk) {
    this.updateValueOk('email', value, isOk);
  },

  doCreateUser: function() {
    const data: any = this.state.userData;
    data.returnToUrl = this.props.anyReturnToUrl;
    if (this.props.authDataCacheKey) {
      data.authDataCacheKey = this.props.authDataCacheKey;
      Server.createOauthUser(data, this.handleCreateUserResponse, this.handleErrorResponse);
    }
    else if (this.props.createPasswordUser) {
      data.password = this.refs.password.getValue();
      Server.createPasswordUser(data, this.handleCreateUserResponse, this.handleErrorResponse);
    }
    else {
      console.error('DwE7KFEW2');
    }
  },

  handleCreateUserResponse: function(response) {
    if (!response.userCreatedAndLoggedIn) {
      dieIf(response.emailVerifiedAndLoggedIn, 'EdE2TSBZ2');
      ReactActions.newUserAccountCreated();
      getAddressVerificationEmailSentDialog().sayVerifEmailSent();
    }
    else if (this.props.anyReturnToUrl && !debiki.internal.isInLoginPopup &&
        this.props.anyReturnToUrl.search('_RedirFromVerifEmailOnly_') === -1) {
      const returnToUrl = this.props.anyReturnToUrl.replace(/__dwHash__/, '#');
      const currentUrl = window.location.toString();
      // Previously, only:
      //   if (returnToUrl === currentUrl) ...
      // but was never used?
      // Now, instead, for Usability Testing Exchange [plugin]: (and perhaps better, always?)
      // (afterLoginCallback = always called after signup if logged in, and the return-to-url
      // is included in the continue-link via the email.)
      if (returnToUrl === currentUrl || this.props.afterLoginCallback) {
        const afterLoginCallback = this.props.afterLoginCallback; // gets nulled when dialogs closed
        debiki2.ReactActions.loadMyself(() => {
          if (afterLoginCallback) {
            afterLoginCallback();
          }
          getAddressVerificationEmailSentDialog().sayWelcomeLoggedIn();
        });
      }
      else {
        window.location.assign(returnToUrl);
        // In case the location didn't change, reload the page, otherwise user specific things
        // won't appear.  [redux] This reload() won't be needed later?
        window.location.reload();
      }
    }
    else if (!window['debiki2']) {
      // COULD remove — this cannot hapen any longer, loading the same script bundle
      // everywhere right now. Remove this?
      // We haven't loaded all JS that would be needed to continue on the same page.
      window.location.assign('/');
    }
    else {
      const isPage = $$byClass('dw-page').length;
      if (!isPage) console.log('should reload ... /? [DwE2KWF1]');
      continueOnMainPageAfterHavingCreatedUser();
    }
    this.props.closeDialog('CloseAllLoginDialogs');
  },

  handleErrorResponse: function(failedRequest: HttpRequest) {
    if (hasErrorCode(failedRequest, '_EsE403WEA_')) {
      this.setState({ theWrongEmailAddress: this.state.userData.email });
      const where = debiki.siteId === FirstSiteId ? "in the config file" : "on the Create Site page";
      util.openDefaultStupidDialog({
        body: "Wrong email address. Please use the email address you specified " + where + '.',
      });
      return IgnoreThisError;
    }
  },

  render: function() {
    const props = this.props;
    const store: Store = props.store;
    const state = this.state;
    const hasEmailAddressAlready = props.email && props.email.length;

    const emailHelp = props.providerId && hasEmailAddressAlready ?
        "Your email has been verified by " + props.providerId + "." : null;

    // Undefined —> use the default, which is True.  ... but for now, always require email [0KPS2J]
    const emailOptional = ''; // store.settings.requireVerifiedEmail === false ? "optional, " : '';

    const emailInput =
        EmailInput({ label: `Email: (${emailOptional}will be kept private)`, id: 'e2eEmail',
          onChangeValueOk: (value, isOk) => this.setEmailOk(value, isOk), tabIndex: 1,
          // If email already provided by e.g. Google, don't let the user change it.
          disabled: hasEmailAddressAlready, defaultValue: props.email, help: emailHelp,
          required: true, // [0KPS2J] store.settings.requireVerifiedEmail !== false,
          error: this.state.userData.email !== this.state.theWrongEmailAddress ?
              null : "Use the email address you specified " +
                        (debiki.siteId === FirstSiteId ?
                            "in the config file." : "on the Create Site page.") });

  // CLEAN_UP SHOULD reuse UsernameInput.more.ts [76KWU02]
    const usernameInput =
        PatternInput({ label: "Username: (unique and short)", ref: 'username', id: 'e2eUsername',
          tabIndex: 2,
          addonBefore: '@', // [7RFWUQ2]
          minLength: 3, maxLength: 20,
          notRegex: / /, notMessage: "No spaces please",
          notRegexTwo: /-/, notMessageTwo: "No hypens (-) please",
          notRegexThree: /@/, notMessageThree: "Don't include the @",
          notRegexFour: /[^a-zA-Z0-9_]/,
          notMessageFour: "Only letters a-z A-Z and 0-9 and _",
          onChange: (value, isOk) => this.updateValueOk('username', value, isOk)
        });

    const passwordInput = props.createPasswordUser
        ? NewPasswordInput({ newPasswordData: state.userData, ref: 'password', tabIndex: 2,
              setPasswordOk: (isOk) => this.updateValueOk('password', 'dummy', isOk) })
        : null;

    const fullNameInput =
      FullNameInput({ label: "Full name: (optional)", ref: 'fullName',
        id: 'e2eFullName', defaultValue: props.name, tabIndex: 2,
        onChangeValueOk: (value, isOk) => this.updateValueOk('fullName', value, isOk) });

    const disableSubmit = _.includes(_.values(this.state.okayStatuses), false);

    return (
      r.form({ className: 'esCreateUser' },
        emailInput,
        usernameInput,
        // Place the full name input above the password input, because someone thought
        // the full-name-input was a password verification field.
        fullNameInput,
        passwordInput,
        PrimaryButton({ onClick: this.doCreateUser, disabled: disableSubmit, id: 'e2eSubmit',
            tabIndex: 2 }, "Create Account")));
  }
});


function continueOnMainPageAfterHavingCreatedUser() {
  // If this is an embedded comments site, we're currently executing in
  // a create user login popup window, not in the <iframe> on the embedding
  // page. If so, only window.opener has loaded the code that we're
  // about to execute.
  var debikiInternal;
  var d2;

  // This won't work, might have been opened via other Debiki tab?
  //    if (window.opener && window.opener['debiki']) {

  if (d.i.isInLoginPopup) {
    // We're in a login popup window for an embedded comments site. Continue in the opener tab
    // and close this popup (at the end of this function).
    debikiInternal = window.opener['debiki'].internal;
    d2 = window.opener['debiki2'];
  }
  else {
    // We're in the correct browser tab already. This is not an embedded comments site;
    // we're not in a popup window. So we can execute the subsequent code in this tab.
    debikiInternal = debiki.internal;
    d2 = debiki2;
  }

  // We should be on some kind of a discussion page.
  // This continues e.g. whatever the user attempted to do before she was asked to login
  // and create a user.
  login.continueAfterLogin();

  if (d.i.isInLoginPopup) {
    close();  // closes the popup window
  }
}


// CLEAN_UP RENAME to CreateUserResultDialog ?
var AddressVerificationEmailSentDialog = createComponent({
  getInitialState: function () {
    return { isOpen: false };
  },
  sayVerifEmailSent: function() {
    this.setState({ isOpen: true });
  },
  sayWelcomeLoggedIn: function() {
    this.setState({ isOpen: true, isLoggedIn: true });
  },
  close: function() {
    this.setState({ isOpen: false });
  },
  render: function () {
    const id = this.state.isLoggedIn ? 'te_WelcomeLoggedIn' : 'e2eNeedVerifyEmailDialog';
    const text = this.state.isLoggedIn
      ? "Account created. You have been logged in."  // COULD say if verif email sent too?
      : "Almost done! You just need to confirm your email address. We have " +
        "sent an email to you. Please click the link in the email to activate " +
        "your account. You can close this page.";
    return (
      Modal({ show: this.state.isOpen, onHide: this.close, id },
        ModalHeader({}, ModalTitle({}, "Welcome")),
        ModalBody({}, r.p({}, text)),
        ModalFooter({},
          PrimaryButton({ onClick: this.close }, "Okay"))));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
