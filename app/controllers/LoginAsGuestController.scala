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

package controllers

import actions.ApiActions.AsyncPostJsonAction
import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.DebikiHttp._
import debiki.antispam.AntiSpam
import java.{util => ju}
import play.api._
import play.api.mvc.{Action => _, _}
import play.api.libs.json.JsObject
import scala.concurrent.ExecutionContext.Implicits.global


/** Logs in guest users.
  */
object LoginAsGuestController extends mvc.Controller {


  def loginGuest = AsyncPostJsonAction(RateLimits.Login, maxLength = 1000) { request =>
    // For now, until I've built more rate limiting stuff and security features:
    if (request.siteId != KajMagnusSiteId)
      throwForbidden("DwE5KEGP8", "Guest login disabled")

    val json = request.body.as[JsObject]
    val name = (json \ "name").as[String].trim
    val email = (json \ "email").as[String].trim

    val settings = request.dao.loadWholeSiteSettings()
    if (!settings.guestLoginAllowed)
      throwForbidden("DwE4K5FW2", "Guest login disabled; you cannot login as guest here")
    if (User nameIsWeird name)
      throwForbidden("DwE82CW19", "Weird name. Please specify a name with no weird characters")
    if (name.isEmpty)
      throwForbidden("DwE872Y90", "Please fill in your name")
    if (email.nonEmpty && User.emailIsWeird(email))
      throwForbidden("DwE04HK83", "Weird email. Please use a real email address")

    Globals.antiSpam.detectRegistrationSpam(request, name = name, email = email) map {
        isSpamReason =>
      AntiSpam.throwForbiddenIfSpam(isSpamReason, "DwE5KJU3")

      // why? we already have request.siteId & .dao
      val tenantId = DebikiHttp.lookupTenantIdOrThrow(request, Globals.systemDao)

      val loginAttempt = GuestLoginAttempt(
        ip = request.ip,
        date = new ju.Date,
        name = name,
        email = email,
        guestCookie = request.theBrowserIdData.idCookie)

      val guestUser = Globals.siteDao(tenantId).loginAsGuest(loginAttempt)

      val (_, _, sidAndXsrfCookies) = Xsrf.newSidAndXsrf(guestUser)

      // Could include a <a href=last-page>Okay</a> link, see the
      // Logout dialog below. Only needed if javascript disabled though,
      // otherwise a javascript welcome dialog is shown instead.
      Ok.withCookies(sidAndXsrfCookies: _*)
    }
  }

}
