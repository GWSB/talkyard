/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
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
import play.api.libs.json._
import scala.collection.immutable
import ReactJson._
import debiki.EdHttp.throwBadRequest



trait AllSettings {
  self =>

  def userMustBeAuthenticated: Boolean
  def userMustBeApproved: Boolean
  // def approveInvitesHow: HowApproveInvites.BeforeTheyAreSent/AfterSignup/AlwaysAllow
  def inviteOnly: Boolean
  def allowSignup: Boolean
  def allowLocalSignup: Boolean
  def allowGuestLogin: Boolean
  def requireVerifiedEmail: Boolean
  def mayComposeBeforeSignup: Boolean
  def mayPostBeforeEmailVerified: Boolean

  /** When signing up and specifying an email address, one needs to type it twice, so we can
    * check for typos. Not yet implemented (currently never need to double type).
    */
  def doubleTypeEmailAddress: Boolean

  /** See 'doubleTypeEmailAddress' above. Also not yet implemented. */
  def doubleTypePassword: Boolean

  /** When someone signs up, if hen doesn't specify an email address, tell hen that then we
    * cannot send hen notifications about new replies. And show buttons:
    *  [Specify email address] [No, skip]
    */
  def begForEmailAddress: Boolean

  def forumMainView: String
  def forumTopicsSortButtons: String
  def forumCategoryLinks: String
  def forumTopicsLayout: TopicListLayout
  def forumCategoriesLayout: CategoriesLayout
  def showCategories: Boolean
  def showTopicFilterButton: Boolean
  def showTopicTypes: Boolean
  def selectTopicType: Boolean
  def numFirstPostsToReview: Int
  def numFirstPostsToApprove: Int
  def numFirstPostsToAllow: Int
  def headStylesHtml: String
  def headScriptsHtml: String
  def endOfBodyHtml: String
  def headerHtml: String
  def footerHtml: String
  def horizontalComments: Boolean
  def socialLinksHtml: String
  def logoUrlOrHtml: String
  def orgDomain: String
  def orgFullName: String
  def orgShortName: String
  def contribAgreement: ContribAgreement
  def contentLicense: ContentLicense
  def googleUniversalAnalyticsTrackingId: String
  def showExperimental: Boolean
  def allowEmbeddingFrom: String
  def htmlTagCssClasses: String

  def numFlagsToHidePost: Int
  def cooldownMinutesAfterFlaggedHidden: Int

  def numFlagsToBlockNewUser: Int
  def numFlaggersToBlockNewUser: Int
  def notifyModsIfUserBlocked: Boolean

  def regularMemberFlagWeight: Float
  def coreMemberFlagWeight: Float

  def toJson: JsObject = Settings2.settingsToJson(toEditedSettings)

