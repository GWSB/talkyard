/**
 * Copyright (c) 2014-2015 Kaj Magnus Lindberg
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
import com.debiki.core.User.SystemUserId
import controllers.EditController
import debiki._
import debiki.DebikiHttp._
import io.efdi.server.notf.NotificationGenerator
import io.efdi.server.pubsub
import java.{util => ju}
import play.{api => p}
import scala.collection.{mutable, immutable}
import PostsDao._


/** Loads and saves pages and page parts (e.g. posts and patches).
  *
  * (There's also a class PageDao (with no 's' in the name) that focuses on
  * one specific single page.)
  *
  * SHOULD make the full text search indexer work again
  */
trait PostsDao {
  self: SiteDao =>


  def insertReply(textAndHtml: TextAndHtml, pageId: PageId, replyToPostNrs: Set[PostNr],
        postType: PostType, authorId: UserId, browserIdData: BrowserIdData): PostNr = {
    if (textAndHtml.safeHtml.trim.isEmpty)
      throwBadReq("DwE6KEF2", "Empty reply")

    // Later: create 1 post of type multireply, with no text, per replied-to post,
    // and one post for the actual text and resulting location of this post.
    // Disabling for now, so I won't have to parse dw2_posts.multireply and convert
    // to many rows.
    if (replyToPostNrs.size > 1)
      throwNotImplemented("EsE7GKX2", o"""Please reply to one single person only.
        Multireplies temporarily disabled, sorry""")

    val (postNr, pageMemberIds, notifications) = readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val uniqueId = transaction.nextPostId()
      val postNr = page.parts.highestReplyNr.map(_ + 1) getOrElse PageParts.FirstReplyNr
      val commonAncestorId = page.parts.findCommonAncestorNr(replyToPostNrs.toSeq)
      val anyParent =
        if (commonAncestorId == PageParts.NoNr) {
          // Flat chat comments might not reply to anyone in particular.
          // On embedded comments pages, there's no Original Post, so top level comments
          // have no parent post.
          if (postType != PostType.Flat && page.role != PageRole.EmbeddedComments)
            throwBadReq("DwE2CGW7", "Non-flat non-embedded comment with no parent post id")
          else
            None
        }
        else {
          val anyParent = page.parts.post(commonAncestorId)
          if (anyParent.isEmpty) {
            throwBadReq("DwEe8HD36", o"""Cannot reply to common ancestor post '$commonAncestorId';
                it does not exist""")
          }
          anyParent
        }
      if (anyParent.exists(_.deletedStatus.isDeleted))
        throwForbidden(
          "The parent post has been deleted; cannot reply to a deleted post", "DwE5KDE7")

      // SHOULD authorize. For now, no restrictions.
      val isApproved = true // for now
      val author = transaction.loadUser(authorId) getOrElse throwNotFound("DwE404UF3", "Bad user")
      val approverId =
        if (author.isStaff) {
          author.id
        }
        else {
          SystemUserId
        }

      val newPost = Post.create(
        siteId = siteId,
        uniqueId = uniqueId,
        pageId = pageId,
        postNr = postNr,
        parent = anyParent,
        multireplyPostNrs = (replyToPostNrs.size > 1) ? replyToPostNrs | Set.empty,
        postType = postType,
        createdAt = transaction.currentTime,
        createdById = authorId,
        source = textAndHtml.text,
        htmlSanitized = textAndHtml.safeHtml,
        approvedById = Some(approverId))

      val numNewOrigPostReplies = (isApproved && newPost.isOrigPostReply) ? 1 | 0
      val newFrequentPosterIds: Seq[UserId] =
        if (isApproved)
          PageParts.findFrequentPosters(newPost +: page.parts.allPosts,
            ignoreIds = Set(page.meta.authorId, authorId))
        else
          page.meta.frequentPosterIds

      val oldMeta = page.meta
      val newMeta = oldMeta.copy(
        bumpedAt = page.isClosed ? oldMeta.bumpedAt | Some(transaction.currentTime),
        lastReplyAt = isApproved ? Option(transaction.currentTime) | oldMeta.lastReplyAt,
        lastReplyById = isApproved ? Option(authorId) | oldMeta.lastReplyById,
        frequentPosterIds = newFrequentPosterIds,
        numRepliesVisible = page.parts.numRepliesVisible + (isApproved ? 1 | 0),
        numRepliesTotal = page.parts.numRepliesTotal + 1,
        numOrigPostRepliesVisible = page.parts.numOrigPostRepliesVisible + numNewOrigPostReplies,
        version = oldMeta.version + 1)

      val uploadRefs = UploadsDao.findUploadRefsInPost(newPost)

      val reviewTask: Option[ReviewTask] = if (author.isStaff) None else {
        val reviewTaskReasons = mutable.ArrayBuffer[ReviewReason]()
        val recentPostsByAuthor = transaction.loadPostsBy(authorId, includeTitles = false,
          limit = Settings.NumFirstUserPostsToReview)
        if (recentPostsByAuthor.length < Settings.NumFirstUserPostsToReview) {
          reviewTaskReasons.append(ReviewReason.IsByNewUser, ReviewReason.NewPost)
        }
        if (page.isClosed) {
          // The topic won't be bumped, so no one might see this post, so staff should review it.
          // Could skip this if the user is trusted.
          reviewTaskReasons.append(ReviewReason.NoBumpPost)
        }
        if (reviewTaskReasons.isEmpty) None
        else Some(ReviewTask(
          id = transaction.nextReviewTaskId(),
          reasons = reviewTaskReasons.to[immutable.Seq],
          causedById = authorId,
          createdAt = transaction.currentTime,
          createdAtRevNr = Some(newPost.currentRevisionNr),
          postId = Some(newPost.uniqueId),
          postNr = Some(newPost.nr)))
      }

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.NewPost,
        doerId = authorId,
        doneAt = transaction.currentTime,
        browserIdData = browserIdData,
        pageId = Some(pageId),
        uniquePostId = Some(newPost.uniqueId),
        postNr = Some(newPost.nr),
        targetUniquePostId = anyParent.map(_.uniqueId),
        targetPostNr = anyParent.map(_.nr),
        targetUserId = anyParent.map(_.createdById))

