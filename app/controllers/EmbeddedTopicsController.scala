/**
 * Copyright (c) 2013 Kaj Magnus Lindberg
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

import com.debiki.core._
import debiki._
import debiki.EdHttp._
import debiki.dao.SiteDao
import ed.server.{EdContext, EdController, RenderedPage}
import ed.server.http._
import javax.inject.Inject
import play.api.mvc.ControllerComponents


/** Shows embedded comments.
  */
class EmbeddedTopicsController @Inject()(cc: ControllerComponents, edContext: EdContext)
  extends EdController(cc, edContext) {

  import context.globals

  def showTopic(embeddingUrl: String, discussionId: Option[AltPageId], edPageId: Option[PageId]) =
        AsyncGetAction { request =>
    import request.dao

    val anyRealPageId = getAnyRealPageId(edPageId, discussionId, embeddingUrl, dao)
    val (renderedPage, pageRequest) = anyRealPageId match {
      case None =>
        // Embedded comments page not yet created. Return a dummy page; we'll create a real one,
        // later when the first reply gets posted.
        val pageRequest = ViewPageController.makeEmptyPageRequest(request, EmptyPageId, showId = true,
            PageRole.EmbeddedComments, globals.now())
        val jsonStuff = ReactJson.pageThatDoesNotExistsToJson(
          dao, PageRole.EmbeddedComments, Some(DefaultCategoryId))
        // Don't render server side, render client side only. Search engines shouldn't see it anyway,
        // because it doesn't exist.
        // So skip: ReactRenderer.renderPage(jsonStuff.jsonString)
        val tpi = new PageTpi(pageRequest, jsonStuff.jsonString, jsonStuff.version,
          "Dummy cached html [EdM2GRVUF05]", WrongCachedPageVersion,
          jsonStuff.pageTitle, jsonStuff.customHeadTags)
        val htmlString = views.html.templates.page(tpi).body
        (RenderedPage(htmlString, unapprovedPostAuthorIds = Set.empty), pageRequest)
      case Some(realId) =>
        val pageMeta = dao.getThePageMeta(realId)
        val pagePath = PagePath(siteId = request.siteId, folder = "/", pageId = Some(realId),
          showId = true, pageSlug = "")
        SECURITY; COULD // do the standard auth stuff here, but not needed right now since we
        // proceed only if is embedded comments page. So, right now all such pages are public.
        if (pageMeta.pageRole != PageRole.EmbeddedComments)
          throwForbidden("EdE2F6UHY3", "Not an embedded comments page")
        val pageRequest = new PageRequest[Unit](
          request.siteIdAndCanonicalHostname,
          sid = request.sid,
          xsrfToken = request.xsrfToken,
          browserId = request.browserId,
          user = request.user,
          pageExists = true,
          pagePath = pagePath,
          pageMeta = Some(pageMeta),
          dao = dao,
          request = request.request)
        (request.dao.renderPageMaybeUseCache(pageRequest), pageRequest)
    }

    ViewPageController.addVolatileJson(renderedPage, pageRequest)
  }


  def showEmbeddedEditor(embeddingUrl: String, discussionId: Option[AltPageId],
        edPageId: Option[PageId]) = GetAction { request =>
    val anyRealPageId = getAnyRealPageId(edPageId, discussionId, embeddingUrl, request.dao)
    val tpi = new EditPageTpi(request, PageRole.EmbeddedComments, anyCurrentPageId = anyRealPageId,
      anyAltPageId = discussionId, anyEmbeddingUrl = Some(embeddingUrl))
    Ok(views.html.embeddedEditor(tpi).body) as HTML
  }


  private def getAnyRealPageId(edPageId: Option[PageId], discussionId: Option[String],
        embeddingUrl: String, dao: SiteDao): Option[PageId] = {
    // Lookup the page by real id, if specified, otherwise alt id, or embedding url.
    edPageId orElse {
      val altId = discussionId.getOrElse(embeddingUrl)
      dao.getRealPageId(altId)
    }
  }


  def showSetupInstructions = AdminGetAction { request =>
    ??? /*
    if (request.dao.loadSiteStatus() != SiteStatus.IsEmbeddedSite)
      throwForbidden("DwE21FG4", "This is currently not an embedded comments site")

    Ok(views.html.createsite.embeddingSiteInstructionsPage(
      SiteTpi(request), request.dao.loadSite()).body) as HTML
      */
  }

}
