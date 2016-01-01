/**
 * Copyright (C) 2015 Kaj Magnus Lindberg (born 1979)
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

package com.debiki.core

import com.debiki.core.Prelude._
import java.net.InetAddress
import java.{util => ju}
import com.debiki.core.EmailNotfPrefs._

import scala.collection.immutable


// SHOULD/COULD convert old method implementations to start using transactions.
trait SiteTransaction {
  def commit()
  def rollback()
  def siteId: SiteId

  def setSiteId(id: SiteId)

  def deferConstraints()

  /** Throws SiteAlreadyExistsException if the site already exists.
    * Throws TooManySitesCreatedException if you've created too many websites already
    * (from the same IP or email address).
    */
  def createSite(name: String, hostname: String, embeddingSiteUrl: Option[String],
    creatorIp: String, creatorEmailAddress: String, pricePlan: Option[String],
    quotaLimitMegabytes: Option[Int], isTestSiteOkayToDelete: Boolean,
    skipMaxSitesCheck: Boolean): Site

  def loadTenant(): Site

  def insertSiteHost(host: SiteHost)

  def loadSiteStatus(): SiteStatus
  def bumpSiteVersion()
  def updateSite(changedSite: Site)


  // Try to remove, use sth more generic like insertUser()? or insertGuest() instead?
  def createUnknownUser()

  def addSiteHost(host: SiteHost)
  def loadSiteVersion(): Int

  def saveSetting(target: SettingsTarget, setting: SettingNameValue[_])
  /** Loads settings for all listed targets, returns settings in the same order.
    */
  def loadSettings(targets: Seq[SettingsTarget]): Seq[RawSettings]
  def loadSiteSettings(): RawSettings =
    loadSettings(Vector(SettingsTarget.WholeSite)).headOption.getOrDie("DwE5fl09")

  def loadResourceUsage(): ResourceUse

  def loadCategory(categoryId: CategoryId): Option[Category]
  def loadCategoryMap(): Map[CategoryId, Category]
  def nextCategoryId(): Int
  def insertCategoryMarkSectionPageStale(category: Category)
  def updateCategoryMarkSectionPageStale(category: Category)

  def loadPost(uniquePostId: UniquePostId): Option[Post]
  def loadThePost(uniquePostId: UniquePostId): Post =
    loadPost(uniquePostId).getOrElse(throw PostNotFoundByIdException(uniquePostId))

  def loadPost(pageId: PageId, postNr: PostNr): Option[Post]
  def loadThePost(pageId: PageId, postNr: PostNr): Post =
    loadPost(pageId, postNr).getOrElse(throw PostNotFoundException(pageId, postNr))

  def loadPostsOnPage(pageId: PageId, siteId: Option[SiteId] = None): immutable.Seq[Post]
  def loadPosts(pagePostNrs: Iterable[PagePostNr]): immutable.Seq[Post]
  def loadPostsByUniqueId(postIds: Iterable[UniquePostId]): immutable.Map[UniquePostId, Post]
  def loadPostsBy(authorId: UserId, includeTitles: Boolean, limit: Int): immutable.Seq[Post]
  def loadPostsToReview(): immutable.Seq[Post]

  def loadTitlesPreferApproved(pageIds: Iterable[PageId]): Map[PageId, String] = {
    val titlePosts = loadPosts(pageIds.map(PagePostNr(_, PageParts.TitleNr)))
    Map(titlePosts.map(post => {
      post.pageId -> post.approvedSource.getOrElse(post.currentSource)
    }): _*)
  }

  def nextPostId(): UniquePostId
  def insertPost(newPost: Post)
  def updatePost(newPost: Post)

  def insertMessageMember(pageId: PageId, userId: UserId, addedById: UserId)
  def loadMessageMembers(pageId: PageId): Set[UserId]

  def loadLastPostRevision(postId: UniquePostId): Option[PostRevision]
  def loadPostRevision(postId: UniquePostId, revisionNr: Int): Option[PostRevision]
  def insertPostRevision(revision: PostRevision)
  def updatePostRevision(revision: PostRevision)

  def loadActionsByUserOnPage(userId: UserId, pageId: PageId): immutable.Seq[PostAction]
  def loadActionsDoneToPost(pageId: PageId, postNr: PostNr): immutable.Seq[PostAction]

  def deleteVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId): Boolean
  def insertVote(uniquePostId: UniquePostId, pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId)

  /** Remembers that the specified posts have been read by a certain user.
    */
  def updatePostsReadStats(pageId: PageId, postNrsRead: Set[PostNr], readById: UserId,
        readFromIp: String)

  def loadPostsReadStats(pageId: PageId, postNr: Option[PostNr]): PostsReadStats
  def loadPostsReadStats(pageId: PageId): PostsReadStats


  def loadFlagsFor(pagePostNrs: immutable.Seq[PagePostNr]): immutable.Seq[PostFlag]
  def insertFlag(uniquePostId: UniquePostId, pageId: PageId, postNr: PostNr, flagType: PostFlagType, flaggerId: UserId)
  def clearFlags(pageId: PageId, postNr: PostNr, clearedById: UserId)

  def nextPageId(): PageId

  def loadAllPageMetas(): immutable.Seq[PageMeta]
  def loadPageMeta(pageId: PageId): Option[PageMeta]
  def loadPageMetasAsMap(pageIds: Seq[PageId], anySiteId: Option[SiteId] = None)
  : Map[PageId, PageMeta]
  def loadThePageMeta(pageId: PageId): PageMeta =
    loadPageMeta(pageId).getOrElse(throw PageNotFoundException(pageId))

  def loadPageMetas(pageIds: Seq[PageId]): immutable.Seq[PageMeta]
  def loadPageMetasAsMap(pageIds: Iterable[PageId]): Map[PageId, PageMeta]
  def insertPageMetaMarkSectionPageStale(newMeta: PageMeta)
  def updatePageMeta(newMeta: PageMeta, oldMeta: PageMeta, markSectionPageStale: Boolean)

  def markPagesWithUserAvatarAsStale(userId: UserId)
  def markSectionPageContentHtmlAsStale(categoryId: CategoryId)
  def loadCachedPageContentHtml(pageId: PageId): Option[(String, CachedPageVersion)]
  // (Could move this one to a transactionless Dao interface?)
  def saveCachedPageContentHtmlPerhapsBreakTransaction(
    pageId: PageId, version: CachedPageVersion, html: String): Boolean


  def insertPagePath(pagePath: PagePath)
  def loadPagePath(pageId: PageId): Option[PagePath]
  def checkPagePath(pathToCheck: PagePath): Option[PagePath]  // rename? check? load? what?
  /**
    * Loads all PagePaths that map to pageId. The canonical path is placed first
    * and the tail consists only of any redirection paths.
    */
  def lookupPagePathAndRedirects(pageId: PageId): List[PagePath]
  def listPagePaths(pageRanges: PathRanges, include: List[PageStatus],
    orderOffset: PageOrderOffset, limit: Int): Seq[PagePathAndMeta]

  def loadPagesInCategories(categoryIds: Seq[CategoryId], pageQuery: PageQuery, limit: Int)
  : Seq[PagePathAndMeta]

  def moveRenamePage(pageId: PageId,
    newFolder: Option[String] = None, showId: Option[Boolean] = None,
    newSlug: Option[String] = None): PagePath


  def currentTime: ju.Date


  /** Remembers that a file has been uploaded and where it's located. */
  def insertUploadedFileMeta(uploadRef: UploadRef, sizeBytes: Int, mimeType: String,
        dimensions: Option[(Int, Int)])
  def deleteUploadedFileMeta(uploadRef: UploadRef)

  /** Uploaded files are referenced via 1) URLs in posts (e.g. `<a href=...> <img src=...>`)
    * and 2) from users, if a file is someone's avatar image.
    */
  def updateUploadedFileReferenceCount(uploadRef: UploadRef)

  /** Remembers that an uploaded file is referenced from this post. */
  def insertUploadedFileReference(postId: UniquePostId, uploadRef: UploadRef, addedById: UserId)
  def deleteUploadedFileReference(postId: UniquePostId, uploadRef: UploadRef): Boolean
  def loadUploadedFileReferences(postId: UniquePostId): Set[UploadRef]
  def loadSiteIdsUsingUpload(ref: UploadRef): Set[SiteId]

  /** Returns the refs currently in use, e.g. as user avatar images or
    * images / attachments inserted into posts.
    */
  def filterUploadRefsInUse(uploadRefs: Iterable[UploadRef]): Set[UploadRef]
  def updateUploadQuotaUse(uploadRef: UploadRef, wasAdded: Boolean)


  def insertInvite(invite: Invite)
  def updateInvite(invite: Invite): Boolean
  def loadInvite(secretKey: String): Option[Invite]
  def loadInvites(createdById: UserId): immutable.Seq[Invite]

  def nextIdentityId: IdentityId
  def insertIdentity(Identity: Identity)
  def loadIdtyDetailsAndUser(userId: UserId): Option[(Identity, User)]

  def nextAuthenticatedUserId: UserId
  def insertAuthenticatedUser(user: CompleteUser)

  def tryLogin(loginAttempt: LoginAttempt): LoginGrant
  def loginAsGuest(loginAttempt: GuestLoginAttempt): GuestLoginResult
  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs)

  def loadCompleteUser(userId: UserId): Option[CompleteUser]

  def loadTheCompleteUser(userId: UserId): CompleteUser =
    loadCompleteUser(userId).getOrElse(throw UserNotFoundException(userId))

  def updateCompleteUser(user: CompleteUser): Boolean
  def updateGuest(guest: User): Boolean

  def loadUser(userId: UserId): Option[User]
  def loadTheUser(userId: UserId) = loadUser(userId).getOrElse(throw UserNotFoundException(userId))

  def loadUsers(userIds: Iterable[UserId]): immutable.Seq[User]
  def loadTheUsers(userIds: UserId*): immutable.Seq[User] = {
    val usersById = loadUsersAsMap(userIds)
    userIds.to[immutable.Seq] map { id =>
      usersById.getOrElse(id, throw UserNotFoundException(id))
    }
  }

  def loadUsersOnPageAsMap2(pageId: PageId, siteId: Option[SiteId] = None): Map[UserId, User]
  def loadUsersAsMap(userIds: Iterable[UserId]): Map[UserId, User]
  def loadUserByEmailOrUsername(emailOrUsername: String): Option[User]

  def loadUsers(): immutable.Seq[User]
  def loadCompleteUsers(
    onlyApproved: Boolean = false,
    onlyPendingApproval: Boolean = false): immutable.Seq[CompleteUser]

  /** Loads users watching the specified page, any parent categories or forums,
    * and people watching everything on the whole site.
    */
  def loadUserIdsWatchingPage(pageId: PageId): Seq[UserId]

  def loadRolePageSettings(roleId: RoleId, pageId: PageId): Option[RolePageSettings]
  def loadRolePageSettingsOrDefault(roleId: RoleId, pageId: PageId) =
        loadRolePageSettings(roleId, pageId) getOrElse RolePageSettings.Default

  def loadUserInfoAndStats(userId: UserId): Option[UserInfoAndStats]
  def loadUserStats(userId: UserId): UserStats
  def listUserActions(userId: UserId): Seq[UserActionInfo]
  def listUsernames(pageId: PageId, prefix: String): Seq[NameAndUsername]

  def saveRolePageSettings(roleId: RoleId, pageId: PageId, settings: RolePageSettings)

  def saveUnsentEmail(email: Email): Unit
  def saveUnsentEmailConnectToNotfs(email: Email, notfs: Seq[Notification])
  def updateSentEmail(email: Email): Unit
  def loadEmailById(emailId: String): Option[Email]

  def nextReviewTaskId(): ReviewTaskId
  def upsertReviewTask(reviewTask: ReviewTask)
  def loadReviewTask(id: ReviewTaskId): Option[ReviewTask]
  def loadReviewTasks(olderOrEqualTo: ju.Date, limit: Int): Seq[ReviewTask]
  def loadReviewTaskCounts(isAdmin: Boolean): ReviewTaskCounts
  def loadPendingPostReviewTask(postId: UniquePostId, causedById: UserId): Option[ReviewTask]

  def nextNotificationId(): NotificationId
  def saveDeleteNotifications(notifications: Notifications)
  def updateNotificationSkipEmail(notifications: Seq[Notification])
  def markNotfAsSeenSkipEmail(userId: UserId, notfId: NotificationId)
  def loadNotificationsForRole(roleId: RoleId, limit: Int, unseenFirst: Boolean,
    upToWhen: Option[ju.Date] = None): Seq[Notification]


  def nextAuditLogEntryId: AuditLogEntryId
  def insertAuditLogEntry(entry: AuditLogEntry)
  def loadCreatePostAuditLogEntry(postId: UniquePostId): Option[AuditLogEntry]
  def loadAuditLogEntriesRecentFirst(userId: UserId, tyype: AuditLogEntryType, limit: Int)
        : immutable.Seq[AuditLogEntry]

  def loadBlocks(ip: String, browserIdCookie: String): immutable.Seq[Block]
  def insertBlock(block: Block)
  def unblockIp(ip: InetAddress)
  def unblockBrowser(browserIdCookie: String)
}


case class UserNotFoundException(userId: UserId) extends QuickException
case class PageNotFoundException(pageId: PageId) extends QuickException
case class PostNotFoundException(pageId: PageId, postNr: PostNr) extends QuickException
case class PostNotFoundByIdException(postId: UniquePostId) extends QuickException

