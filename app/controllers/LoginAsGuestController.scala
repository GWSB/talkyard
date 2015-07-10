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

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.DebikiHttp._
import java.{util => ju}
import play.api._
import play.api.mvc.{Action => _, _}
import requests.DebikiRequest
import actions.ApiActions.PostJsonAction
import play.api.libs.json.JsObject


/** Logs in guest users.
  */
object LoginAsGuestController extends mvc.Controller {


  def loginGuest = PostJsonAction(RateLimits.Login, maxLength = 1000) { request =>
    // For now, until I've built more rate limiting stuff and security features:
    if (request.siteId != KajMagnusSiteId && false)
      throwForbidden("DwE5KEGP8", "Guest login disabled")

    val json = request.body.as[JsObject]
    val name = (json \ "name").as[String]
    val email = (json \ "email").as[String]

    def failLogin(errCode: String, summary: String, details: String) =
      throwForbiddenDialog(errCode, "Login Failed", summary, details)

    val settings = request.dao.loadWholeSiteSettings()
    if (!settings.guestLoginAllowed)
      failLogin("DwE4KFW2", "Guest login disabled", "You cannot login as guest at this site")
    if (User nameIsWeird name)
      failLogin("DwE82ckWE19", "Weird name.",
        "Please specify another name, with no weird characters.")
    if (name isEmpty)
      failLogin("DwE873k2e90", "No name specified.",
        "Please fill in your name.")
    if (email.nonEmpty && User.emailIsWeird(email))
      failLogin("DwE0432hrsk23", "Weird email.",
        "Please specify an email address with no weird characters.")

    val addr = request.ip
    val tenantId = DebikiHttp.lookupTenantIdOrThrow(request, Globals.systemDao)

    val loginAttempt = GuestLoginAttempt(
      ip = addr,
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