  def toEditedSettings = EditedSettings(
    userMustBeAuthenticated = Some(self.userMustBeAuthenticated),
    userMustBeApproved = Some(self.userMustBeApproved),
    inviteOnly = Some(self.inviteOnly),
    allowSignup = Some(self.allowSignup),
    allowLocalSignup = Some(self.allowLocalSignup),
    allowGuestLogin = Some(self.allowGuestLogin),
    requireVerifiedEmail = Some(self.requireVerifiedEmail),
    mayComposeBeforeSignup = Some(self.mayComposeBeforeSignup),
    mayPostBeforeEmailVerified = Some(self.mayPostBeforeEmailVerified),
    doubleTypeEmailAddress = Some(self.doubleTypeEmailAddress),
    doubleTypePassword = Some(self.doubleTypePassword),
    begForEmailAddress = Some(self.begForEmailAddress),
    forumMainView = Some(self.forumMainView),
    forumTopicsSortButtons = Some(self.forumTopicsSortButtons),
    forumCategoryLinks = Some(self.forumCategoryLinks),
    forumTopicsLayout = Some(self.forumTopicsLayout),
    forumCategoriesLayout = Some(self.forumCategoriesLayout),
    showCategories = Some(self.showCategories),
    showTopicFilterButton = Some(self.showTopicFilterButton),
    showTopicTypes = Some(self.showTopicTypes),
    selectTopicType = Some(self.selectTopicType),
    numFirstPostsToReview = Some(self.numFirstPostsToReview),
    numFirstPostsToApprove = Some(self.numFirstPostsToApprove),
    numFirstPostsToAllow = Some(self.numFirstPostsToAllow),
    headStylesHtml = Some(self.headStylesHtml),
    headScriptsHtml = Some(self.headScriptsHtml),
    endOfBodyHtml = Some(self.endOfBodyHtml),
    headerHtml = Some(self.headerHtml),
    footerHtml = Some(self.footerHtml),
    horizontalComments = Some(self.horizontalComments),
    socialLinksHtml = Some(self.socialLinksHtml),
    logoUrlOrHtml = Some(self.logoUrlOrHtml),
    orgDomain = Some(self.orgDomain),
    orgFullName = Some(self.orgFullName),
    orgShortName = Some(self.orgShortName),
    contribAgreement = Some(self.contribAgreement),
    contentLicense = Some(self.contentLicense),
    googleUniversalAnalyticsTrackingId = Some(self.googleUniversalAnalyticsTrackingId),
    showExperimental = Some(self.showExperimental),
    allowEmbeddingFrom = Some(self.allowEmbeddingFrom),
    htmlTagCssClasses = Some(self.htmlTagCssClasses),
    numFlagsToHidePost = Some(self.numFlagsToHidePost),
    cooldownMinutesAfterFlaggedHidden = Some(self.cooldownMinutesAfterFlaggedHidden),
    numFlagsToBlockNewUser = Some(self.numFlagsToBlockNewUser),
    numFlaggersToBlockNewUser = Some(self.numFlaggersToBlockNewUser),
    notifyModsIfUserBlocked = Some(self.notifyModsIfUserBlocked),
    regularMemberFlagWeight = Some(self.regularMemberFlagWeight),
    coreMemberFlagWeight = Some(self.coreMemberFlagWeight))
}


object AllSettings {

  /** I read somewhere on Discourse's forum that very few people edit their posts 5 hours
    * after they've been created. So 5 hours will result in very few edits to review
    * — and hopefully catch all/most malicious edits.
    */
  val PostRecentlyCreatedLimitMs: Int = 5 * 3600 * 1000

  val Default = new AllSettings {  // [8L4KWU02]
    val userMustBeAuthenticated = false
    val userMustBeApproved = false
    val inviteOnly = false
    val allowSignup = true
    val allowLocalSignup = true
    val allowGuestLogin = false
    val requireVerifiedEmail = true
    val mayComposeBeforeSignup = false
    val mayPostBeforeEmailVerified = false
    val doubleTypeEmailAddress = false
    val doubleTypePassword = false
    val begForEmailAddress = false
    val forumMainView = "latest"
    val forumTopicsSortButtons = "latest|top"
    val forumCategoryLinks = "categories"
    val forumTopicsLayout = TopicListLayout.Default
    val forumCategoriesLayout = CategoriesLayout.Default
    val showCategories = true
    val showTopicFilterButton = true
    val showTopicTypes = true
    val selectTopicType = true
    val numFirstPostsToReview = 1
    val numFirstPostsToApprove = 0
    val numFirstPostsToAllow = 0
    val headStylesHtml = ""
    val headScriptsHtml = ""
    val endOfBodyHtml = ""
    val headerHtml = ""
    val footerHtml = /* default CSS here: [5UK62W] */ o"""
      <footer><p>
        <a href="/-/terms-of-use" rel="nofollow">Terms of use</a>
        <a href="/-/privacy-policy" rel="nofollow">Privacy policy</a>
      </p></footer>"""
    val horizontalComments = false
    val socialLinksHtml = ""
    val logoUrlOrHtml = ""
    val orgDomain = ""
    val orgFullName = ""
    val orgShortName = ""
    var contribAgreement = ContribAgreement.CcBy3And4
    var contentLicense = ContentLicense.CcBySa4
    val googleUniversalAnalyticsTrackingId = ""
    val showExperimental = false
    val allowEmbeddingFrom = ""
    val htmlTagCssClasses = ""
    def numFlagsToHidePost = 3
    def cooldownMinutesAfterFlaggedHidden = 10
    def numFlagsToBlockNewUser = 3
    def numFlaggersToBlockNewUser = 3
    def notifyModsIfUserBlocked = true
    def regularMemberFlagWeight = 1.5f
    def coreMemberFlagWeight = 2.0f
  }
}


