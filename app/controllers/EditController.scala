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
import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.DebikiHttp._
import debiki.ReactJson.JsStringOrNull
import debiki.onebox.Onebox
import debiki.antispam.AntiSpam.throwForbiddenIfSpam
import play.api._
import play.api.libs.json._
import play.api.mvc.{Action => _, _}
import requests._
import scala.concurrent.ExecutionContext.Implicits.global
import Utils.{OkSafeJson, parseIntOrThrowBadReq}


/** Edits pages and posts.
  */
object EditController extends mvc.Controller {

  val EmptyPostErrorMessage =
    o"""Cannot save empty posts. If you want to delete this post, please click
        More just below the post, and then Delete. However only the post author
        and staff members can do this."""


  def loadDraftAndGuidelines(writingWhat: String, categoryId: String, pageRole: String) =
        GetAction { request =>

    val writeWhat = writingWhat.toIntOption.flatMap(WriteWhat.fromInt) getOrElse throwBadArgument(
      "DwE4P6CK0", "writingWhat")

    val categoryIdInt = categoryId.toIntOption getOrElse throwBadArgument(
      "DwE5PFKQ2", "categoryId")

    val thePageRole = pageRole.toIntOption.flatMap(PageRole.fromInt) getOrElse throwBadArgument(
      "DwE6PYK8", "pageRole")

    val guidelinesSafeHtml = writeWhat match {
      case WriteWhat.ChatComment =>
        Some(ChatCommentGuidelines)
      case WriteWhat.Reply =>
        Some(ReplyGuidelines)
      case WriteWhat.ReplyToOriginalPost =>
        if (thePageRole == PageRole.Critique) Some(GiveCritiqueGuidelines) // [plugin]
        else Some(ReplyGuidelines)
      case WriteWhat.OriginalPost =>
        if (thePageRole == PageRole.Critique) Some(AskForCritiqueGuidelines) // [plugin]
        else None // Some(OriginalPostGuidelines)
    }

    OkSafeJson(Json.obj(
      "guidelinesSafeHtml" -> JsStringOrNull(guidelinesSafeHtml)))
  }


  /** Sends back a post's current CommonMark source to the browser.
    */
  def loadCurrentText(pageId: String, postId: String) = GetAction { request =>
    val postIdAsInt = parseIntOrThrowBadReq(postId, "DwE1Hu80")
    val post = request.dao.loadPost(pageId, postId.toInt) getOrElse
      throwNotFound("DwE7SKE3", "Post not found")

    if (!debiki.dao.PostsDao.userMayEdit(request.theUser, post))
      throwForbidden("DwE8FKY0", "Not your post")

    val json = Json.obj("currentText" -> post.currentSource)
    OkSafeJson(json)
  }


  /** Edits posts.
    */
  def edit = AsyncPostJsonAction(RateLimits.EditPost, maxLength = MaxPostSize) {
        request: JsonPostRequest =>
    val pageId = (request.body \ "pageId").as[PageId]
    val postId = (request.body \ "postId").as[PostId]
    val newText = (request.body \ "text").as[String]

    if (postId == PageParts.TitleId)
      throwForbidden("DwE5KEWF4", "Edit the title via /-/edit-title-save-settings instead")

    if (newText.isEmpty)
      throwBadReq("DwE6KEFW8", EmptyPostErrorMessage)

    _throwIfTooMuchData(newText, request)

    val newTextAndHtml = TextAndHtml(newText, isTitle = false,
      allowClassIdDataAttrs = postId == PageParts.BodyId)
      // When follow links? Previously:
      // followLinks = postToEdit.createdByUser(page.parts).isStaff && editor.isStaff

    Globals.antiSpam.detectPostSpam(request, pageId, newTextAndHtml) map { isSpamReason =>
      throwForbiddenIfSpam(isSpamReason, "DwE6PYU4")

      request.dao.editPostIfAuth(pageId = pageId, postId = postId, editorId = request.theUser.id,
        request.theBrowserIdData, newTextAndHtml)

      OkSafeJson(ReactJson.postToJson2(postId = postId, pageId = pageId,
        request.dao, includeUnapproved = true))
    }
  }


  /** Downloads the linked resource via an external request to the URL (assuming it's
    * a trusted safe site) then creates and returns sanitized onebox html.
    */
  def onebox(url: String) = AsyncGetActionRateLimited(RateLimits.LoadOnebox) { request =>
    Onebox.loadRenderSanitize(url, javascriptEngine = None).transform(
      html => Ok(html),
      throwable => ResultException(BadReqResult("DwE4PKE2", "Cannot onebox that link")))
  }


