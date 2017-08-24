/**
 * Copyright (c) 2012-2015 Kaj Magnus Lindberg
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

package debiki.dao

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.EdHttp.{throwNotFound, throwInternalError}
import ed.server.http.PageRequest
import play.{api => p}
import RenderedPageHtmlDao._
import ed.server.RenderedPage


object RenderedPageHtmlDao {

  def renderedPageKey(sitePageId: SitePageId) =
    MemCacheKey(sitePageId.siteId, s"${sitePageId.pageId}|PageHtml")

}


trait RenderedPageHtmlDao {
  self: SiteDao =>

  import context.globals


  memCache.onPageCreated { page =>
    uncacheForums(this.siteId)
  }

  memCache.onPageSaved { sitePageId =>
    uncacheRenderedPage(sitePageId)
    uncacheForums(sitePageId.siteId)
  }


  private def loadPageFromDatabaseAndRender(pageRequest: PageRequest[_]): RenderedPage = {
    if (!pageRequest.pageExists)
      throwNotFound("EdE00404", "Page not found")

    globals.mostMetrics.getRenderPageTimer(pageRequest.pageRole).time {
      val anyPageQuery = pageRequest.parsePageQuery()
      val anyPageRoot = pageRequest.pageRoot

      val renderResult = ReactJson.pageToJson(pageRequest.thePageId, this, anyPageRoot, anyPageQuery)

      val (cachedHtml, cachedVersion) =
        renderContent(pageRequest.thePageId, renderResult.version, renderResult.jsonString)

      val tpi = new PageTpi(pageRequest, renderResult.jsonString, renderResult.version,
        cachedHtml, cachedVersion, renderResult.pageTitle, renderResult.customHeadTags)

      val pageHtml: String = views.html.templates.page(tpi).body

      RenderedPage(pageHtml, renderResult.unapprovedPostAuthorIds)
    }
  }


  def renderPageMaybeUseCache(pageReq: PageRequest[_]): RenderedPage = {
    // Bypass the cache if the page doesn't yet exist (it's being created),
    // because in the past there was some error because non-existing pages
    // had no ids (so feels safer to bypass).
    var useMemCache = pageReq.pageExists
    useMemCache &= pageReq.pageRoot.contains(PageParts.BodyNr)
    useMemCache &= !pageReq.debugStats
    useMemCache &= !pageReq.bypassCache
    if (globals.isProd) {
      // The request.host will be included in the generated html, and if it doesn't match
      // the correct hostname + port then don't cache the html, because if e.g. the port is
      // wrong, WebSocket or cookie domains will be wrong, thing's won't work, later
      // when loading the page via the correct hostname + port (because wrong host in cache).
      // (Reasons for "wrong" hostname & port include: testing on localhost, or calling the Play
      // app directly via its own port and cURL, rather than accessing via Nginx port 80/443.)
      useMemCache &= pageReq.host == pageReq.canonicalHostname + globals.colonPort
    }
    else {
      // Caching different hostname is ok, but caching the wrong port is annoying because
      // then Nginx+Nchan work and I have to restart the Play server to empty the cache.
      useMemCache &= pageReq.colonPort == globals.colonPort
    }

    // When paginating forum topics in a non-Javascript client, we cannot use the cache.
    useMemCache &= pageReq.parsePageQuery().isEmpty

    if (!useMemCache)
      return loadPageFromDatabaseAndRender(pageReq)

    val key = renderedPageKey(pageReq.theSitePageId)
    memCache.lookup(key, orCacheAndReturn = {
      if (pageReq.thePageRole == PageRole.Forum) {
        rememberForum(pageReq.thePageId)
      }
      Some(loadPageFromDatabaseAndRender(pageReq))
    }, metric = globals.mostMetrics.renderPageCacheMetrics) getOrDie "DwE93IB7"
  }


  private def renderContent(pageId: PageId, currentVersion: CachedPageVersion,
        reactStore: String): (String, CachedPageVersion) = {
    // COULD reuse the caller's transaction, but the caller currently uses > 1  :-(
    // Or could even do this outside any transaction.
    readOnlyTransaction { transaction =>
      transaction.loadCachedPageContentHtml(pageId) foreach { case (cachedHtml, cachedVersion) =>
        // Here we can compare hash sums of the up-to-date data and the data that was
        // used to generate the cached page. We ignore page version and site version.
        // Hash sums are always correct, so we'll never rerender unless we actually need to.
        if (cachedVersion.appVersion != currentVersion.appVersion ||
            cachedVersion.dataHash != currentVersion.dataHash) {
          // The browser will make cachedhtml up-to-date by running React.js with up-to-date
          // json, so it's okay to return cachedHtml. However, we'd rather send up-to-date
          // html, and this page is being accessed, so regenerate html. [4KGJW2]
          p.Logger.debug(o"""Page $pageId site $siteId is accessed and out-of-date,
               sending rerender-in-background message [DwE5KGF2]""")
          // COULD wait 150 ms for the background thread to finish rendering the page?
          // Then timeout and return the old cached page.
          globals.renderPageContentInBackground(SitePageId(siteId, pageId))
        }
        return (cachedHtml, cachedVersion)
      }
    }

    // Now we'll have to render the page contents [5KWC58], so we have some html to send back
    // to the client, in case the client is a search engine bot — I suppose those
    // aren't happy with only up-to-date json (but no html) + running React.js.
    val newHtml = ReactRenderer.renderPage(reactStore) getOrElse throwInternalError(
      "DwE500RNDR", "Error rendering page")

    readWriteTransaction { transaction =>
      transaction.saveCachedPageContentHtmlPerhapsBreakTransaction(
        pageId, currentVersion, newHtml)
    }
    (newHtml, currentVersion)
  }


  private def rememberForum(forumPageId: PageId) {
    var done = false
    do {
      val key = this.forumsKey(siteId)
      memCache.lookup[List[PageId]](key) match {
        case None =>
          done = memCache.putIfAbsent(key, MemCacheValueIgnoreVersion(List(forumPageId)))
        case Some(cachedForumIds) =>
          if (cachedForumIds contains forumPageId)
            return
          val newForumIds = forumPageId :: cachedForumIds
          done = memCache.replace(key, MemCacheValueIgnoreVersion(cachedForumIds),
            newValue = MemCacheValueIgnoreVersion(newForumIds))
      }
    }
    while (!done)
  }


  private def uncacheRenderedPage(sitePageId: SitePageId) {
    memCache.remove(renderedPageKey(sitePageId))

    // Don't remove the cached contents, because it takes long to regenerate. [6KP368]
    // Instead, send old stale cached content html to the browsers,
    // and they'll make it up-to-date when they render the React json client side
    // (we do send up-to-date json, always). — After a short while, some background
    // threads will have regenerated the content and updated the cache, and then
    // the browsers will get up-to-date html again.
    // So don't:
    //    removeFromCache(contentKey(sitePageId))
    // Instead:
    globals.renderPageContentInBackground(sitePageId)

    // BUG race condition: What if anotoher thread started rendering a page
    // just before the invokation of this function, and is finished
    // just after we've cleared the cache? Then that other thread will insert
    // a stale cache item. Could fix, by verifying that when you cache
    // something, the current version of the page is the same as the page
    // version when you started generating the cache value (e.g. started
    // rendering a page).
  }


  /** Forums list other pages sorted by modification time, so whenever any
    * page is modified, it's likely that a forum page should be rerendered.
    * Also, if a new category is added, the parent forum should be rerendered.
    * For simplicity, we here uncache all forums.
    */
  private def uncacheForums(siteId: SiteId) {
    val forumIds = memCache.lookup[List[String]](forumsKey(siteId)) getOrElse Nil
    for (forumId <- forumIds) {
      memCache.remove(renderedPageKey(SitePageId(siteId, forumId)))
    }
    // Don't remove any cached content, see comment above. [6KP368]
  }


  private def forumsKey(siteId: SiteId) =
    MemCacheKey(siteId, "|ForumIds") // "|" is required by a debug check function

}