case class EffectiveSettings(
  editedSettingsChain: immutable.Seq[EditedSettings],
  default: AllSettings)
  extends AllSettings {

  def firstInChain[V](getField: (EditedSettings) => Option[V]): Option[V] = {
    for (editedSettings <- editedSettingsChain) {
      val anyValue = getField(editedSettings)
      if (anyValue.isDefined)
        return anyValue
    }
    None
  }

  def userMustBeAuthenticated: Boolean = firstInChain(_.userMustBeAuthenticated) getOrElse default.userMustBeAuthenticated
  def userMustBeApproved: Boolean = firstInChain(_.userMustBeApproved) getOrElse default.userMustBeApproved
  def inviteOnly: Boolean = firstInChain(_.inviteOnly) getOrElse default.inviteOnly
  def allowSignup: Boolean = firstInChain(_.allowSignup) getOrElse default.allowSignup
  def allowLocalSignup: Boolean = firstInChain(_.allowLocalSignup) getOrElse default.allowLocalSignup
  def allowGuestLogin: Boolean = firstInChain(_.allowGuestLogin) getOrElse default.allowGuestLogin
  def requireVerifiedEmail: Boolean = firstInChain(_.requireVerifiedEmail) getOrElse default.requireVerifiedEmail
  def mayComposeBeforeSignup: Boolean = firstInChain(_.mayComposeBeforeSignup) getOrElse default.mayComposeBeforeSignup
  def mayPostBeforeEmailVerified: Boolean = firstInChain(_.mayPostBeforeEmailVerified) getOrElse default.mayPostBeforeEmailVerified
  def doubleTypeEmailAddress: Boolean = firstInChain(_.doubleTypeEmailAddress) getOrElse default.doubleTypeEmailAddress
  def doubleTypePassword: Boolean = firstInChain(_.doubleTypePassword) getOrElse default.doubleTypePassword
  def begForEmailAddress: Boolean = firstInChain(_.begForEmailAddress) getOrElse default.begForEmailAddress
  def forumMainView: String = firstInChain(_.forumMainView) getOrElse default.forumMainView
  def forumTopicsSortButtons: String = firstInChain(_.forumTopicsSortButtons) getOrElse default.forumTopicsSortButtons
  def forumCategoryLinks: String = firstInChain(_.forumCategoryLinks) getOrElse default.forumCategoryLinks
  def forumTopicsLayout: TopicListLayout = firstInChain(_.forumTopicsLayout) getOrElse default.forumTopicsLayout
  def forumCategoriesLayout: CategoriesLayout = firstInChain(_.forumCategoriesLayout) getOrElse default.forumCategoriesLayout
  def showCategories: Boolean = firstInChain(_.showCategories) getOrElse default.showCategories
  def showTopicFilterButton: Boolean = firstInChain(_.showTopicFilterButton) getOrElse default.showTopicFilterButton
  def showTopicTypes: Boolean = firstInChain(_.showTopicTypes) getOrElse default.showTopicTypes
  def selectTopicType: Boolean = firstInChain(_.selectTopicType) getOrElse default.selectTopicType
  def numFirstPostsToReview: Int = firstInChain(_.numFirstPostsToReview) getOrElse default.numFirstPostsToReview
  def numFirstPostsToApprove: Int = firstInChain(_.numFirstPostsToApprove) getOrElse default.numFirstPostsToApprove
  def numFirstPostsToAllow: Int = firstInChain(_.numFirstPostsToAllow) getOrElse default.numFirstPostsToAllow
  def headStylesHtml: String = firstInChain(_.headStylesHtml) getOrElse default.headStylesHtml
  def headScriptsHtml: String = firstInChain(_.headScriptsHtml) getOrElse default.headScriptsHtml
  def endOfBodyHtml: String = firstInChain(_.endOfBodyHtml) getOrElse default.endOfBodyHtml
  def headerHtml: String = firstInChain(_.headerHtml) getOrElse default.headerHtml
  def footerHtml: String = firstInChain(_.footerHtml) getOrElse default.footerHtml
  def horizontalComments: Boolean = firstInChain(_.horizontalComments) getOrElse default.horizontalComments
  def socialLinksHtml: String = firstInChain(_.socialLinksHtml) getOrElse default.socialLinksHtml
  def logoUrlOrHtml: String = firstInChain(_.logoUrlOrHtml) getOrElse default.logoUrlOrHtml
  def orgDomain: String = firstInChain(_.orgDomain) getOrElse default.orgDomain
  def orgFullName: String = firstInChain(_.orgFullName) getOrElse default.orgFullName
  def orgShortName: String = firstInChain(_.orgShortName) getOrElse default.orgShortName
  def contribAgreement: ContribAgreement = firstInChain(_.contribAgreement) getOrElse default.contribAgreement
  def contentLicense: ContentLicense = firstInChain(_.contentLicense) getOrElse default.contentLicense
  def googleUniversalAnalyticsTrackingId: String = firstInChain(_.googleUniversalAnalyticsTrackingId) getOrElse default.googleUniversalAnalyticsTrackingId
  def showExperimental: Boolean = firstInChain(_.showExperimental) getOrElse default.showExperimental
  def allowEmbeddingFrom: String = firstInChain(_.allowEmbeddingFrom) getOrElse default.allowEmbeddingFrom
  def htmlTagCssClasses: String = firstInChain(_.htmlTagCssClasses) getOrElse default.htmlTagCssClasses

  def numFlagsToHidePost: Int = firstInChain(_.numFlagsToHidePost) getOrElse default.numFlagsToHidePost
  def cooldownMinutesAfterFlaggedHidden: Int = firstInChain(_.cooldownMinutesAfterFlaggedHidden) getOrElse default.cooldownMinutesAfterFlaggedHidden

  def numFlagsToBlockNewUser: Int = firstInChain(_.numFlagsToBlockNewUser) getOrElse default.numFlagsToBlockNewUser
  def numFlaggersToBlockNewUser: Int = firstInChain(_.numFlaggersToBlockNewUser) getOrElse default.numFlaggersToBlockNewUser
  def notifyModsIfUserBlocked: Boolean = firstInChain(_.notifyModsIfUserBlocked) getOrElse default.notifyModsIfUserBlocked

  def regularMemberFlagWeight: Float = firstInChain(_.regularMemberFlagWeight) getOrElse default.regularMemberFlagWeight
  def coreMemberFlagWeight: Float = firstInChain(_.coreMemberFlagWeight) getOrElse default.coreMemberFlagWeight


  def isGuestLoginAllowed =
    allowGuestLogin && !userMustBeAuthenticated && !userMustBeApproved &&
      !inviteOnly && allowSignup

  /** Finds any invalid setting value, or invalid settings configurations. */
  def findAnyError: Option[String] = {
    // Hmm ...
    None
  }

}


