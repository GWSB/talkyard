/// <reference path="../test-types.ts"/>

import * as _ from 'lodash';
import assert = require('assert');
import fs = require('fs');
import server = require('../utils/server');
import utils = require('../utils/utils');
import pages = require('../utils/pages');
import pagesFor = require('../utils/pages-for');
import settings = require('../utils/settings');
import make = require('../utils/make');
import logAndDie = require('../utils/log-and-die');
import c = require('../test-constants');

declare let browser: any;

let everyonesBrowsers;
let maja;
let majasBrowser;
let michael;
let michaelsBrowser;
let strangersBrowser;

let data;
let idAddress: IdAddress;
let siteId: any;

const majasComment = 'majasComment';
const michaelsComment = 'michaelsComment';

const localHostname = 'comments-for-e2e-test-emballgi-localhost-8080';
const embeddingOrigin = 'http://e2e-test-emballgi.localhost:8080';
const pageSlug = 'emb-cmts-all-logins.html';
const pageUrl = embeddingOrigin + '/' + pageSlug;


describe("emb cmts all logins", () => {

  if (!settings.include3rdPartyDependentTests) {
    console.log("Skipping this spec; no 3rd party login credentials specified.");
    return;
  }

  it("initialize people", () => {
    browser.perhapsDebugBefore();
    everyonesBrowsers = _.assign(browser, pagesFor(browser));
    majasBrowser = everyonesBrowsers;
    michaelsBrowser = everyonesBrowsers;
    maja = make.memberMaria();
    michael = make.memberMichael();
  });

  it("import a site", () => {
    const site: SiteData = make.forumOwnedByOwen('embdscid', { title: "Emb Cmts Disc Id Test" });
    site.meta.localHostname = localHostname;
    site.settings.allowEmbeddingFrom = embeddingOrigin;
    site.settings.requireVerifiedEmail = false;
    site.settings.mayPostBeforeEmailVerified = true;
    idAddress = server.importSiteData(site);
    siteId = idAddress.id;
  });

  it("create embedding page", () => {
    const dirPath = 'target';
    fs.writeFileSync(`${dirPath}/${pageSlug}`, `
<html>
<head>
<title>Embedded comments E2E test</title>
</head>
<body style="background: black; color: #ccc; font-family: monospace">
<p>This is an embedded comments E2E test page. Ok to delete. 27KT5QAX29. The comments:</p>

<script>edCommentsServerUrl='http://${localHostname}.localhost';</script>
<script async defer src="http://${localHostname}.localhost/-/ed-comments.v0.min.js"></script>
<!-- You can specify a per page discussion id on the next line, if your URLs might change. -->
<div class="ed-comments" style="margin-top: 45px;">
<noscript>Please enable Javascript to view comments.</noscript>
<p style="margin-top: 25px; opacity: 0.9; font-size: 96%">Comments powered by
<a href="https://www.effectivediscussions.org">Effective Discussions</a>.</p>

<p>/End of page.</p>
</body>
</html>`);
  });

  it("Maja opens the embedding page", () => {
    majasBrowser.go(pageUrl);
    majasBrowser.switchToEmbeddedCommentsIrame();
    majasBrowser.disableRateLimits();
  });

  it("... clicks Reply and logs in, with Gmail @gmail", () => {
    majasBrowser.topic.clickReplyToEmbeddingBlogPost();
    majasBrowser.swithToOtherTabOrWindow();
    majasBrowser.loginDialog.loginWithGmail({
      email: settings.gmailEmail,
      password: settings.gmailPassword,
    }, true);
    majasBrowser.waitAndSetValue('.esCreateUserDlg #e2eUsername', maja.username);
    majasBrowser.loginDialog.clickSubmit();
    majasBrowser.loginDialog.acceptTerms(false);
    majasBrowser.switchBackToFirstTabOrWindow();
  });

  it("... posts a comment", () => {
    majasBrowser.switchToEmbeddedEditorIrame();
    majasBrowser.editor.editText(majasComment);
    majasBrowser.editor.save();
  });

  it("... it appears", () => {
    majasBrowser.switchToEmbeddedCommentsIrame();
    majasBrowser.topic.waitForPostNrVisible(2);  // that's the first reply nr, = comment 1
    majasBrowser.topic.assertPostTextMatches(2, majasComment);
  });

  it("Michael logs in, with Facebook", () => {
    michaelsBrowser.waitAndClick('.dw-a-logout');
    // Now the page reloads. Wait.
    michaelsBrowser.switchToEmbeddedCommentsIrame();
    michaelsBrowser.topic.waitForReplyButtonAssertCommentsVisible();
    michaelsBrowser.topic.clickReplyToEmbeddingBlogPost();
    michaelsBrowser.swithToOtherTabOrWindow();
    michaelsBrowser.loginDialog.loginWithFacebook({
      email: settings.facebookUserEmail,
      password: settings.facebookUserPassword,
    }, true);
    michaelsBrowser.waitAndSetValue('.esCreateUserDlg #e2eUsername', michael.username);
    michaelsBrowser.loginDialog.clickSubmit();
    michaelsBrowser.loginDialog.acceptTerms(false);
    michaelsBrowser.switchBackToFirstTabOrWindow();
  });

  it("... posts a comment", () => {
    michaelsBrowser.switchToEmbeddedEditorIrame();
    michaelsBrowser.editor.editText(michaelsComment);
    michaelsBrowser.editor.save();
  });

  it("... it appears", () => {
    michaelsBrowser.switchToEmbeddedCommentsIrame();
    michaelsBrowser.topic.waitForPostNrVisible(2);
    michaelsBrowser.topic.waitForPostNrVisible(3);
    michaelsBrowser.topic.assertPostTextMatches(2, majasComment);
    michaelsBrowser.topic.assertPostTextMatches(3, michaelsComment);
  });


  it("Done", () => {
    everyonesBrowsers.perhapsDebug();
  });

});