  def loadPostRevisions(postId: String, revisionNr: String) = GetAction { request =>
    val postIdInt = postId.toIntOption getOrElse throwBadRequest("DwE8FKP", "Bad post id")
    val revisionNrInt =
      if (revisionNr == "LastRevision") PostRevision.LastRevisionMagicNr
      else revisionNr.toIntOption getOrElse throwBadRequest("EdE8UFMW2", "Bad revision nr")
    val revisionsRecentFirst =
      request.dao.loadSomeRevisionsRecentFirst(postIdInt, revisionNrInt, atLeast = 5)
    val revisionsJson = revisionsRecentFirst map { revision =>
      val isStaffOrComposer = request.isStaff || request.theUserId == revision.composedById
      ReactJson.postRevisionToJson(revision, maySeeHidden = isStaffOrComposer)
    }
    OkSafeJson(JsArray(revisionsJson))
  }


  def changePostType = PostJsonAction(RateLimits.EditPost, maxLength = 100) { request =>
    val pageId = (request.body \ "pageId").as[PageId]
    val postNr = (request.body \ "postNr").as[PostId]
    val newTypeInt = (request.body \ "newType").as[Int]
    val newType = PostType.fromInt(newTypeInt) getOrElse throwBadArgument("DwE4EWL3", "newType")

    request.dao.changePostType(pageId = pageId, postNr = postNr, newType,
      changerId = request.theUser.id, request.theBrowserIdData)
    Ok
  }


  def deletePost = PostJsonAction(RateLimits.DeletePost, maxLength = 5000) { request =>
    val pageId = (request.body \ "pageId").as[PageId]
    val postNr = (request.body \ "postNr").as[PostId]
    val repliesToo = (request.body \ "repliesToo").as[Boolean]

    val action =
      if (repliesToo) PostStatusAction.DeleteTree
      else PostStatusAction.DeletePost(clearFlags = false)

    request.dao.changePostStatus(postNr, pageId = pageId, action, userId = request.theUserId)

    OkSafeJson(ReactJson.postToJson2(postId = postNr, pageId = pageId, // COULD: don't include post in reply? It'd be annoying if other unrelated changes were loaded just because the post was toggled open?
      request.dao, includeUnapproved = request.theUser.isStaff))
  }


  private def _throwIfTooMuchData(text: String, request: DebikiRequest[_]) {
    val postSize = text.length
    val user = request.user_!
    if (user.isAdmin) {
      // Allow up to MaxPostSize chars (see above).
    }
    else if (user.isAuthenticated) {
      if (postSize > MaxPostSizeForAuUsers)
        throwEntityTooLarge("DwE413kX5", "Please do not upload that much text")
    }
    else {
      if (postSize > MaxPostSizeForUnauUsers)
        throwEntityTooLarge("DwE413IJ1", "Please do not upload that much text")
    }
  }


  val ReplyGuidelines = i"""
    |<p>Be kind to the others.
    |<p>Criticism is welcome — and criticize ideas, not people.
    |"""

  val ChatCommentGuidelines = i"""
    |<p>You're writing a chat comment (or status update).
    |
    |<p>If you intended to reply to the Original Post (at the top of the page),
    |then click Cancel below, and instead click the reply button just below the Original Post.
    |
    |<p>Be kind to the others.
    |"""

  val GiveCritiqueGuidelines = /* [plugin] */ i"""
    |<p>You give critique in order to help the poster to improve his/her work:
    |<ul>
    |<li>Tell what you think won't work and should be improved, perhaps removed.
    |<li>Try to suggest improvements.
    |<li>Be friendly and polite.
    |<li>Mention things you like.
    |</ul>
    |"""

  val AskForCritiqueGuidelines = /* [plugin] */ i"""
    |<p>Type a title in the topmost filed below.
    |<p>Below the title, add a link to your work, or upload an image (drag and drop it).
    |And tell people what you want feedback about.
    |<p>This is a public forum — anyone is welcome to help you.</p>
    |"""

  // This advice actually feels mostly annoying to me: (so currently not in use)
  val OriginalPostGuidelines = i"""
    |<p>In order for more people to reply to you:
    |<ul>
    |<li>Try to come up with a good topic title, so others will understand what the topic is about
    |  and read it.
    |<li>Including good search words might help others find your topic.
    |</ul>
    |"""
}

