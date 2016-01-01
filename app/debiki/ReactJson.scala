/**
 * Copyright (C) 2012 Kaj Magnus Lindberg (born 1979)
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

package debiki

import com.debiki.core._
import com.debiki.core.Prelude._
import controllers.ForumController
import debiki.dao.{ReviewStuff, PageStuff, SiteDao, PageDao}
import debiki.DebikiHttp.throwNotFound
import io.efdi.server.http.{DebikiRequest, PageRequest}
import java.{util => ju}
import play.api.libs.json._
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.math.BigDecimal.decimal


object ReactJson {

  /** If there are more than this many visible replies, we'll summarize the page, otherwise
    * it'll take a bit long to render in the browser, especially on mobiles.
    */
  val SummarizeNumRepliesVisibleLimit = 80

  /** If we're summarizing a page, we'll show the first replies to each comment non-summarized.
    * But the rest will be summarized.
    */
  val SummarizeSiblingIndexLimit = 5

  val SummarizeAllDepthLimit = 5

  /** If we're summarizing a page, we'll squash the last replies to a comment into one
    * single "Click to show more comments..." html elem.
    */
  val SquashSiblingIndexLimit = 8

  /** Like a tweet :-)  */
  val PostSummaryLength = 140

  /** Posts shorter than this won't be summarized if they're one single paragraph only,
    * because the "Click to show..." text would then make the summarized post as large
    * as the non-summarized version.
    */
  val SummarizePostLengthLimit =
    PostSummaryLength + 80 // one line is roughly 80 chars



  def pageToJson(
        pageId: PageId,
        dao: SiteDao,
        anyPageRoot: Option[PostNr] = None,
        anyPageQuery: Option[PageQuery] = None): (String, CachedPageVersion) = {
    dao.readOnlyTransaction(
      pageToJsonImpl(pageId, dao, _, anyPageRoot, anyPageQuery))
  }


  /** When a site has just been created, and has no contents.
    */
  def emptySiteJson(pageReq: PageRequest[_]): JsObject = {
    require(!pageReq.pageExists, "DwE7KEG2")
    require(pageReq.pagePath.value == HomepageUrlPath, "DwE8UPY4")
    val siteStatusString = loadSiteStatusString(pageReq.dao)
    val siteSettings = pageReq.dao.loadWholeSiteSettings()
    Json.obj(
      "now" -> JsNumber((new ju.Date).getTime),
      "siteStatus" -> JsString(siteStatusString),
      "guestLoginAllowed" -> JsBoolean(siteSettings.guestLoginAllowed && pageReq.siteId == KajMagnusSiteId),
      "userMustBeAuthenticated" -> JsBoolean(siteSettings.userMustBeAuthenticated.asBoolean),
      "userMustBeApproved" -> JsBoolean(siteSettings.userMustBeApproved.asBoolean),
      "pageId" -> pageReq.thePageId,
      "pageRole" -> JsNumber(pageReq.thePageRole.toInt),
      "pagePath" -> JsString(pageReq.pagePath.value),
      "numPosts" -> JsNumber(0),
      "numPostsRepliesSection" -> JsNumber(0),
      "numPostsChatSection" -> JsNumber(0),
      "numPostsExclTitle" -> JsNumber(0),
      "isInEmbeddedCommentsIframe" -> JsBoolean(false),
      "categories" -> JsArray(),
      "topics" -> JsArray(),
      "user" -> NoUserSpecificData,
      "rootPostId" -> JsNumber(PageParts.BodyNr),
      "allPosts" -> JsObject(Nil),
      "topLevelCommentIdsSorted" -> JsArray(),
      "horizontalLayout" -> JsBoolean(false),
      "socialLinksHtml" -> JsNull)
  }


  def loadSiteStatusString(dao: SiteDao): String =
    dao.loadSiteStatus() match {
      case SiteStatus.OwnerCreationPending(adminEmail) =>
        var obfuscatedEmail = adminEmail.takeWhile(_ != '@')
        obfuscatedEmail = "" //obfuscatedEmail.dropRight(3).take(4) -- remove obfuscatedEmail?
        s"AdminCreationPending:$obfuscatedEmail"
      case x => x.toString
    }


  private def pageToJsonImpl(
        pageId: PageId,
        dao: SiteDao,
        transaction: SiteTransaction,
        anyPageRoot: Option[PostNr],
        anyPageQuery: Option[PageQuery]): (String, CachedPageVersion) = {

    val socialLinksHtml = dao.loadWholeSiteSettings().socialLinksHtml.valueAsString
    val page = PageDao(pageId, transaction)
    val pageParts = page.parts
    pageParts.loadAllPosts()

    var numPosts = 0
    var numPostsRepliesSection = 0
    var numPostsChatSection = 0

    var allPostsJson = pageParts.allPosts filter { post =>
      !post.deletedStatus.isDeleted || (
        post.deletedStatus.onlyThisDeleted && pageParts.hasNonDeletedSuccessor(post.nr))
    } map { post: Post =>
      numPosts += 1
      if (post.tyype == PostType.Flat)
        numPostsChatSection += 1
      else if (!post.isOrigPost && !post.isTitle)
        numPostsRepliesSection += 1

          // Ooops two other Nashorn JSON parser bugs, happen in 'dist' mode only:
          // 1. java.lang.ArrayIndexOutOfBoundsException: Array index out of range: 84
          // 2. The 1 and 2 etc items in:  { 1: ..., 2: ..., 0: ...}
          //    are thrown away because 0 is last. Works fine with 0 first though.
          // Solve by using string keys instead, not numeric keys: prefix underscore.
          // Fixed in later Nashorn versions, see:
          //   http://hg.openjdk.java.net/jdk9/dev/nashorn/rev/dec3faccd3de
          //   http://mail.openjdk.java.net/pipermail/nashorn-dev/2015-March/004284.html
          // COULD remove this workaround when upgraded to JDK 8u60, will be released August 2015)
          // Also remove in in ReactRenderer and debikiScripts.scala.html, see [64KEWF2].
      ("_" + post.nr.toString) -> postToJsonImpl(post, page, transaction.currentTime)
    }

    val numPostsExclTitle = numPosts - (if (pageParts.titlePost.isDefined) 1 else 0)

    if (page.role == PageRole.EmbeddedComments) {
      allPostsJson +:=
        PageParts.BodyNr.toString ->
          embeddedCommentsDummyRootPost(pageParts.topLevelComments)
    }

    val topLevelComments = pageParts.topLevelComments
    val topLevelCommentIdsSorted =
      Post.sortPostsBestFirst(topLevelComments).map(reply => JsNumber(reply.nr))

    val (anyForumId: Option[PageId], ancestorsJsonRootFirst: Seq[JsObject]) =
      makeForumIdAndAncestorsJson(page.meta, dao)

    val anyLatestTopics: Seq[JsObject] =
      if (page.role == PageRole.Forum) {
        val rootCategoryId = page.meta.categoryId.getOrDie(
          "DwE7KYP2", s"Forum page '${page.id}', site '${transaction.siteId}', has no category id")
        val orderOffset = anyPageQuery.getOrElse(
          PageQuery(PageOrderOffset.ByBumpTime(None), PageFilter.ShowAll))
        val topics = ForumController.listTopicsInclPinned(rootCategoryId, orderOffset, dao,
          includeDescendantCategories = true,
          // For now, filter out hidden-in-forum client side if !isStaff.
          // [redux] Rewrite later by loading topics in hidden-in-forum cats only if isStaff,
          // and adding them into the react-flux-redux state tree.
          includeHiddenInForum = true,
          limit = ForumController.NumTopicsToList)
        val pageStuffById = dao.loadPageStuff(topics.map(_.pageId))
        topics.map(controllers.ForumController.topicToJson(_, pageStuffById))
      }
      else {
        Nil
      }

    val messageMemberIds = transaction.loadMessageMembers(pageId)
    val messageMembers = messageMemberIds.toSeq.flatMap(transaction.loadUser)

    val siteStatusString = loadSiteStatusString(dao)
    val siteSettings = dao.loadWholeSiteSettings()
    val pageSettings = dao.loadSinglePageSettings(pageId)
    val horizontalLayout = page.role == PageRole.MindMap ||
      pageSettings.horizontalComments.valueAsBoolean
    val is2dTreeDefault = pageSettings.horizontalComments.valueAsBoolean
    val showForumCategories =
      if (page.role == PageRole.Forum) Some(siteSettings.showForumCategories.asBoolean)
      else None

    val jsonObj = Json.obj(
      "siteStatus" -> JsString(siteStatusString),
      "guestLoginAllowed" -> JsBoolean(siteSettings.guestLoginAllowed && transaction.siteId == KajMagnusSiteId),
      "userMustBeAuthenticated" -> JsBoolean(siteSettings.userMustBeAuthenticated.asBoolean),
      "userMustBeApproved" -> JsBoolean(siteSettings.userMustBeApproved.asBoolean),
      "pageId" -> pageId,
      "messageMembers" -> JsArray(messageMembers.map(JsUser)),
      "categoryId" -> JsNumberOrNull(page.meta.categoryId),
      "forumId" -> JsStringOrNull(anyForumId),
      "showForumCategories" -> JsBooleanOrNull(showForumCategories),
      "ancestorsRootFirst" -> ancestorsJsonRootFirst,
      "pageRole" -> JsNumber(page.role.toInt),
      "pagePath" -> JsString(page.thePath.value),
      "pinOrder" -> JsNumberOrNull(page.meta.pinOrder),
      "pinWhere" -> JsNumberOrNull(page.meta.pinWhere.map(_.toInt)),
      "pageAnsweredAtMs" -> dateOrNull(page.meta.answeredAt),
      "pageAnswerPostUniqueId" -> JsNumberOrNull(page.meta.answerPostUniqueId),
      "pagePlannedAtMs" -> dateOrNull(page.meta.plannedAt),
      "pageDoneAtMs" -> dateOrNull(page.meta.doneAt),
      "pageClosedAtMs" -> dateOrNull(page.meta.closedAt),
      "pageLockedAtMs" -> dateOrNull(page.meta.lockedAt),
      "pageFrozenAtMs" -> dateOrNull(page.meta.frozenAt),
      //"pageDeletedAtMs" -> ...
      "numPosts" -> numPosts,
      "numPostsRepliesSection" -> numPostsRepliesSection,
      "numPostsChatSection" -> numPostsChatSection,
      "numPostsExclTitle" -> numPostsExclTitle,
      "maxUploadSizeBytes" -> Globals.maxUploadSizeBytes,
      "isInEmbeddedCommentsIframe" -> JsBoolean(page.role == PageRole.EmbeddedComments),
      "categories" -> categoriesJson(page,
        // For now, filter out hidden-in-forum client side if !isStaff.
        // [redux] Rewrite later by loading all catetgories if isStaff, and adding
        // them into the react-flux-redux state tree.
         includeHiddenInForum = true, dao),
      "topics" -> JsArray(anyLatestTopics),
      "user" -> NoUserSpecificData,
      "rootPostId" -> JsNumber(BigDecimal(anyPageRoot getOrElse PageParts.BodyNr)),
      "allPosts" -> JsObject(allPostsJson),
      "topLevelCommentIdsSorted" -> JsArray(topLevelCommentIdsSorted),
      "horizontalLayout" -> JsBoolean(horizontalLayout),
      "is2dTreeDefault" -> JsBoolean(is2dTreeDefault),
      "socialLinksHtml" -> JsString(socialLinksHtml))

    val jsonString = jsonObj.toString()
    val version = CachedPageVersion(
      siteVersion = transaction.loadSiteVersion(),
      pageVersion = page.version,
      appVersion = Globals.applicationVersion,
      dataHash = hashSha1Base64UrlSafe(jsonString))

    (jsonString, version)
  }


  /** Returns (any-forum-id, json-for-ancestor-forum-and-categories-forum-first).
    */
  def makeForumIdAndAncestorsJson(pageMeta: PageMeta, dao: SiteDao)
        : (Option[PageId], Seq[JsObject]) = {
    val categoryId = pageMeta.categoryId getOrElse {
      return (None, Nil)
    }
    val categoriesRootFirst = dao.loadCategoriesRootLast(categoryId).reverse
    if (categoriesRootFirst.isEmpty) {
      return (None, Nil)
    }
    val forumPageId = categoriesRootFirst.head.sectionPageId
    dao.lookupPagePath(forumPageId) match {
      case None => (None, Nil)
      case Some(forumPath) =>
        val jsonRootFirst = categoriesRootFirst.map(makeForumOrCategoryJson(forumPath, _))
        (Some(forumPageId), jsonRootFirst)
    }
  }


  /** Returns the URL path, category id and title for a forum or category.
    */
  private def makeForumOrCategoryJson(forumPath: PagePath, category: Category): JsObject = {
    val (name, path) =
      if (category.isRoot)
        ("Forum", s"${forumPath.value}#/latest/")   // [i18n]
      else
        (category.name, s"${forumPath.value}#/latest/${category.slug}")
    Json.obj(
      "categoryId" -> category.id,
      "title" -> name,
      "path" -> path,
      "hideInForum" -> category.hideInForum)
  }


  def postToJson2(postNr: PostNr, pageId: PageId, dao: SiteDao, includeUnapproved: Boolean = false)
        : JsObject = {
    dao.readOnlyTransaction { transaction =>
      // COULD optimize: don't load the whole page, load only postNr and the author and last editor.
      val page = PageDao(pageId, transaction)
      postToJsonImpl(page.parts.thePost(postNr), page, transaction.currentTime,
        includeUnapproved = includeUnapproved)
    }
  }


  /** Private, so it cannot be called outside a transaction.
    */
  private def postToJsonImpl(post: Post, page: Page, currentTime: ju.Date,
        includeUnapproved: Boolean = false): JsObject = {
    val people = page.parts

    val (anySanitizedHtml: Option[String], isApproved: Boolean) =
      if (includeUnapproved)
        (Some(post.currentHtmlSanitized(ReactRenderer, page.role)),
          post.isCurrentVersionApproved)
      else
        (post.approvedHtmlSanitized, post.approvedAt.isDefined)

    val depth = page.parts.depthOf(post.nr)

    // Find out if we should summarize post, or squash it and its subsequent siblings.
    // This is simple but a bit too stupid? COULD come up with a better algorithm (better
    // in the sense that it better avoids summarizing or squashing interesting stuff).
    // (Note: We'll probably have to do this server side in order to do it well, because
    // only server side all information is available, e.g. how trustworthy certain users
    // are or if they are trolls. Cannot include that in JSON sent to the browser, privacy issue.)
    val (summarize, jsSummary, squash) =
      if (page.parts.numRepliesVisible < SummarizeNumRepliesVisibleLimit) {
        (false, JsNull, false)
      }
      else {
        val (siblingIndex, hasNonDeletedSuccessorSiblingTrees) = page.parts.siblingIndexOf(post)
        val squashTime = siblingIndex > SquashSiblingIndexLimit / math.max(depth, 1)
        // Don't squash a single comment with no replies – summarize it instead.
        val squash = squashTime && (hasNonDeletedSuccessorSiblingTrees ||
          page.parts.hasNonDeletedSuccessor(post.nr))
        var summarize = !squash && (squashTime || siblingIndex > SummarizeSiblingIndexLimit ||
          depth >= SummarizeAllDepthLimit)
        val summary: JsValue =
          if (summarize) post.approvedHtmlSanitized match {
            case None =>
              JsString("(Not approved [DwE4FGEU7])")
            case Some(html) =>
              // Include only the first paragraph or header.
              val ToTextResult(text, isSingleParagraph) =
                htmlToTextWithNewlines(html, firstLineOnly = true)
              if (isSingleParagraph && text.length <= SummarizePostLengthLimit) {
                // There's just one short paragraph. Don't summarize.
                summarize = false
                JsNull
              }
              else {
                JsString(text.take(PostSummaryLength))
              }
          }
          else JsNull
        (summarize, summary, squash)
      }

    val childrenSorted = page.parts.childrenBestFirstOf(post.nr)
    val author = post.createdByUser(people)
    val postType: Option[Int] = if (post.tyype == PostType.Normal) None else Some(post.tyype.toInt)

    // For now, ignore ninja edits of the very first revision, because otherwise if
    // clicking to view the edit history, it'll be empty.
    val lastApprovedEditAtNoNinja =
      if (post.approvedRevisionNr == Some(FirstRevisionNr)) None
      else post.lastApprovedEditAt

    var fields = Vector(
      "uniqueId" -> JsNumber(post.uniqueId),
      "postId" -> JsNumber(post.nr),
      "parentId" -> post.parentNr.map(JsNumber(_)).getOrElse(JsNull),
      "multireplyPostIds" -> JsArray(post.multireplyPostNrs.toSeq.map(JsNumber(_))),
      "postType" -> JsNumberOrNull(postType),
      "authorId" -> JsString(post.createdById.toString),  // COULD remove, but be careful when converting to int client side
      "authorIdInt" -> JsNumber(post.createdById),  // Rename to authorId when it's been converted to int (the line above)
      "authorFullName" -> JsString(author.displayName),
      "authorUsername" -> JsStringOrNull(author.username),
      "authorAvatarUrl" -> JsUploadUrlOrNull(author.smallAvatar),
      "createdAt" -> date(post.createdAt),
      "lastApprovedEditAt" -> dateOrNull(lastApprovedEditAtNoNinja),
      "numEditors" -> JsNumber(post.numDistinctEditors),
      "numLikeVotes" -> JsNumber(post.numLikeVotes),
      "numWrongVotes" -> JsNumber(post.numWrongVotes),
      "numBuryVotes" -> JsNumber(post.numBuryVotes),
      "numUnwantedVotes" -> JsNumber(post.numUnwantedVotes),
      "numPendingEditSuggestions" -> JsNumber(post.numPendingEditSuggestions),
      "summarize" -> JsBoolean(summarize),
      "summary" -> jsSummary,
      "squash" -> JsBoolean(squash),
      "isTreeDeleted" -> JsBoolean(post.deletedStatus.isTreeDeleted),
      "isPostDeleted" -> JsBoolean(post.deletedStatus.isPostDeleted),
      "isTreeCollapsed" -> (
        if (summarize) JsString("Truncated")
        else JsBoolean(!squash && post.collapsedStatus.isTreeCollapsed)),
      "isPostCollapsed" -> JsBoolean(!summarize && !squash && post.collapsedStatus.isPostCollapsed),
      "isTreeClosed" -> JsBoolean(post.closedStatus.isTreeClosed),
      "isApproved" -> JsBoolean(isApproved),
      "pinnedPosition" -> post.pinnedPosition.map(JsNumber(_)).getOrElse(JsNull),
      "likeScore" -> JsNumber(decimal(post.likeScore)),
      "childIdsSorted" -> JsArray(childrenSorted.map(reply => JsNumber(reply.nr))),
      "sanitizedHtml" -> JsStringOrNull(anySanitizedHtml))

    if (post.isHidden) fields :+= "isPostHidden" -> JsTrue
    if (author.email.isEmpty) fields :+= "authorEmailUnknown" -> JsTrue

    if (author.isSuspendedAt(currentTime)) {
      author.suspendedTill match {
        case None => fields :+= "authorSuspendedTill" -> JsString("Forever")
        case Some(date) => fields :+= "authorSuspendedTill" -> JsNumber(date.getTime)
      }
    }

    JsObject(fields)
  }


  def postRevisionToJson(revision: PostRevision, usersById: Map[UserId, User],
        maySeeHidden: Boolean): JsValue = {
    val source =
      if (revision.isHidden && !maySeeHidden) JsNull
      else JsString(revision.fullSource.getOrDie("DwE7GUY2"))
    val composer = usersById.get(revision.composedById)
    val approver = revision.approvedById.flatMap(usersById.get)
    val hider = revision.hiddenById.flatMap(usersById.get)
    Json.obj(
      "revisionNr" -> revision.revisionNr,
      "previousNr" -> JsNumberOrNull(revision.previousNr),
      "fullSource" -> source,
      "composedAtMs" -> revision.composedAt,
      "composedBy" -> JsUserOrNull(composer),
      "approvedAtMs" -> JsDateMsOrNull(revision.approvedAt),
      "approvedBy" -> JsUserOrNull(approver),
      "hiddenAtMs" -> JsDateMsOrNull(revision.hiddenAt),
      "hiddenBy" -> JsUserOrNull(hider))
  }


  /** Creates a dummy root post, needed when rendering React elements. */
  def embeddedCommentsDummyRootPost(topLevelComments: immutable.Seq[Post]) = Json.obj(
    "postId" -> JsNumber(PageParts.BodyNr),
    "childIdsSorted" ->
      JsArray(Post.sortPostsBestFirst(topLevelComments).map(reply => JsNumber(reply.nr))))


  val NoUserSpecificData = Json.obj(
    "rolePageSettings" -> JsObject(Nil),
    "notifications" -> JsArray(),
    "votes" -> JsObject(Nil),
    "unapprovedPosts" -> JsObject(Nil),
    "postIdsAutoReadLongAgo" -> JsArray(Nil),
    "postIdsAutoReadNow" -> JsArray(Nil),
    "marksByPostId" -> JsObject(Nil))


  def userDataJson(pageRequest: PageRequest[_]): Option[JsObject] = {
    val user = pageRequest.user getOrElse {
      return None
    }
    pageRequest.dao.readOnlyTransaction { transaction =>
      Some(userDataJsonImpl(user, pageRequest.pageId, transaction))
    }
  }


  def userNoPageToJson(request: DebikiRequest[_]): JsValue = {
    val user = request.user getOrElse {
      return JsNull
    }
    request.dao.readOnlyTransaction(userDataJsonImpl(user, anyPageId = None, _))
  }


  private def userDataJsonImpl(user: User, anyPageId: Option[PageId],
        transaction: SiteTransaction): JsObject = {

    val reviewTasksAndCounts =
      if (user.isStaff) transaction.loadReviewTaskCounts(user.isAdmin)
      else ReviewTaskCounts(0, 0)

    val notfsAndCounts = loadNotifications(user.id, transaction, unseenFirst = true, limit = 30)

    val (rolePageSettings, anyVotes, anyUnapprovedPosts) =
      anyPageId map { pageId =>
        val rolePageSettings = user.anyRoleId.map({ roleId =>
          val anySettings = transaction.loadRolePageSettings(roleId = roleId, pageId = pageId)
          rolePageSettingsToJson(anySettings getOrElse RolePageSettings.Default)
        }) getOrElse JsNull
        (rolePageSettings,
          votesJson(user.id, pageId, transaction),
          unapprovedPostsJson(user.id, pageId, transaction))
      } getOrElse (JsNull, JsNull, JsNull)

    Json.obj(
      "userId" -> JsNumber(user.id),
      "username" -> JsStringOrNull(user.username),
      "fullName" -> JsString(user.displayName),
      "isLoggedIn" -> JsBoolean(true),
      "isAdmin" -> JsBoolean(user.isAdmin),
      "isModerator" -> JsBoolean(user.isModerator),
      "avatarUrl" -> JsUploadUrlOrNull(user.smallAvatar),
      "isEmailKnown" -> JsBoolean(user.email.nonEmpty),
      "isAuthenticated" -> JsBoolean(user.isAuthenticated),
      "rolePageSettings" -> rolePageSettings,

      "numUrgentReviewTasks" -> reviewTasksAndCounts.numUrgent,
      "numOtherReviewTasks" -> reviewTasksAndCounts.numOther,

      "numTalkToMeNotfs" -> notfsAndCounts.numTalkToMe,
      "numTalkToOthersNotfs" -> notfsAndCounts.numTalkToOthers,
      "numOtherNotfs" -> notfsAndCounts.numOther,
      "thereAreMoreUnseenNotfs" -> notfsAndCounts.thereAreMoreUnseen,
      "notifications" -> notfsAndCounts.notfsJson,

      "votes" -> anyVotes,
      "unapprovedPosts" -> anyUnapprovedPosts,
      "postIdsAutoReadLongAgo" -> JsArray(Nil),
      "postIdsAutoReadNow" -> JsArray(Nil),
      "marksByPostId" -> JsObject(Nil))
  }


  private def rolePageSettingsToJson(settings: RolePageSettings): JsObject = {
    Json.obj(
      "notfLevel" -> JsString(settings.notfLevel.toString))
  }


  case class NotfsAndCounts(
    numTalkToMe: Int,
    numTalkToOthers: Int,
    numOther: Int,
    thereAreMoreUnseen: Boolean,
    notfsJson: JsArray)


  def loadNotifications(userId: UserId, transaction: SiteTransaction, unseenFirst: Boolean,
        limit: Int, upToWhen: Option[ju.Date] = None): NotfsAndCounts = {
    val notfs = transaction.loadNotificationsForRole(userId, limit, unseenFirst, upToWhen)
    notificationsToJson(notfs, transaction)
  }


  def notificationsToJson(notfs: Seq[Notification], transaction: SiteTransaction)
        : NotfsAndCounts = {
    val pageIds = ArrayBuffer[PageId]()
    val userIds = ArrayBuffer[UserId]()
    var numTalkToMe = 0
    var numTalkToOthers = 0
    var numOther = 0

    notfs.foreach {
      case notf: Notification.NewPost =>
        pageIds.append(notf.pageId)
        userIds.append(notf.byUserId)
        import NotificationType._
        notf.tyype match {
          case DirectReply | Mention | Message =>
            numTalkToMe += 1
          case NewPost =>
            numTalkToOthers += 1
        }
      case _ => ()
    }

    // Unseen notfs are sorted first, so if the last one is unseen, there might be more unseen.
    val thereAreMoreUnseen = notfs.lastOption.exists(_.seenAt.isEmpty)

    val pageTitlesById = transaction.loadTitlesPreferApproved(pageIds)
    val usersById = transaction.loadUsersAsMap(userIds)

    NotfsAndCounts(
      numTalkToMe = numTalkToMe,
      numTalkToOthers = numTalkToOthers,
      numOther = numOther,
      thereAreMoreUnseen = thereAreMoreUnseen,
      notfsJson = JsArray(notfs.map(makeNotificationsJson(_, pageTitlesById, usersById))))
  }


  private def makeNotificationsJson(notf: Notification, pageTitlesById: Map[PageId, String],
        usersById: Map[UserId, User]): JsObject = {
    notf match {
      case notf: Notification.NewPost =>
        Json.obj(
          "id" -> notf.id,
          "type" -> notf.tyype.toInt,
          "createdAtMs" -> notf.createdAt.getTime,
          "pageId" -> notf.pageId,
          "pageTitle" -> JsStringOrNull(pageTitlesById.get(notf.pageId)),
          "postNr" -> notf.postNr,
          "byUser" -> JsUserOrNull(usersById.get(notf.byUserId)),
          "seen" -> notf.seenAt.nonEmpty)
    }
  }


  private def votesJson(userId: UserId, pageId: PageId, transaction: SiteTransaction): JsObject = {
    val actions = transaction.loadActionsByUserOnPage(userId, pageId)
    val votes = actions.filter(_.isInstanceOf[PostVote]).asInstanceOf[immutable.Seq[PostVote]]
    val userVotesMap = UserPostVotes.makeMap(votes)
    val votesByPostId = userVotesMap map { case (postNr, votes) =>
      var voteStrs = Vector[String]()
      if (votes.votedLike) voteStrs = voteStrs :+ "VoteLike"
      if (votes.votedWrong) voteStrs = voteStrs :+ "VoteWrong"
      if (votes.votedBury) voteStrs = voteStrs :+ "VoteBury"
      if (votes.votedUnwanted) voteStrs = voteStrs :+ "VoteUnwanted"
      postNr.toString -> Json.toJson(voteStrs)
    }
    JsObject(votesByPostId.toSeq)
  }


  private def unapprovedPostsJson(userId: UserId, pageId: PageId, transaction: SiteTransaction)
        : JsObject = {
    // I'm rewriting/refactoring and right now all posts are approved directly, so for now:
    JsObject(Nil)
    /* Previously:
    val relevantPosts =
      if (request.theUser.isAdmin) request.thePageParts.getAllPosts
      else request.thePageParts.postsByUser(request.theUser.id)

    val unapprovedPosts = relevantPosts filter { post =>
      !post.currentVersionApproved
    }

    val json = JsObject(unapprovedPosts.map { post =>
      post.id.toString -> postToJson(post, includeUnapproved = true)
    })

    json
    */
  }


  private def categoriesJson(page: PageDao, includeHiddenInForum: Boolean, dao: SiteDao)
        : JsArray = {
    if (page.role != PageRole.Forum)
      return JsArray(Nil)

    categoriesJson(page.id, includeHiddenInForum, dao)
  }


  def categoriesJson(sectionId: PageId, includeHiddenInForum: Boolean, dao: SiteDao): JsArray = {
    val categories: Seq[Category] = dao.listSectionCategories(sectionId, includeHiddenInForum)
    val pageStuffById = dao.loadPageStuff(categories.map(_.sectionPageId))
    val categoriesJson = JsArray(categories.filterNot(_.isRoot) map { category =>
      categoryJson(category)
    })
    categoriesJson
  }


  def categoryJson(category: Category, recentTopicsJson: Seq[JsObject] = null) = {
    var json = Json.obj(
      "id" -> category.id,
      "name" -> category.name,
      "slug" -> category.slug,
      "newTopicTypes" -> JsArray(category.newTopicTypes.map(t => JsNumber(t.toInt))),
      "hideInForum" -> JsBoolean(category.hideInForum),
      "position" -> category.position,
      "description" -> JsStringOrNull(category.description))
    if (recentTopicsJson ne null) {
      json += "recentTopics" -> JsArray(recentTopicsJson)
    }
    if (category.isTheUncategorizedCategory) {
      json += "isTheUncategorizedCategory" -> JsBoolean(true)
    }
    json
  }


  def reviewStufToJson(stuff: ReviewStuff, usersById: Map[UserId, User]): JsValue = {
    val anyPost = stuff.post match {
      case None => JsNull
      case Some(post) =>
        Json.obj(
          "pageId" -> post.pageId,
          "nr" -> post.nr,
          "uniqueId" -> post.uniqueId,
          "createdBy" -> JsUserOrNull(usersById.get(post.createdById)),
          "currentSource" -> post.currentSource,
          "currRevNr" -> post.currentRevisionNr,
          "currRevComposedBy" -> JsUserOrNull(usersById.get(post.currentRevisionById)),
          "approvedSource" -> JsStringOrNull(post.approvedSource),
          "approvedHtmlSanitized" -> JsStringOrNull(post.approvedHtmlSanitized),
          "approvedRevNr" -> JsNumberOrNull(post.approvedRevisionNr),
          "approvedRevComposedById" -> JsNull, // post.lastApprovedEditById ? ... hmm, see below
          "approvedRevApprovedById" -> JsNull) // -> post.aprvdRevAprvdById?? ... hmm no,
                                                // better: post.lastApporvedRevision.approvedById
    }
    Json.obj(
      "id" -> stuff.id,
      "reasonsLong" -> ReviewReason.toLong(stuff.reasons),
      "createdAtMs" -> stuff.createdAt.getTime,
      "moreReasonsAtMs" -> JsDateMsOrNull(stuff.moreReasonsAt),
      "completedAtMs" -> JsDateMsOrNull(stuff.completedAt),
      "completedBy" -> JsUserOrNull(stuff.completedBy),
      "invalidatedAt" -> JsDateMsOrNull(stuff.invalidatedAt),
      "resolution" -> JsNumberOrNull(stuff.resolution),
      "user" -> JsUserOrNull(stuff.user),
      "pageId" -> JsStringOrNull(stuff.pageId),
      "pageTitle" -> JsStringOrNull(stuff.pageTitle),
      "post" -> anyPost)
  }


  case class ToTextResult(text: String, isSingleParagraph: Boolean)


  def htmlToTextWithNewlines(htmlText: String, firstLineOnly: Boolean = false): ToTextResult = {
    // This includes no newlines: Jsoup.parse(htmlText).body.text
    // Instead we'll have to traverse all nodes. There are some alternative approaches
    // at StackOverflow but I think this is the only way to do it properly.
    // This implementation is based on how above `.text` works)
    import org.jsoup.Jsoup
    import org.jsoup.nodes.{Element, TextNode, Node}
    import org.jsoup.select.{NodeTraversor, NodeVisitor}
    import scala.util.control.ControlThrowable

    val result = new StringBuilder
    var numParagraphBlocks = 0
    var numOtherBlocks = 0
    def isInFirstParagraph = numParagraphBlocks == 0 && numOtherBlocks == 0
    def canStillBeSingleParagraph = numOtherBlocks == 0 && numParagraphBlocks <= 1

    val nodeTraversor = new NodeTraversor(new NodeVisitor() {
      override def head(node: Node, depth: Int) {
        node match {
          case textNode: TextNode =>
            if (!firstLineOnly || isInFirstParagraph) {
              result.append(textNode.getWholeText.trim)
            }
          case _ => ()
        }
      }
      override def tail(node: Node, depth: Int) {
        node match {
          case element: Element if result.nonEmpty =>
            val tagName = element.tag.getName
            if (tagName == "body")
              return
            if (element.isBlock) {
              // Consider a <br>, not just </p>, the end of a paragraph.
              if (tagName == "p" || tagName == "br")
                numParagraphBlocks += 1
              else
                numOtherBlocks += 1
            }
            if (element.isBlock || tagName == "br") {
              if (firstLineOnly) {
                // Don't break traversal before we know if there's at most one paragraph.
                if (!canStillBeSingleParagraph)
                  throw new ControlThrowable {}
              }
              else {
                result.append("\n")
              }
            }
          case _ => ()
        }
      }
    })

    try { nodeTraversor.traverse(Jsoup.parse(htmlText).body) }
    catch {
      case _: ControlThrowable => ()
    }
    ToTextResult(text = result.toString().trim, isSingleParagraph = canStillBeSingleParagraph)
  }


  def htmlToExcerpt(htmlText: String, length: Int): String = {
    val ToTextResult(text, _) = htmlToTextWithNewlines(htmlText, firstLineOnly = true)
    var excerpt =
      if (text.length <= length + 3) text
      else text.take(length) + "..."
    var lastChar = 'x'
    excerpt = excerpt takeWhile { ch =>
      val newParagraph = ch == '\n' && lastChar == '\n'
      lastChar = ch
      !newParagraph
    }
    excerpt
  }


  def JsUserOrNull(user: Option[User]): JsValue =
    user.map(JsUser).getOrElse(JsNull)

  def JsUser(user: User): JsObject = {
    var json = Json.obj(
      "id" -> JsNumber(user.id),
      "username" -> JsStringOrNull(user.username),
      "fullName" -> JsString(user.displayName))
    user.tinyAvatar foreach { uploadRef =>
      json += "avatarUrl" -> JsString(uploadRef.url)
    }
    if (user.isGuest) {
      json += "isGuest" -> JsTrue
    }
    else {
      require(user.isAuthenticated, "EdE8GPY4")
      json += "isAuthenticated" -> JsTrue  // COULD remove this, client side, use !isGuest instead
    }
    if (user.email.isEmpty) {
      json += "isEmailUnknown" -> JsTrue
    }
    if (user.isAdmin) {
      json += "isAdmin" -> JsTrue
    }
    if (user.isModerator) {
      json += "isModerator" -> JsTrue
    }
    json
  }

  def JsUploadUrlOrNull(uploadRef: Option[UploadRef]): JsValue =
    uploadRef.map(ref => JsString(ref.url)) getOrElse JsNull

  def JsStringOrNull(value: Option[String]) =
    value.map(JsString).getOrElse(JsNull)

  def JsBooleanOrNull(value: Option[Boolean]) =
    value.map(JsBoolean).getOrElse(JsNull)

  def JsNumberOrNull(value: Option[Int]) =
    value.map(JsNumber(_)).getOrElse(JsNull)

  def JsLongOrNull(value: Option[Long]) =
    value.map(JsNumber(_)).getOrElse(JsNull)

  def JsDateMs(value: ju.Date) =
    JsNumber(value.getTime)

  def JsDateMsOrNull(value: Option[ju.Date]) =
    value.map(JsDateMs).getOrElse(JsNull)

  def DateEpochOrNull(value: Option[ju.Date]) =
    value.map(date => JsNumber(date.getTime)).getOrElse(JsNull)

  def date(value: ju.Date) =
    JsString(toIso8601NoSecondsNoT(value))

  def dateOrNull(value: Option[ju.Date]) = value match {
    case Some(v) => date(v)
    case None => JsNull
  }

}