object Settings2 {

  def settingsToJson(editedSettings2: EditedSettings): JsObject = {
    val s = editedSettings2
    Json.obj(
      "userMustBeAuthenticated" -> JsBooleanOrNull(s.userMustBeAuthenticated),
      "userMustBeApproved" -> JsBooleanOrNull(s.userMustBeApproved),
      "inviteOnly" -> JsBooleanOrNull(s.inviteOnly),
      "allowSignup" -> JsBooleanOrNull(s.allowSignup),
      "allowLocalSignup" -> JsBooleanOrNull(s.allowLocalSignup),
      "allowGuestLogin" -> JsBooleanOrNull(s.allowGuestLogin),
      "requireVerifiedEmail" -> JsBooleanOrNull(s.requireVerifiedEmail),
      "mayComposeBeforeSignup" -> JsBooleanOrNull(s.mayComposeBeforeSignup),
      "mayPostBeforeEmailVerified" -> JsBooleanOrNull(s.mayPostBeforeEmailVerified),
      "doubleTypeEmailAddress" -> JsBooleanOrNull(s.doubleTypeEmailAddress),
      "doubleTypePassword" -> JsBooleanOrNull(s.doubleTypePassword),
      "begForEmailAddress" -> JsBooleanOrNull(s.begForEmailAddress),
      "forumMainView" -> JsStringOrNull(s.forumMainView),
      "forumTopicsSortButtons" -> JsStringOrNull(s.forumTopicsSortButtons),
      "forumCategoryLinks" -> JsStringOrNull(s.forumCategoryLinks),
      "forumTopicsLayout" -> JsNumberOrNull(s.forumTopicsLayout.map(_.toInt)),
      "forumCategoriesLayout" -> JsNumberOrNull(s.forumCategoriesLayout.map(_.toInt)),
      "showCategories" -> JsBooleanOrNull(s.showCategories),
      "showTopicFilterButton" -> JsBooleanOrNull(s.showTopicFilterButton),
      "showTopicTypes" -> JsBooleanOrNull(s.showTopicTypes),
      "selectTopicType" -> JsBooleanOrNull(s.selectTopicType),
      "numFirstPostsToReview" -> JsNumberOrNull(s.numFirstPostsToReview),
      "numFirstPostsToApprove" -> JsNumberOrNull(s.numFirstPostsToApprove),
      "numFirstPostsToAllow" -> JsNumberOrNull(s.numFirstPostsToAllow),
      "headStylesHtml" -> JsStringOrNull(s.headStylesHtml),
      "headScriptsHtml" -> JsStringOrNull(s.headScriptsHtml),
      "endOfBodyHtml" -> JsStringOrNull(s.endOfBodyHtml),
      "headerHtml" -> JsStringOrNull(s.headerHtml),
      "footerHtml" -> JsStringOrNull(s.footerHtml),
      "horizontalComments" -> JsBooleanOrNull(s.horizontalComments),
      "socialLinksHtml" -> JsStringOrNull(s.socialLinksHtml),
      "logoUrlOrHtml" -> JsStringOrNull(s.logoUrlOrHtml),
      "companyDomain" -> JsStringOrNull(s.orgDomain),
      "companyFullName" -> JsStringOrNull(s.orgFullName),
      "companyShortName" -> JsStringOrNull(s.orgShortName),
      "contribAgreement" -> JsNumberOrNull(s.contribAgreement.map(_.toInt)),
      "contentLicense" -> JsNumberOrNull(s.contentLicense.map(_.toInt)),
      "googleUniversalAnalyticsTrackingId" -> JsStringOrNull(s.googleUniversalAnalyticsTrackingId),
      "showExperimental" -> JsBooleanOrNull(s.showExperimental),
      "allowEmbeddingFrom" -> JsStringOrNull(s.allowEmbeddingFrom),
      "htmlTagCssClasses" -> JsStringOrNull(s.htmlTagCssClasses),
      "numFlagsToHidePost" -> JsNumberOrNull(s.numFlagsToHidePost),
      "cooldownMinutesAfterFlaggedHidden" -> JsNumberOrNull(s.cooldownMinutesAfterFlaggedHidden),
      "numFlagsToBlockNewUser" -> JsNumberOrNull(s.numFlagsToBlockNewUser),
      "numFlaggersToBlockNewUser" -> JsNumberOrNull(s.numFlaggersToBlockNewUser),
      "notifyModsIfUserBlocked" -> JsBooleanOrNull(s.notifyModsIfUserBlocked),
      "regularMemberFlagWeight" -> JsFloatOrNull(s.regularMemberFlagWeight),
      "coreMemberFlagWeight" -> JsFloatOrNull(s.coreMemberFlagWeight))
  }


