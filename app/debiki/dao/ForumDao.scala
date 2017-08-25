/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
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
import scala.collection.immutable
import ForumDao._


case class CreateForumResult(
  pagePath: PagePath,
  staffCategoryId: CategoryId,
  defaultCategoryId: CategoryId)


/** Creates forums.
  */
trait ForumDao {
  self: SiteDao =>


  def createForum(title: String, folder: String, byWho: Who): CreateForumResult = {
    val titleHtmlSanitized = context.nashorn.sanitizeHtml(title, followLinks = false)
    readWriteTransaction { transaction =>

      // The forum page points to the root category, which points back.
      transaction.deferConstraints()

      val rootCategoryId = transaction.nextCategoryId()

      // Create forum page.
      val (forumPagePath, _) = createPageImpl(
        PageRole.Forum, PageStatus.Published, anyCategoryId = Some(rootCategoryId),
        anyFolder = Some(folder), anySlug = Some(""), showId = false,
        titleSource = title, titleHtmlSanitized = titleHtmlSanitized,
        bodySource = ForumIntroText.source, bodyHtmlSanitized = ForumIntroText.html,
        pinOrder = None, pinWhere = None,
        byWho, spamRelReqStuff = None, transaction)

      val forumPageId = forumPagePath.pageId getOrDie "DwE5KPFW2"

      val partialResult: CreateForumResult = createDefaultCategoriesAndTopics(
        forumPageId, rootCategoryId, byWho, transaction)

      // COULD create audit log entries.

      partialResult.copy(pagePath = forumPagePath)
    }
  }


  private def createDefaultCategoriesAndTopics(forumPageId: PageId, rootCategoryId: CategoryId,
        byWho: Who, transaction: SiteTransaction): CreateForumResult = {

    val defaultCategoryId = rootCategoryId + 1
    val staffCategoryId = rootCategoryId + 2
    val bySystem = Who(SystemUserId, byWho.browserIdData)

    // Create forum root category.
    transaction.insertCategoryMarkSectionPageStale(Category(
      id = rootCategoryId,
      sectionPageId = forumPageId,
      parentId = None,
      defaultCategoryId = Some(defaultCategoryId),
      name = RootCategoryName,
      slug = RootCategorySlug,
      position = 1,
      description = None,
      newTopicTypes = Nil,
      unlisted = false,
      includeInSummaries = IncludeInSummaries.Default,
      createdAt = transaction.now.toJavaDate,
      updatedAt = transaction.now.toJavaDate))

    // Create the default category.
    createCategoryImpl(
      CategoryToSave(
        anyId = Some(defaultCategoryId),
        sectionPageId = forumPageId,
        parentId = rootCategoryId,
        shallBeDefaultCategory = true,
        name = DefaultCategoryName,
        slug = DefaultCategorySlug,
        position = DefaultCategoryPosition,
        description = "New topics get placed here, unless another category is selected.",
        newTopicTypes = immutable.Seq(PageRole.Discussion),
        unlisted = false,
        includeInSummaries = IncludeInSummaries.Default,
        isCreatingNewForum = true),
      immutable.Seq[PermsOnPages](
        makeEveryonesDefaultCategoryPerms(defaultCategoryId),
        makeStaffCategoryPerms(defaultCategoryId)),
      bySystem)(transaction)

    // Create the Staff category.
    createCategoryImpl(
      CategoryToSave(
        anyId = Some(staffCategoryId),
        sectionPageId = forumPageId,
        parentId = rootCategoryId,
        shallBeDefaultCategory = false,
        name = "Staff",
        slug = "staff",
        position = Category.DefaultPosition + 10,
        description = "Private category for staff discussions",
        newTopicTypes = immutable.Seq(PageRole.Discussion),
        unlisted = false,
        includeInSummaries = IncludeInSummaries.Default,
        isCreatingNewForum = true),
      immutable.Seq[PermsOnPages](
        makeStaffCategoryPerms(staffCategoryId)),
      bySystem)(transaction)

    // Create forum welcome topic.
    createPageImpl(
      PageRole.Discussion, PageStatus.Published, anyCategoryId = Some(defaultCategoryId),
      anyFolder = None, anySlug = Some("welcome"), showId = true,
      titleSource = WelcomeTopicTitle,
      titleHtmlSanitized = WelcomeTopicTitle,
      bodySource = welcomeTopic.source,
      bodyHtmlSanitized = welcomeTopic.html,
      pinOrder = Some(WelcomeToForumTopicPinOrder),
      pinWhere = Some(PinPageWhere.Globally),
      bySystem,
      spamRelReqStuff = None,
      transaction)

    CreateForumResult(null, defaultCategoryId = defaultCategoryId,
      staffCategoryId = staffCategoryId)
  }


  private val RootCategoryName = "(Root Category)"  // In Typescript test code too [7UKPX5]
  private val RootCategorySlug = "(root-category)"  //

  private val DefaultCategoryName = "Uncategorized"
  private val DefaultCategorySlug = "uncategorized"
  private val DefaultCategoryPosition = 1000

}


object ForumDao {

  val WelcomeToForumTopicPinOrder = 5
  val AboutCategoryTopicPinOrder = 10


  val ForumIntroText: CommonMarkSourceAndHtml = {
    val source = o"""Edit this to tell people what this community is about.
        You can link back to your main website, if any."""
    CommonMarkSourceAndHtml(source, html = s"<p>$source</p>")
  }


  val WelcomeTopicTitle = "Welcome to this community"

  val welcomeTopic: CommonMarkSourceAndHtml = {
    val para1Line1 = "Edit this to clarify what this community is about. This first paragraph"
    val para1Line2 = "is shown to everyone, on the forum homepage."
    val para2Line1 = "Here, below the first paragraph, add details like:"
    val listItem1 = "Who is this community for?"
    val listItem2 = "What can they do or find here?"
    val listItem3 = "Link to additional info, for example, any FAQ, or main website of yours."
    val toEditText = """To edit this, click the <b class="icon-edit"></b> icon below."""
    CommonMarkSourceAndHtml(
      source = i"""
        |$para1Line1
        |$para1Line2
        |
        |$para2Line1
        |- $listItem1
        |- $listItem2
        |- $listItem3
        |
        |$toEditText
        |""",
      html = i"""
        |<p>$para1Line1 $para1Line2</p>
        |<p>$para2Line1</p>
        |<ol><li>$listItem1</li><li>$listItem2</li><li>$listItem3</li></ol>
        |<p>$toEditText</p>
        """)
  }


  // Sync with dupl code in Typescript. [7KFWY025]
  def makeEveryonesDefaultCategoryPerms(categoryId: CategoryId) = PermsOnPages(
    id = NoPermissionId,
    forPeopleId = Group.EveryoneId,
    onCategoryId = Some(categoryId),
    mayEditOwn = Some(true),
    mayCreatePage = Some(true),
    mayPostComment = Some(true),
    maySee = Some(true),
    maySeeOwn = Some(true))


  // Sync with dupl code in Typescript. [7KFWY025]
  def makeStaffCategoryPerms(categoryId: CategoryId) = PermsOnPages(
    id = NoPermissionId,
    forPeopleId = Group.StaffId,
    onCategoryId = Some(categoryId),
    mayEditPage = Some(true),
    mayEditComment = Some(true),
    mayEditWiki = Some(true),
    mayEditOwn = Some(true),
    mayDeletePage = Some(true),
    mayDeleteComment = Some(true),
    mayCreatePage = Some(true),
    mayPostComment = Some(true),
    maySee = Some(true),
    maySeeOwn = Some(true))

}
