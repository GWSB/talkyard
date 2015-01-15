/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
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

package controllers

import actions.ApiActions._
import actions.PageActions._
import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import java.{util => ju, io => jio}
import play.api._
import play.api.Play.current
import play.api.mvc.{Action => _, _}
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import requests._
import DebikiHttp._
import Utils.ValidationImplicits._
import Utils.{OkHtml, OkXml}



/** Shows pages and individual posts.
  *
  * Also loads the users permissions on the page, and info on which
  * comments the user has authored or rated, and also loads the user's
  * comments that are pending approval — although such unapproved comments
  * aren't loaded, when other people view the page.
  */
object ViewPageController extends mvc.Controller {


  val HtmlEncodedUserSpecificDataJsonMagicString =
    "__html_encoded_user_specific_data_json__"


  def viewPost(pathIn: PagePath) = PageGetAction(pathIn, pageMustExist = false) { pageReq =>
    if (!pageReq.pageExists) {
      if (pageReq.pagePath.value == "/") {
        // TemplateRenderer will show a getting-started or create-something-here page.
        viewPostImpl(makeEmptyPageRequest(pageReq, pageId = "0"))
      }
      else {
        throwNotFound("DwE404", "Page not found")
      }
    }
    else {
      viewPostImpl(pageReq)
    }
  }


  def viewPostImpl(pageReq: PageGetRequest) = {
    var pageHtml = pageReq.dao.renderTemplate(pageReq)
    val anyUserSpecificDataJson = ReactJson.userDataJson(pageReq)

    // Insert user specific data into the HTML.
    // The Scala templates take care to place the <script type="application/json">
    // tag with the magic-string-that-we'll-replace-with-user-specific-data before
    // unsafe data like JSON and HTML for comments and the page title and body.
    anyUserSpecificDataJson foreach { json =>
      val htmlEncodedJson = org.owasp.encoder.Encode.forHtmlContent(json.toString)
      pageHtml = org.apache.commons.lang3.StringUtils.replaceOnce(
        pageHtml, HtmlEncodedUserSpecificDataJsonMagicString, htmlEncodedJson)
    }

    Ok(pageHtml) as HTML
  }


  // dupl code TODO merge with app/controllers/EmbeddedTopicsController.scala
  private def makeEmptyPageRequest(request: PageGetRequest, pageId: PageId): PageGetRequest = {
    val pagePath = PagePath(
      tenantId = request.siteId,
      folder = "/",
      pageId = Some(pageId),
      showId = false,
      pageSlug = "")

    val pageParts = PageParts(pageId)

    val newTopicMeta = PageMeta.forNewPage(
      PageRole.WebPage,
      author = SystemUser.User,
      parts = pageParts,
      creationDati = new ju.Date,
      parentPageId = None,
      publishDirectly = true)

    new requests.DummyPageRequest(
      sid = request.sid,
      xsrfToken = request.xsrfToken,
      browserId = request.browserId,
      user = request.user,
      pageExists = false,
      pagePath = pagePath,
      pageMeta = newTopicMeta,
      permsOnPage = PermsOnPage.Wiki, // for now
      dao = request.dao,
      dummyPageParts = pageParts,
      request = request.request)
  }

}