  def d = AllSettings.Default

  def settingsToSaveFromJson(json: JsValue) = SettingsToSave(
    userMustBeAuthenticated = anyBool(json, "userMustBeAuthenticated", d.userMustBeAuthenticated),
    userMustBeApproved = anyBool(json, "userMustBeApproved", d.userMustBeApproved),
    inviteOnly = anyBool(json, "inviteOnly", d.inviteOnly),
    allowSignup = anyBool(json, "allowSignup", d.allowSignup),
    allowLocalSignup = anyBool(json, "allowLocalSignup", d.allowLocalSignup),
    allowGuestLogin = anyBool(json, "allowGuestLogin", d.allowGuestLogin),
    requireVerifiedEmail = anyBool(json, "requireVerifiedEmail", d.requireVerifiedEmail),
    mayComposeBeforeSignup = anyBool(json, "mayComposeBeforeSignup", d.mayComposeBeforeSignup),
    mayPostBeforeEmailVerified = anyBool(json, "mayPostBeforeEmailVerified", d.mayPostBeforeEmailVerified),
    doubleTypeEmailAddress = anyBool(json, "doubleTypeEmailAddress", d.doubleTypeEmailAddress),
    doubleTypePassword = anyBool(json, "doubleTypePassword", d.doubleTypePassword),
    begForEmailAddress = anyBool(json, "begForEmailAddress", d.begForEmailAddress),
    forumMainView = anyString(json, "forumMainView", d.forumMainView),
    forumTopicsSortButtons = anyString(json, "forumTopicsSortButtons", d.forumTopicsSortButtons),
    forumCategoryLinks = anyString(json, "forumCategoryLinks", d.forumCategoryLinks),
    forumTopicsLayout = anyInt(json, "forumTopicsLayout", d.forumTopicsLayout.toInt).map(_.flatMap(TopicListLayout.fromInt)),
    forumCategoriesLayout = anyInt(json, "forumCategoriesLayout", d.forumCategoriesLayout.toInt).map(_.flatMap(CategoriesLayout.fromInt)),
    showCategories = anyBool(json, "showCategories", d.showCategories),
    showTopicFilterButton = anyBool(json, "showTopicFilterButton", d.showTopicFilterButton),
    showTopicTypes = anyBool(json, "showTopicTypes", d.showTopicTypes),
    selectTopicType = anyBool(json, "selectTopicType", d.selectTopicType),
    numFirstPostsToReview = anyInt(json, "numFirstPostsToReview", d.numFirstPostsToReview),
    numFirstPostsToApprove = anyInt(json, "numFirstPostsToApprove", d.numFirstPostsToApprove),
    numFirstPostsToAllow = anyInt(json, "numFirstPostsToAllow", d.numFirstPostsToAllow),
    headStylesHtml = anyString(json, "headStylesHtml", d.headStylesHtml),
    headScriptsHtml = anyString(json, "headScriptsHtml", d.headScriptsHtml),
    endOfBodyHtml = anyString(json, "endOfBodyHtml", d.endOfBodyHtml),
    headerHtml = anyString(json, "headerHtml", d.headerHtml),
    footerHtml = anyString(json, "footerHtml", d.footerHtml),
    horizontalComments = anyBool(json, "horizontalComments", d.horizontalComments),
    socialLinksHtml = anyString(json, "socialLinksHtml", d.socialLinksHtml),
    logoUrlOrHtml = anyString(json, "logoUrlOrHtml", d.logoUrlOrHtml),
    orgDomain = anyString(json, "companyDomain", d.orgDomain),
    orgFullName = anyString(json, "companyFullName", d.orgFullName),
    orgShortName = anyString(json, "companyShortName", d.orgShortName),
    contribAgreement = anyInt(json, "contribAgreement", d.contribAgreement.toInt).map(_.map(
      ContribAgreement.fromInt(_) getOrElse throwBadRequest(
        "EsE5YK28", "Invalid contributors agreement"))),
    contentLicense = anyInt(json, "contentLicense", d.contentLicense.toInt).map(_.map(
      ContentLicense.fromInt(_) getOrElse throwBadRequest("EsE5YK28", "Invalid content license"))),
    googleUniversalAnalyticsTrackingId =
      anyString(json, "googleUniversalAnalyticsTrackingId", d.googleUniversalAnalyticsTrackingId),
    showExperimental = anyBool(json, "showExperimental", d.showExperimental),
    allowEmbeddingFrom = anyString(json, "allowEmbeddingFrom", d.allowEmbeddingFrom),
    htmlTagCssClasses = anyString(json, "htmlTagCssClasses", d.htmlTagCssClasses),
    numFlagsToHidePost = anyInt(json, "numFlagsToHidePost", d.numFlagsToHidePost),
    cooldownMinutesAfterFlaggedHidden = anyInt(json, "cooldownMinutesAfterFlaggedHidden", d.cooldownMinutesAfterFlaggedHidden  ),
    numFlagsToBlockNewUser = anyInt(json, "numFlagsToBlockNewUser", d.numFlagsToBlockNewUser  ),
    numFlaggersToBlockNewUser = anyInt(json, "numFlaggersToBlockNewUser", d.numFlaggersToBlockNewUser  ),
    notifyModsIfUserBlocked = anyBool(json, "notifyModsIfUserBlocked", d.notifyModsIfUserBlocked  ),
    regularMemberFlagWeight = anyFloat(json, "regularMemberFlagWeight", d.regularMemberFlagWeight  ),
    coreMemberFlagWeight = anyFloat(json, "coreMemberFlagWeight", d.coreMemberFlagWeight))