      transaction.insertPost(newPost)
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = isApproved)
      uploadRefs foreach { uploadRef =>
        transaction.insertUploadedFileReference(newPost.uniqueId, uploadRef, authorId)
      }
      insertAuditLogEntry(auditLogEntry, transaction)
      reviewTask.foreach(transaction.upsertReviewTask)

      // generate json? load all page members?
      // send the post + json back to the caller?
      // & publish [pubsub]
      val pageMemberIds = transaction.loadMessageMembers(pageId)

      val notifications = NotificationGenerator(transaction).generateForNewPost(page, newPost)
      transaction.saveDeleteNotifications(notifications)

      (postNr, pageMemberIds, notifications)
    }

    pubSub.publish(
      // for now, send null:
      pubsub.NewPostMessage(siteId, pageMemberIds, pageId, play.api.libs.json.JsNull, notifications))

    refreshPageInAnyCache(pageId)
    postNr
  }


  /** Edits the post, if authorized to edit it.
    */
  def editPostIfAuth(pageId: PageId, postNr: PostNr, editorId: UserId, browserIdData: BrowserIdData,
        newTextAndHtml: TextAndHtml) {

    if (newTextAndHtml.safeHtml.trim.isEmpty)
      throwBadReq("DwE4KEL7", EditController.EmptyPostErrorMessage)

    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val postToEdit = page.parts.post(postNr) getOrElse {
        page.meta // this throws page-not-fount if the page doesn't exist
        throwNotFound("DwE404GKF2", s"Post not found, id: '$postNr'")
      }

      if (postToEdit.isDeleted)
        throwForbidden("DwE6PK2", "The post has been deleted")

      if (postToEdit.currentSource == newTextAndHtml.text)
        return

      val editor = transaction.loadUser(editorId).getOrElse(
        throwNotFound("DwE30HY21", s"User not found, id: '$editorId'"))

      // For now: (add back edit suggestions later. And should perhaps use PermsOnPage.)
      if (!userMayEdit(editor, postToEdit))
        throwForbidden("DwE8KF32", "You may not edit that post")

      val approverId = if (editor.isStaff) editor.id else SystemUserId

      // COULD don't allow sbd else to edit until 3 mins after last edit by sbd else?
      // so won't create too many revs quickly because 2 edits.
      BUG // COULD compare version number: kills the lost update bug.

      val isInNinjaEditWindow = {
        val ninjaWindowMs = ninjaEditWindowMsFor(page.role)
        val ninjaEditEndMs = postToEdit.currentRevStaredAt.getTime + ninjaWindowMs
        transaction.currentTime.getTime < ninjaEditEndMs
      }

      // If we've saved an old revision already, and 1) there hasn't been any more discussion
      // in this sub thread since the current revision was started, and 2) the current revision
      // hasn't been flagged, — then don't save a new revision. It's rather uninteresting
      // to track changes, when no discussion is happening.
      // (We avoid saving unneeded revisions, to save disk.)
      val anyLastRevision = loadLastRevisionWithSource(postToEdit.uniqueId, transaction)
      def oldRevisionSavedAndNothingHappened = anyLastRevision match {
        case None => false
        case Some(_) =>
          // COULD: instead of comparing timestamps, flags and replies could explicitly clarify
          // which revision of postToEdit they concern.
          val currentRevStartMs = postToEdit.currentRevStaredAt.getTime
          val flags = transaction.loadFlagsFor(immutable.Seq(PagePostNr(pageId, postNr)))
          val anyNewFlag = flags.exists(_.flaggedAt.getTime > currentRevStartMs)
          val successors = page.parts.successorsOf(postNr)
          val anyNewComment = successors.exists(_.createdAt.getTime > currentRevStartMs)
        !anyNewComment && !anyNewFlag
      }

      val isNinjaEdit = {
        val sameAuthor = postToEdit.currentRevisionById == editorId
        val ninjaHardEndMs = postToEdit.currentRevStaredAt.getTime + HardMaxNinjaEditWindowMs
        val isInHardWindow = transaction.currentTime.getTime < ninjaHardEndMs
        sameAuthor && isInHardWindow && (isInNinjaEditWindow || oldRevisionSavedAndNothingHappened)
      }

      val (newRevision: Option[PostRevision], newStartedAt, newRevisionNr, newPrevRevNr) =
        if (isNinjaEdit) {
          (None, postToEdit.currentRevStaredAt, postToEdit.currentRevisionNr,
            postToEdit.previousRevisionNr)
        }
        else {
          val revision = PostRevision.createFor(postToEdit, previousRevision = anyLastRevision)
          (Some(revision), transaction.currentTime, postToEdit.currentRevisionNr + 1,
            Some(postToEdit.currentRevisionNr))
        }

      // COULD send current version from browser to server, reject edits if != oldPost.currentVersion
      // to stop the lost update problem.

      // Later, if post not approved directly: currentSourcePatch = makePatch(from, to)

      var editedPost = postToEdit.copy(
        currentRevStaredAt = newStartedAt,
        currentRevLastEditedAt = Some(transaction.currentTime),
        currentRevisionById = editorId,
        currentSourcePatch = None,
        currentRevisionNr = newRevisionNr,
        previousRevisionNr = newPrevRevNr,
        lastApprovedEditAt = Some(transaction.currentTime),
        lastApprovedEditById = Some(editorId),
        approvedSource = Some(newTextAndHtml.text),
        approvedHtmlSanitized = Some(newTextAndHtml.safeHtml),
        approvedAt = Some(transaction.currentTime),
        approvedById = Some(approverId),
        approvedRevisionNr = Some(newRevisionNr))

      if (editorId != editedPost.createdById) {
        editedPost = editedPost.copy(numDistinctEditors = 2)  // for now
      }

      val anyEditedCategory =
        if (page.role != PageRole.AboutCategory || !editedPost.isOrigPost) {
          // Later: Go here also if new text not yet approved.
          None
        }
        else {
          if (newTextAndHtml.text == Category.UncategorizedDescription) {
            // We recognize Uncategorized categories via the magic text tested for above.
            throwForbidden("DwE4KEP8", "Forbidden magic text")
          }
          // COULD reuse the same transaction, when loading the category. Barely matters.
          val category = loadTheCategory(page.meta.categoryId getOrDie "DwE2PKF0")
          val newDescription = ReactJson.htmlToExcerpt(
            newTextAndHtml.safeHtml, Category.DescriptionExcerptLength)
          Some(category.copy(description = Some(newDescription)))
        }

      // Use findUploadRefsInPost (not ...InText) so we'll find refs both in the hereafter
      // 1) approved version of the post, and 2) the current possibly unapproved version.
      // Because if any of the approved or the current version links to an uploaded file,
      // we should keep the file.
      val currentUploadRefs = UploadsDao.findUploadRefsInPost(editedPost)
      val oldUploadRefs = transaction.loadUploadedFileReferences(postToEdit.uniqueId)
      val uploadRefsAdded = currentUploadRefs -- oldUploadRefs
      val uploadRefsRemoved = oldUploadRefs -- currentUploadRefs

      val postRecentlyCreated = transaction.currentTime.getTime - postToEdit.createdAt.getTime <=
          Settings.PostRecentlyCreatedLimitMs

      val reviewTask: Option[ReviewTask] =
        if (postRecentlyCreated || editor.isStaff) {
          // Need not review a recently created post: it's new and the edits likely
          // happened before other people read it, so they'll notice any weird things
          // later when they read it, and can flag it. This is not totally safe,
          // but better than forcing the staff to review all edits? (They'd just
          // get bored and stop reviewing.)
          // The way to do this in a really safe manner: Create a invisible inactive post-edited
          // review task, which gets activated & shown after x hours if too few people have read
          // the post. But if many has seen the post, the review task instead gets deleted.
          None
        }
        else {
          Some(makeReviewTask(editorId, editedPost, immutable.Seq(ReviewReason.LateEdit),
            transaction))
        }

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.EditPost,
        doerId = editorId,
        doneAt = transaction.currentTime,
        browserIdData = browserIdData,
        pageId = Some(pageId),
        uniquePostId = Some(postToEdit.uniqueId),
        postNr = Some(postNr),
        targetUserId = Some(postToEdit.createdById))

      transaction.updatePost(editedPost)
      newRevision.foreach(transaction.insertPostRevision)

      uploadRefsAdded foreach { hashPathSuffix =>
        transaction.insertUploadedFileReference(postToEdit.uniqueId, hashPathSuffix, editorId)
      }
      uploadRefsRemoved foreach { hashPathSuffix =>
        val gone = transaction.deleteUploadedFileReference(postToEdit.uniqueId, hashPathSuffix)
        if (!gone) {
          p.Logger.warn(o"""Didn't delete this uploaded file ref: $hashPathSuffix, post id:
            ${postToEdit.uniqueId} [DwE7UMF2]""")
        }
      }

      insertAuditLogEntry(auditLogEntry, transaction)
      anyEditedCategory.foreach(transaction.updateCategoryMarkSectionPageStale)
      reviewTask.foreach(transaction.upsertReviewTask)

      if (!postToEdit.isSomeVersionApproved && editedPost.isSomeVersionApproved) {
        unimplemented("Updating visible post counts when post approved via an edit", "DwE5WE28")
      }

      if (editedPost.isCurrentVersionApproved) {
        val notfs = NotificationGenerator(transaction).generateForEdits(postToEdit, editedPost)
        transaction.saveDeleteNotifications(notfs)
      }

      val oldMeta = page.meta
      var newMeta = oldMeta.copy(version = oldMeta.version + 1)
      var makesSectionPageHtmlStale = false
      // Bump the page, if the article / original post was edited.
      // (This is how Discourse works and people seems to like it. However,
      // COULD add a don't-bump option for minor edits.)
      if (postNr == PageParts.BodyNr && editedPost.isCurrentVersionApproved && !page.isClosed) {
        newMeta = newMeta.copy(bumpedAt = Some(transaction.currentTime))
        makesSectionPageHtmlStale = true
      }
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, makesSectionPageHtmlStale)
    }

    refreshPageInAnyCache(pageId)
  }


  def loadSomeRevisionsRecentFirst(postId: UniquePostId, revisionNr: Int, atLeast: Int)
        : (Seq[PostRevision], Map[UserId, User]) = {
    val revisionsRecentFirst = mutable.ArrayStack[PostRevision]()
    var usersById: Map[UserId, User] = null
    readOnlyTransaction { transaction =>
      loadSomeRevisionsWithSourceImpl(postId, revisionNr, revisionsRecentFirst,
        atLeast, transaction)
      if (revisionNr == PostRevision.LastRevisionMagicNr) {
        val postNow = transaction.loadThePost(postId)
        val currentRevision = PostRevision.createFor(postNow, revisionsRecentFirst.headOption)
          .copy(fullSource = Some(postNow.currentSource))
        revisionsRecentFirst.push(currentRevision)
      }
      val userIds = mutable.HashSet[UserId]()
      revisionsRecentFirst foreach { revision =>
        userIds add revision.composedById
        revision.approvedById foreach userIds.add
        revision.hiddenById foreach userIds.add
      }
      usersById = transaction.loadUsersAsMap(userIds)
    }
    (revisionsRecentFirst.toSeq, usersById)
  }


  private def loadLastRevisionWithSource(postId: UniquePostId, transaction: SiteTransaction)
        : Option[PostRevision] = {
    val revisionsRecentFirst = mutable.ArrayStack[PostRevision]()
    loadSomeRevisionsWithSourceImpl(postId, PostRevision.LastRevisionMagicNr,
      revisionsRecentFirst, atLeast = 1, transaction)
    revisionsRecentFirst.headOption
  }


  private def loadSomeRevisionsWithSourceImpl(postId: UniquePostId, revisionNr: Int,
        revisionsRecentFirst: mutable.ArrayStack[PostRevision], atLeast: Int,
        transaction: SiteTransaction) {
    transaction.loadPostRevision(postId, revisionNr) foreach { revision =>
      loadRevisionsFillInSource(revision, revisionsRecentFirst, atLeast, transaction)
    }
  }


  private def loadRevisionsFillInSource(revision: PostRevision,
        revisionsRecentFirstWithSource: mutable.ArrayStack[PostRevision],
        atLeast: Int, transaction: SiteTransaction) {
    if (revision.fullSource.isDefined && (atLeast <= 1 || revision.previousNr.isEmpty)) {
      revisionsRecentFirstWithSource.push(revision)
      return
    }

    val previousRevisionNr = revision.previousNr.getOrDie(
      "DwE08SKF3", o"""In site $siteId, post ${revision.postId} revision ${revision.revisionNr}
          has neither full source nor any previous revision nr""")

    val previousRevision =
      transaction.loadPostRevision(revision.postId, previousRevisionNr).getOrDie(
        "DwE5GLK2", o"""In site $siteId, post ${revision.postId} revision $previousRevisionNr
            is missing""")

    loadRevisionsFillInSource(previousRevision, revisionsRecentFirstWithSource,
      atLeast - 1, transaction)

    val prevRevWithSource = revisionsRecentFirstWithSource.headOption getOrDie "DwE85UF2"
    val revisionWithSource =
      if (revision.fullSource.isDefined) revision
      else revision.copyAndPatchSourceFrom(prevRevWithSource)
    revisionsRecentFirstWithSource.push(revisionWithSource)
  }


  def changePostType(pageId: PageId, postNr: PostNr, newType: PostType,
        changerId: UserId, browserIdData: BrowserIdData) {
    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val postBefore = page.parts.thePost(postNr)
      val postAfter = postBefore.copy(tyype = newType)
      val Seq(author, changer) = transaction.loadTheUsers(postBefore.createdById, changerId)

      // Test if the changer is allowed to change the post type in this way.
      if (changer.isStaff) {
        (postBefore.tyype, postAfter.tyype) match {
          case (before, after)
            if before == PostType.Normal && after.isWiki => // Fine, staff wikifies post.
          case (before, after)
            if before.isWiki && after == PostType.Normal => // Fine, staff removes wiki status.
          case (before, after) =>
            throwForbidden("DwE7KFE2", s"Cannot change post type from $before to $after")
        }
      }
      else {
        // All normal users may do is to remove wiki status of their own posts.
        if (postBefore.isWiki && postAfter.tyype == PostType.Normal) {
          if (changer.id != author.id)
            throwForbidden("DwE5KGPF2", o"""You are not the author and not staff,
                so you cannot remove the Wiki status of this post""")
        }
        else {
            throwForbidden("DwE4KXB2", s"""Cannot change post type from
                ${postBefore.tyype} to ${postAfter.tyype}""")
        }
      }

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.ChangePostType,
        doerId = changerId,
        doneAt = transaction.currentTime,
        browserIdData = browserIdData,
        pageId = Some(pageId),
        uniquePostId = Some(postBefore.uniqueId),
        postNr = Some(postNr),
        targetUserId = Some(postBefore.createdById))

      val oldMeta = page.meta
      val newMeta = oldMeta.copy(version = oldMeta.version + 1)

      transaction.updatePost(postAfter)
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = false)
      insertAuditLogEntry(auditLogEntry, transaction)
      // COULD generate some notification? E.g. "Your post was made wiki-editable."
    }

    refreshPageInAnyCache(pageId)
  }


  def changePostStatus(postNr: PostNr, pageId: PageId, action: PostStatusAction, userId: UserId) {
    import com.debiki.core.{PostStatusAction => PSA}
    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)

      val postBefore = page.parts.thePost(postNr)
      val user = transaction.loadUser(userId) getOrElse throwForbidden("DwE3KFW2", "Bad user id")

      // Authorization.
      if (!user.isStaff) {
        if (postBefore.createdById != userId)
          throwForbidden("DwE0PK24", "You may not modify that post, it's not yours")

        if (!action.isInstanceOf[PSA.DeletePost] && action != PSA.CollapsePost)
          throwForbidden("DwE5JKF7", "You may not modify the whole tree")
      }

      val isChangingDeletePostToDeleteTree =
        postBefore.deletedStatus.onlyThisDeleted && action == PSA.DeleteTree
      if (postBefore.isDeleted && !isChangingDeletePostToDeleteTree)
        throwForbidden("DwE5GUK5", "This post has already been deleted")

      var numVisibleRepliesGone = 0
      var numOrigPostVisibleRepliesGone = 0

      // Update the directly affected post.
      val postAfter = action match {
        case PSA.HidePost =>
          postBefore.copyWithNewStatus(transaction.currentTime, userId, postHidden = true)
        case PSA.UnhidePost =>
          postBefore.copyWithNewStatus(transaction.currentTime, userId, postUnhidden = true)
        case PSA.CloseTree =>
          postBefore.copyWithNewStatus(transaction.currentTime, userId, treeClosed = true)
        case PSA.CollapsePost =>
          postBefore.copyWithNewStatus(transaction.currentTime, userId, postCollapsed = true)
        case PSA.CollapseTree =>
          postBefore.copyWithNewStatus(transaction.currentTime, userId, treeCollapsed = true)
        case PSA.DeletePost(clearFlags) =>
          if (postBefore.isVisible && postBefore.isReply) {
            numVisibleRepliesGone += 1
            if (postBefore.isOrigPostReply) {
              numOrigPostVisibleRepliesGone += 1
            }
          }
          postBefore.copyWithNewStatus(transaction.currentTime, userId, postDeleted = true)
        case PSA.DeleteTree =>
          if (postBefore.isVisible && postBefore.isReply) {
            numVisibleRepliesGone += 1
            if (postBefore.isOrigPostReply) {
              numOrigPostVisibleRepliesGone += 1
            }
          }
          postBefore.copyWithNewStatus(transaction.currentTime, userId, treeDeleted = true)
      }

      SHOULD // delete any review tasks.

      transaction.updatePost(postAfter)

      // Update any indirectly affected posts, e.g. subsequent comments in the same
      // thread that are being deleted recursively.
      for (successor <- page.parts.successorsOf(postNr)) {
        val anyUpdatedSuccessor: Option[Post] = action match {
          case PSA.CloseTree =>
            if (successor.closedStatus.areAncestorsClosed) None
            else Some(successor.copyWithNewStatus(
              transaction.currentTime, userId, ancestorsClosed = true))
          case PSA.CollapsePost =>
            None
          case PSA.CollapseTree =>
            if (successor.collapsedStatus.areAncestorsCollapsed) None
            else Some(successor.copyWithNewStatus(
              transaction.currentTime, userId, ancestorsCollapsed = true))
          case PSA.DeletePost(clearFlags) =>
            None
          case PSA.DeleteTree =>
            if (successor.isVisible && successor.isReply) {
              numVisibleRepliesGone += 1
              if (successor.isOrigPostReply) {
                // Was the orig post + all replies deleted recursively? Weird.
                numOrigPostVisibleRepliesGone += 1
              }
            }
            if (successor.deletedStatus.areAncestorsDeleted) None
            else Some(successor.copyWithNewStatus(
              transaction.currentTime, userId, ancestorsDeleted = true))
          case x =>
            die("DwE8FMU3", "PostAction not implemented: " + x)
        }

        anyUpdatedSuccessor foreach { updatedSuccessor =>
          transaction.updatePost(updatedSuccessor)
        }
      }

      val oldMeta = page.meta
      var newMeta = oldMeta.copy(version = oldMeta.version + 1)
      var markSectionPageStale = false
      if (numVisibleRepliesGone > 0) {
        newMeta = newMeta.copy(
          numRepliesVisible = oldMeta.numRepliesVisible - numVisibleRepliesGone,
          numOrigPostRepliesVisible =
            // For now: use max() because the db field was just added so some counts are off.
            math.max(oldMeta.numOrigPostRepliesVisible - numOrigPostVisibleRepliesGone, 0))
        markSectionPageStale = true
      }
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale)

      // In the future: if is a forum topic, and we're restoring the OP, then bump the topic.
    }

    refreshPageInAnyCache(pageId)
  }


  def approvePost(pageId: PageId, postNr: PostNr, approverId: UserId) {
    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val pageMeta = page.meta
      val postBefore = page.parts.thePost(postNr)
      if (postBefore.isCurrentVersionApproved)
        throwForbidden("DwE4GYUR2", "Post already approved")

      // If this revision is being approved by a human, then it's safe.
      val safeRevisionNr =
        if (approverId != SystemUserId) Some(postBefore.currentRevisionNr)
        else postBefore.safeRevisionNr

      val postAfter = postBefore.copy(
        safeRevisionNr = safeRevisionNr,
        approvedRevisionNr = Some(postBefore.currentRevisionNr),
        approvedAt = Some(transaction.currentTime),
        approvedById = Some(approverId),
        approvedSource = Some(postBefore.currentSource),
        approvedHtmlSanitized = Some(postBefore.currentHtmlSanitized(
          commonmarkRenderer, pageMeta.pageRole)))
      transaction.updatePost(postAfter)

      SHOULD // delete any review tasks.

      val isApprovingPageBody = postNr == PageParts.BodyNr
      val isApprovingNewPost = postBefore.approvedRevisionNr.isEmpty

      var newMeta = pageMeta.copy(version = pageMeta.version + 1)
      // Bump page and update reply counts if a new post was approved and became visible,
      // or if the original post was edited.
      var makesSectionPageHtmlStale = false
      if (isApprovingNewPost || isApprovingPageBody) {
        val (numNewReplies, numNewOrigPostReplies, newLastReplyAt) =
          if (isApprovingNewPost && postAfter.isReply)
            (1, postAfter.isOrigPostReply ? 1 | 0, Some(transaction.currentTime))
          else
            (0, 0, pageMeta.lastReplyAt)

        newMeta = newMeta.copy(
          numRepliesVisible = pageMeta.numRepliesVisible + numNewReplies,
          numOrigPostRepliesVisible = pageMeta.numOrigPostRepliesVisible + numNewOrigPostReplies,
          lastReplyAt = newLastReplyAt,
          bumpedAt = Some(transaction.currentTime))
        makesSectionPageHtmlStale = true
      }
      transaction.updatePageMeta(newMeta, oldMeta = pageMeta, makesSectionPageHtmlStale)

      val notifications =
        if (isApprovingNewPost) {
          NotificationGenerator(transaction).generateForNewPost(page, postAfter)
        }
        else {
          NotificationGenerator(transaction).generateForEdits(postBefore, postAfter)
        }
      transaction.saveDeleteNotifications(notifications)
    }

    refreshPageInAnyCache(pageId)
  }


  def deletePost(pageId: PageId, postNr: PostNr, deletedById: UserId,
        browserIdData: BrowserIdData) {
    changePostStatus(pageId = pageId, postNr = postNr,
      action = PostStatusAction.DeletePost(clearFlags = false), userId = deletedById)
  }


  def deleteVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId) {
    readWriteTransaction { transaction =>
      transaction.deleteVote(pageId, postNr, voteType, voterId = voterId)
      updateVoteCounts(pageId, postNr = postNr, transaction)
      /* FRAUD SHOULD delete by cookie too, like I did before:
      var numRowsDeleted = 0
      if ((userIdData.anyGuestId.isDefined && userIdData.userId != UnknownUser.Id) ||
        userIdData.anyRoleId.isDefined) {
        numRowsDeleted = deleteVoteByUserId()
      }
      if (numRowsDeleted == 0 && userIdData.browserIdCookie.isDefined) {
        numRowsDeleted = deleteVoteByCookie()
      }
      if (numRowsDeleted > 1) {
        assErr("DwE8GCH0", o"""Too many votes deleted, page `$pageId' post `$postId',
          user: $userIdData, vote type: $voteType""")
      }
      */
    }
    refreshPageInAnyCache(pageId)
  }


  def ifAuthAddVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType,
        voterId: UserId, voterIp: String, postNrsRead: Set[PostNr]) {
    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val post = page.parts.thePost(postNr)
      val voter = transaction.loadTheUser(voterId)

      if (voteType == PostVoteType.Bury && !voter.isStaff)
        throwForbidden("DwE2WU74", "Only staff and regular members may Bury-vote")

      if (voteType == PostVoteType.Unwanted && !voter.isStaff && page.meta.authorId != voterId)
        throwForbidden("DwE5JUK0", "Only staff and the page author may Unwanted-vote")

      if (voteType == PostVoteType.Like) {
        if (post.createdById == voterId)
          throwForbidden("DwE84QM0", "Cannot like own post")
      }

      try {
        transaction.insertVote(post.uniqueId, pageId, postNr, voteType, voterId = voterId)
      }
      catch {
        case DbDao.DuplicateVoteException =>
          throwForbidden("Dw403BKW2", "You have already voted")
      }

      // Update post read stats.
      val postsToMarkAsRead =
        if (voteType == PostVoteType.Like) {
          // Upvoting a post shouldn't affect its ancestors, because they're on the
          // path to the interesting post so they are a bit useful/interesting. However
          // do mark all earlier siblings as read since they weren't upvoted (this time).
          val ancestorIds = page.parts.ancestorsOf(postNr).map(_.nr)
          postNrsRead -- ancestorIds.toSet
        }
        else {
          // The post got a non-like vote: wrong, bury or unwanted.
          // This should result in only the downvoted post
          // being marked as read, because a post *not* being downvoted shouldn't
          // give that post worse rating. (Remember that the rating of a post is
          // roughly the number of Like votes / num-times-it's-been-read.)
          Set(postNr)
        }

      transaction.updatePostsReadStats(pageId, postsToMarkAsRead, readById = voterId,
        readFromIp = voterIp)

      updateVoteCounts(post, transaction)
    }
    refreshPageInAnyCache(pageId)
  }


  def loadThingsToReview(): ThingsToReview = {
    readOnlyTransaction { transaction =>
      val posts = transaction.loadPostsToReview()
      val pageMetas = transaction.loadPageMetas(posts.map(_.pageId))
      val flags = transaction.loadFlagsFor(posts.map(_.pagePostId))
      val userIds = mutable.HashSet[UserId]()
      userIds ++= posts.map(_.createdById)
      userIds ++= posts.map(_.currentRevisionById)
      userIds ++= flags.map(_.flaggerId)
      val users = transaction.loadUsers(userIds.toSeq)
      ThingsToReview(posts, pageMetas, users, flags)
    }
  }


  def flagPost(pageId: PageId, postNr: PostNr, flagType: PostFlagType, flaggerId: UserId) {
    readWriteTransaction { transaction =>
      val postBefore = transaction.loadThePost(pageId, postNr)
      // SHOULD if >= 2 pending flags, then hide post until reviewed? And unhide, if flags cleared.
      val postAfter = postBefore.copy(numPendingFlags = postBefore.numPendingFlags + 1)
      val reviewTask = makeReviewTask(flaggerId, postAfter,
        immutable.Seq(ReviewReason.PostFlagged), transaction)
      transaction.insertFlag(postBefore.uniqueId, pageId, postNr, flagType, flaggerId)
      transaction.updatePost(postAfter)
      transaction.upsertReviewTask(reviewTask)
      // Need not update page version: flags aren't shown (except perhaps for staff users).
    }
    refreshPageInAnyCache(pageId)
  }


  def clearFlags(pageId: PageId, postNr: PostNr, clearedById: UserId): Unit = {
    readWriteTransaction { transaction =>
      val postBefore = transaction.loadThePost(pageId, postNr)
      val postAfter = postBefore.copy(
        numPendingFlags = 0,
        numHandledFlags = postBefore.numHandledFlags + postBefore.numPendingFlags)
      transaction.updatePost(postAfter)
      transaction.clearFlags(pageId, postNr, clearedById = clearedById)
      // Need not update page version: flags aren't shown (except perhaps for staff users).
    }
    // In case the post gets unhidden now when flags gone:
    refreshPageInAnyCache(pageId)
  }


  def loadPostsReadStats(pageId: PageId): PostsReadStats =
    readOnlyTransaction(_.loadPostsReadStats(pageId))


  def loadPost(pageId: PageId, postNr: PostNr): Option[Post] =
    readOnlyTransaction(_.loadPost(pageId, postNr))


  def makeReviewTask(causedById: UserId, post: Post, reasons: immutable.Seq[ReviewReason],
        transaction: SiteTransaction): ReviewTask = {
    val oldReviewTask = transaction.loadPendingPostReviewTask(post.uniqueId,
      causedById = causedById)
    val newTask = ReviewTask(
      id = oldReviewTask.map(_.id).getOrElse(transaction.nextReviewTaskId()),
      reasons = reasons,
      causedById = causedById,
      createdAt = transaction.currentTime,
      createdAtRevNr = Some(post.currentRevisionNr),
      postId = Some(post.uniqueId),
      postNr = Some(post.nr))
    newTask.mergeWithAny(oldReviewTask)
  }


  private def updateVoteCounts(pageId: PageId, postNr: PostNr, transaction: SiteTransaction) {
    val post = transaction.loadThePost(pageId, postNr = postNr)
    updateVoteCounts(post, transaction)
  }


  private def updateVoteCounts(post: Post, transaction: SiteTransaction) {
    val actions = transaction.loadActionsDoneToPost(post.pageId, postNr = post.nr)
    val readStats = transaction.loadPostsReadStats(post.pageId, Some(post.nr))
    val postAfter = post.copyWithUpdatedVoteAndReadCounts(actions, readStats)

    val numNewLikes = postAfter.numLikeVotes - post.numLikeVotes
    val numNewWrongs = postAfter.numWrongVotes - post.numWrongVotes
    val numNewBurys = postAfter.numBuryVotes - post.numBuryVotes
    val numNewUnwanteds = postAfter.numUnwantedVotes - post.numUnwantedVotes

    val (numNewOpLikes, numNewOpWrongs, numNewOpBurys, numNewOpUnwanteds) =
      if (post.isOrigPost)
        (numNewLikes, numNewWrongs, numNewBurys, numNewUnwanteds)
      else
        (0, 0, 0, 0)

    val pageMetaBefore = transaction.loadThePageMeta(post.pageId)
    val pageMetaAfter = pageMetaBefore.copy(
      numLikes = pageMetaBefore.numLikes + numNewLikes,
      numWrongs = pageMetaBefore.numWrongs + numNewWrongs,
      numBurys = pageMetaBefore.numBurys + numNewBurys,
      numUnwanteds = pageMetaBefore.numUnwanteds + numNewUnwanteds,
      // For now: use max() because the db fields were just added so some counts are off.
      // (but not for Unwanted, that vote was added after the vote count fields)
      numOrigPostLikeVotes = math.max(0, pageMetaBefore.numOrigPostLikeVotes + numNewOpLikes),
      numOrigPostWrongVotes = math.max(0, pageMetaBefore.numOrigPostWrongVotes + numNewOpWrongs),
      numOrigPostBuryVotes = math.max(0, pageMetaBefore.numOrigPostBuryVotes + numNewOpBurys),
      numOrigPostUnwantedVotes = pageMetaBefore.numOrigPostUnwantedVotes + numNewOpUnwanteds,
      version = pageMetaBefore.version + 1)

    transaction.updatePost(postAfter)
    transaction.updatePageMeta(pageMetaAfter, oldMeta = pageMetaBefore,
      markSectionPageStale = true)

    // COULD split e.g. num_like_votes into ..._total and ..._unique? And update here.
  }

}



object PostsDao {

  private val SixMinutesMs = 6 * 60 * 1000
  private val OneHourMs = SixMinutesMs * 10
  private val OneDayMs = OneHourMs * 24

  val HardMaxNinjaEditWindowMs = OneDayMs

  /** For non-discussion pages, uses a long ninja edit window.
    */
  def ninjaEditWindowMsFor(pageRole: PageRole): Int = pageRole match {
    case PageRole.HomePage => OneHourMs
    case PageRole.WebPage => OneHourMs
    case PageRole.Code => OneHourMs
    case PageRole.SpecialContent => OneHourMs
    case PageRole.Blog => OneHourMs
    case PageRole.Forum => OneHourMs
    case _ => SixMinutesMs
  }

  def userMayEdit(user: User, post: Post): Boolean = {
    val editsOwnPost = user.id == post.createdById
    val mayEditWiki = user.isAuthenticated && post.tyype == PostType.CommunityWiki
    editsOwnPost || user.isStaff || mayEditWiki
  }
}



trait CachingPostsDao extends PagesDao {
  self: CachingSiteDao =>

  // We cache all html already, that might be enough actually. For now, don't cache posts too.
  // So I've removed all cache-posts code from here.

}