  private def anyString(json: JsValue, field: String, default: String): Option[Option[String]] =
    (json \ field).toOption.map {
      case s: JsString =>
        if (s.value == default) None else Some(s.value)
      case JsNull =>
        None
      case bad =>
        throwBadRequest("EsE5J4K02", s"'$field' is not a JsString, but a ${classNameOf(bad)}")
    }

  private def anyBool(json: JsValue, field: String, default: Boolean): Option[Option[Boolean]] =
    (json \ field).toOption.map {
      case b: JsBoolean =>
        if (b.value == default) None else Some(b.value)
      case JsNull =>
        None
      case bad =>
        throwBadRequest("EsE5J4K02", s"'$field' is not a JsBoolean, but a ${classNameOf(bad)}")
    }

  private def anyInt(json: JsValue, field: String, default: Int): Option[Option[Int]] =
    (json \ field).toOption.map {
      case n: JsNumber =>
        val value = n.value.toInt
        if (value == default) None else Some(value)
      case JsNull =>
        None
      case bad =>
        throwBadRequest("EsE5J4K02", s"'$field' is not a JsNumber, but a ${classNameOf(bad)}")
    }

  private def anyFloat(json: JsValue, field: String, default: Float): Option[Option[Float]] =
    (json \ field).toOption.map {
      case n: JsNumber =>
        val value = n.value.toFloat
        if (value == default) None else Some(value)
      case JsNull =>
        None
      case bad =>
        throwBadRequest("EdE7GK2Z8", s"'$field' is not a JsNumber, but a ${classNameOf(bad)}")
    }
}
