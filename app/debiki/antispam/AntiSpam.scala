/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
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

package debiki.antispam

import java.net.UnknownHostException

import com.debiki.core._
import com.debiki.core.Prelude._
import java.{net => jn}
import debiki.TextAndHtml
import debiki.DebikiHttp.throwForbidden
import play.{api => p}
import play.api.Play.current
import play.api.libs.ws._
import play.api.libs.json.Json
import requests.DebikiRequest
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.concurrent.Future.successful
import scala.util.{Success, Failure}



sealed abstract class SpamCheckResult { def isSpam: Boolean }
object SpamCheckResult {
  case object IsSpam extends SpamCheckResult { def isSpam = true }
  case object NotSpam extends SpamCheckResult { def isSpam = false }
}


object ApiKeyInvalidException extends QuickException
object CouldNotVerifyApiKeyException extends QuickException
object BadSpamCheckResponseException extends QuickException
object NoApiKeyException extends QuickException



/** Currently uses Akismet and Spamhaus and uribl and StopForumSpam. Could break out
  * the various services to different classes — but not right now, first find out
  * these spam checks seem to work or not.
  *
  * Test like so:
  * - Akismet: Post a title or page or comment with: '--viagra-test-123--' anywhere
  * - Spamhaus: Link to 'dbltest.com', see:
  * - uribl: Link to 'test.uribl.com' or 2.0.0.127, see: http://uribl.com/about.shtml
  * - StopForumSpam: sign up with email test@test.com
  *
  * Which domain block lists to use? Have a look here:
  *   https://www.intra2net.com/en/support/antispam/index.php_sort=accuracy_order=desc.html
  * the URIBL entries only ("uri block list", rather than e.g. email sender's ip address).
  *
  * Todo:
  *  Could send only new users' posts to Akistmet
  *  Read: https://meta.discourse.org/t/some-ideas-for-spam-control/10393/4
  *
  * More to test / use:
  * http://blogspam.net/faq/
  * https://www.mollom.com/pricing
  * https://cleantalk.org/price
  * http://sblam.com/ -- no, doesn't seem to support https
  * -- For IPs: (when signing up)
  * http :// www.spamhaus.org / lookup / 4
  * http :// www.spamcop.net / bl.shtml3
  * http :// www.projecthoneypot.org / search_ip.php4
  * http :// torstatus.blutmagie.de / tor_exit_query.php2
  *
  * Jeff Atwood @ Discourse's list of block lists:
  *   https://meta.discourse.org/t/questionable-account-checks-at-the-time-of-signup/19068/7
  *
  * And Discourse's built in spam control:
  * https://meta.discourse.org/t/some-ideas-for-spam-control/10393/4?u=kajmagnus
  * ----- (this text between --- is licensed under a Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported License, (c) Jeff Atwood)-----
  *   So far here is what we have:
  *
  *   new users are sandboxed in a few ways, notably they cannot post images, and can only have 2 URLs in any given post.
  *
  *   posting the same root URL over and over as a new user will lead to auto-hiding of all their posts with that URL, block of future posts with the same root URL, and a PM generated to them
  *
  *   if (x) new user posts are flagged by (y) unique users, all their posts are hidden, a PM generated to them, and they are prevented from posting
  *
  *   if an individual post reaches the community flagging threshold, it is hidden and a PM generated to the user. An edit will un-hide the post. Read more about flagging.
  *
  *   if the moderator deletes the spam user via the "delete spammer" button available from clicking "flag, spam" on one of their posts, both the email address and IP address are blacklisted and will not be accepted for new accounts again.
  *
  *   if a topic is started by a new user, and a different new user with the same IP address replies to that topic, both posts are automatically flagged as spam
  *
  *   accounts created in the last 24 hours can only create a maximum of 5 topics and 10 replies.
  *
  *   accounts created in the last 24 hours can only create new topics every 60 seconds and new replies every 30 seconds.
  *
  *   deleted spammers automatically blacklist the email and IP used. Emails are fuzzy matched.
  *
  *   you can temporarily disable all new account registration as needed via allow_user_registrations.
  *
  *   Trust level 3 users can hide spam with a single flag, versus the three (default setting) flags that are usually required. Read more about user trust levels.
  *-----------
  *
  * Thread safe.
  */
class AntiSpam {

  /*
  val request: dispatch.Req = dispatch.url("http://api.hostip.info/country.php").GET
  ... http://ipinfo.io/developers
  */

  private val TimeoutMs = 5000
  private val UserAgent = "Debiki/0.00.00 | Built-In/0.00.00"
  private val ContentType = "application/x-www-form-urlencoded"

  private val apiKeyIsValidPromise: Promise[Boolean] = Promise()

  private def encode(text: String) = jn.URLEncoder.encode(text, "UTF-8")

  // One key only, for now. Later on, one per site? + 1 global for non-commercial
  // low traffic newly created sites? + 1 global for commercial low traffic sites?
  // (and one per site for high traffic sites)
  private val anyApiKey: Option[String] = p.Play.configuration.getString("debiki.akismetApiKey")

  val AkismetAlwaysSpamName = "viagra-test-123"

  // Type the text '--viagra-test-123--' in a comment or title and it should be reported as
  // spam, always.
  val AlwaysSpamMagicText = "--viagra-test-123--"

  // All types: http://blog.akismet.com/2012/06/19/pro-tip-tell-us-your-comment_type/
  object AkismetSpamType {
    val Comment = "comment"
    val Pingback = "pingback"
    val Trackback = "trackback"
    val ForumPost = "forum-post" // forum posts and replies
    val BlogPost = "blog-post"
    val ContactForm = "contact-form" // contact forms, inquiry forms and the like
    val Signup = "signup" // account signup, registration or activation
    val Tweet = "tweet" // twitter messages
  }


  def start() {
    verifyAkismetApiKey()
  }


  private def verifyAkismetApiKey() {
    if (anyApiKey.isEmpty) {
      apiKeyIsValidPromise.failure(NoApiKeyException)
      return
    }

    // apparently port number not okay, -> invalid, and http://localhost -> invalid, too.
    val postData = "key=" + encode(anyApiKey.get) +
      "&blog=" + encode("http://localhost")

    // Without (some of) these headers, Akismet says the api key is invalid.
    val request: WSRequest =
      WS.url("https://rest.akismet.com/1.1/verify-key").withHeaders(
        play.api.http.HeaderNames.CONTENT_TYPE -> ContentType,
        play.api.http.HeaderNames.USER_AGENT -> UserAgent,
        play.api.http.HeaderNames.CONTENT_LENGTH -> postData.length.toString)

    request.post(postData) map { response: WSResponse =>
      val body = response.body
      val isValid = body.trim == "valid"
      if (!isValid) {
        val debugHelp = response.header("X-akismet-debug-help")
        p.Logger.error(o"""Akismet key is not valid [DwE4PKW0], response: '$body', debug help:
            $debugHelp""")
      }
      else {
        p.Logger.info(s"Akismet key is valid [DwM2KWS4]")
      }
      apiKeyIsValidPromise.success(isValid)
    }
  }


  def detectRegistrationSpam(request: DebikiRequest[_], name: String, email: String)
        : Future[Option[String]] = {

    val stopForumSpamFuture = checkViaStopForumSpam(request, name, email)

    val akismetBody = makeAkismetRequestBody(AkismetSpamType.Signup, request,
      anyName = Some(name), anyEmail = Some(email))
    val akismetFuture = checkViaAkismet(akismetBody)

    aggregateResults(Seq(stopForumSpamFuture, akismetFuture))
  }


  def detectNewPageSpam(request: DebikiRequest[_], titleTextAndHtml: TextAndHtml,
        bodyTextAndHtml: TextAndHtml): Future[Option[String]] = {

    throwForbiddenIfLooksSpammy(request, bodyTextAndHtml)

    val allTextAndHtml = titleTextAndHtml append bodyTextAndHtml
    val domainFutures = checkDomainBlockLists(allTextAndHtml)
    // COULD postpone the Akismet check until after the domain check has been done? [5KGUF2]

    // Don't send the whole text, because of privacy issues. Send the links only.
    // Or yes do that?  dupl question [7KECW2]
    val payload = makeAkismetRequestBody(AkismetSpamType.ForumPost, request,
      text = Some(allTextAndHtml.safeHtml))//htmlLinksOnePerLine))
    val akismetFuture = checkViaAkismet(payload)

    aggregateResults(domainFutures :+ akismetFuture)
  }


  /** Returns a future that eventually succeeds with Some(reason-why-is-spam-message) if is spam,
    * or None if not spam, or fails if we couldn't
    * find out if it's spam or not, e.g. because Akismet is offline or if
    * the API key has expired.
    */
  def detectPostSpam(request: DebikiRequest[_], pageId: PageId, textAndHtml: TextAndHtml)
        : Future[Option[String]] = {

    throwForbiddenIfLooksSpammy(request, textAndHtml)

    val domainFutures = checkDomainBlockLists(textAndHtml)
    // COULD postpone the Akismet check until after the domain check has been done? [5KGUF2]
    // So won't have to pay for Akismet requests, if the site is commercial.
    // Currently the Spamhaus test happens synchronously already though.

    // Don't send the whole text, because of privacy issues. Send the links only.
    // Or yes do that?  dupl question [7KECW2]
    val payload = makeAkismetRequestBody(AkismetSpamType.ForumPost, request,
      text = Some(textAndHtml.safeHtml))//htmlLinksOnePerLine))
    val akismetFuture = checkViaAkismet(payload)

    aggregateResults(domainFutures :+ akismetFuture)
  }


  /** If spam found, returns a random is-spam result, and logs details about the spam stuff
    * and which spam check services think it's spam.
    */
  def aggregateResults(futures: Seq[Future[Option[String]]]): Future[Option[String]] = {
    /* Could:
    implicit class FutureHelper[T](f: Future[T]) extends AnyVal{
      import akka.pattern.after
      def orDefault(t: Timeout, default: => T)(implicit system: ActorSystem): Future[T] = {
        val delayed = after(t.duration, system.scheduler)(Future.successful(default))
        Future firstCompletedOf Seq(f, delayed)
      }
    }
    see: http://stackoverflow.com/a/17467769/694469  */

    Future.sequence(futures) map { results: Seq[Option[String]] =>
      val spamResults: Seq[String] = results.filter(_.isDefined).map(_.get)
      if (spamResults.nonEmpty) {
        // TODO: log ip, email, name, text + one line for each spam detection, but for now:
        spamResults foreach { spamReason =>
          p.Logger(s"Spam (?) detected [DwM2KPF8]: $spamReason")
        }
      }
      spamResults.headOption
    }
  }


  /** Does some simple tests to try to fend off spam.
    */
  def throwForbiddenIfLooksSpammy(request: DebikiRequest[_], textAndHtml: TextAndHtml) {
    def throwIfTooManyLinks(maxNumLinks: Int) {
      if (textAndHtml.linkDomains.length > maxNumLinks)
        throwForbidden("DwE4KFY2", o"""Your text includes more than $maxNumLinks links —
           that makes me nervous about spam. Can you please remove some links?""")
    }
    if (request.isStaff) {
      throwIfTooManyLinks(15)
    }
    else if (request.isAuthenticated) {
      throwIfTooManyLinks(7)
    }
    else {
      throwIfTooManyLinks(3)
    }
  }


  def checkViaStopForumSpam(request: DebikiRequest[_], name: String, email: String)
        : Future[Option[String]] = {
    // StopForumSpam doesn't support ipv6.
    // See: https://www.stopforumspam.com/forum/viewtopic.php?id=6392
    val anyIpParam =
      if (request.ip.startsWith("[")) ""
      else  "&ip=" + encode(request.ip)
    val encodedEmail = encode(email)
    // StopForumSpam has a self signed https cert, and Java then dies with a
    // java.security.cert.CertificateException. So use http for now — later, add the self
    // signed cert to the Java cert store?
    // See: https://www.playframework.com/documentation/2.4.x/WSQuickStart
    // Or just wait for a while:
    //   "I've got the .com and .org certs now and will get them plumbed in shortly."
    // (the forum thread above, June 2015)
    // Hmm supposedly already https:
    //  "it took some time but the site, including the API, is now available with fully signed SSL"
    //   http://www.stopforumspam.com/forum/viewtopic.php?id=6345
    // a few days ago. I'm still getting a cert error though. Works in Chrome, not Java,
    // I suppose Java's cert store is old and out of date somehow?
    // Use http for now:
    WS.url(s"http://www.stopforumspam.com/api?email=$encodedEmail$anyIpParam&f=json").get()
      .map(handleStopForumSpamResponse)
      .recover({
        case ex: Exception =>
          p.Logger.warn(s"Error querying api.stopforumspam.org [DwE2PWC7]", ex)
          None
      })
  }


  def handleStopForumSpamResponse(response: WSResponse): Option[String] = {
    // Ask: https://api.stopforumspam.org/api?ip=91.186.18.61&email=g2fsehis5e@mail.ru&f=json
    // Response:
    //    {"success":1,"email":{"frequency":0,"appears":0},"ip":{"frequency":0,"appears":0}}

    def prettyJson = s"\n--------\n${response.body}\n--------"
    val json =
      try Json.parse(response.body)
      catch {
        case ex: Exception =>
          p.Logger.warn(s"Bad JSON from api.stopforumspam.org: $prettyJson", ex)
          return None
      }

    if ((json \ "success").asOpt[Int] != Some(1)) {
      p.Logger.warn(s"api.stopforumspam.org returned success != 1: $prettyJson")
      return None
    }

    val ipJson = json \ "ip"
    (ipJson \ "frequency").asOpt[Int] match {
      case None =>
        p.Logger.warn(s"api.stopforumspam.org didn't send back any ip.frequency: $prettyJson")
      case Some(frequency) =>
        if (frequency >= 1)
          return Some(o"""Stop Forum Spam thinks a spammer lives at your ip address
              (frequency = $frequency). You can instead try from another location,
              e.g. another apartment or house, so that you'll get a different
              ip address [DwE7JYK2]""")
    }

    val emailJson = json \ "email"
    (emailJson \ "frequency").asOpt[Int] match {
      case None =>
        p.Logger.warn(s"api.stopforumspam.org didn't send back any email.frequency: $prettyJson")
      case Some(frequency) =>
        if (frequency >= 1)
          return Some(o"""Stop Forum Spam thinks the person with that email address is a spammer
              (frequency = $frequency). Do you want to try with a different address? [DwE5KGP2]""")
    }

    None
  }


  def checkDomainBlockLists(textAndHtml: TextAndHtml): Seq[Future[Option[String]]] = {
    /* WHY
    scala> java.net.InetAddress.getAllByName("dbltest.com.dbl.spamhaus.org");  <-- fails the frist time
    java.net.UnknownHostException: dbltest.com.dbl.spamhaus.org: unknown error

    scala> java.net.InetAddress.getAllByName("dbltest.com.dbl.spamhaus.org");   <-- then works
    res9: Array[java.net.InetAddress] = Array(dbltest.com.dbl.spamhaus.org/127.0.1.2)
    asked here:
    http://stackoverflow.com/questions/32983129/why-does-inetaddress-getallbyname-fail-once-then-succeed
    */

    // Consider this spam if there's any link with a raw ip address.
    textAndHtml.linkAddresses.headOption foreach { address =>
      return Seq(successful(Some(o"""You have typed a link with this raw IP number: $address.
          Please remove it; currently I am afraid that links with IP numbers tend
          to be spam. [DwE4PUM2]""")))
    }

    val domainsToCheck = textAndHtml.linkDomains // TODO: scrubDomains(textAndHtml.linkDomains)...
    // ...unless removing hostnames and sub domains, the block list lookup might fail —
    // block lists tend to expect requests with sub domains stripped.
    // Read here; http://www.surbl.org/guidelines  about how to extract the base (registered)
    // domain from an uri.

    val spamhausFuture = queryDomainBlockList("dbl.spamhaus.org", "Spamhaus", domainsToCheck)
    val uriblFuture = queryDomainBlockList("multi.uribl.com", "uribl.com", domainsToCheck)
    Seq(spamhausFuture, uriblFuture)
  }


  def queryDomainBlockList(blockListDomain: String, blockListName: String,
        domainsToCheck: Seq[String]): Future[Option[String]] = {
    // We ask the list if a domain is spam, by querying the DNS system: prefix the
    // suspect domain, reversed, to Spamhaus (e.g. 'dbl.spamhaus.org'), and if any ip
    // is returned, then the domain is in the block list.
    domainsToCheck foreach { domain =>
      val query = s"$domain.$blockListDomain"
      try {
        // COULD do this asynchronously instead, or use Scala's blocking { ... } ?
        val addresses = java.net.InetAddress.getAllByName(query)
        if (addresses.nonEmpty)
          return successful(Some(o"""$blockListName thinks links to this domain are spam:
             '$domain'. And you have typed a link to that domain. You could 1) remove the link,
             or 2) change it to plain text and insert spaces in the domain name — then
             it won't be a real link. [DwE5GKF2]
             """))
      }
      catch {
        case ex: UnknownHostException =>
        // Fine, not in the block list.
      }
    }
    successful(None)
  }


  def checkViaAkismet(requestBody: String): Future[Option[String]] = {
    val promise = Promise[Boolean]()
    apiKeyIsValidPromise.future onComplete {
      case Success(true) =>
        sendCheckIsSpamRequest(apiKey = anyApiKey.get, payload = requestBody, promise)
      /*
      case Success(false) =>
        promise.failure(ApiKeyInvalidException) ? Or just ignore the spam check, for now
      */
      case _ =>
        // Skip the spam check. We've logged an error already about the invalid key.
        promise.success(false)
    }
    promise.future map { isSpam =>
      if (isSpam) Some("Akismet thinks this is spam [DwE7JUK2]")
      else None
    }
  }


  private def sendCheckIsSpamRequest(apiKey: String, payload: String, promise: Promise[Boolean]) {
    val request: WSRequest =
      WS.url(s"https://$apiKey.rest.akismet.com/1.1/comment-check").withHeaders(
        play.api.http.HeaderNames.CONTENT_TYPE -> ContentType,
        play.api.http.HeaderNames.USER_AGENT -> UserAgent,
        play.api.http.HeaderNames.CONTENT_LENGTH -> payload.length.toString)

    request.post(payload) map { response: WSResponse =>
      val body = response.body
      body.trim match {
        case "true" =>
          SECURITY // COULD remember ip and email, check manually? block?
          p.Logger.debug(s"Akismet found spam: $payload")
          promise.success(true)
        case "false" =>
          p.Logger.debug(s"Akismet says not spam: $payload")
          promise.success(false)
        case badResponse =>
          val debugHelp = response.header("X-akismet-debug-help")
          p.Logger.error(o"""Akismet error: Weird spam check response: '$badResponse',
               debug help: $debugHelp""")
          promise.failure(BadSpamCheckResponseException)
      }
    }
  }


  private def makeAkismetRequestBody(tyype: String, request: DebikiRequest[_],
      pageId: Option[PageId] = None, text: Option[String] = None, anyName: Option[String] = None,
      anyEmail: Option[String] = None): String = {

    if (anyApiKey.isEmpty)
      return "No Akismet API key configured [DwM4GLU8]"

    val body = new StringBuilder()
    def theUser = request.theUser

    // Documentation: http://akismet.com/development/api/#comment-check

    // (required) The front page or home URL of the instance making the request.
    // For a blog or wiki this would be the front page. Note: Must be
    // a full URI, including http://.
    body.append("blog=" + encode("http://localhost")) // debikiRequest.origin))

    // (required) IP address of the comment submitter.
    body.append("&user_ip=" + encode("1.22.26.83"))//debikiRequest.ip))

    // (required) User agent string of the web browser submitting the comment - typically
    // the HTTP_USER_AGENT cgi variable. Not to be confused with the user agent
    // of your Akismet library.
    val browserUserAgent = request.headers.get("User-Agent") getOrElse "Unknown"
    body.append("&user_agent=" + encode(browserUserAgent))

    // The content of the HTTP_REFERER header should be sent here.
    request.headers.get("referer") foreach { referer =>
      body.append("&referrer=" + encode(referer)) // should be 2 'r' yes
    }

    // The permanent location of the entry the comment was submitted to.
    pageId foreach { id =>
      body.append("&permalink=" + encode(request.origin + "/-" + id))
    }

    // May be blank, comment, trackback, pingback, or a made up value like "registration".
    // It's important to send an appropriate value, and this is further explained here.
    body.append("&comment_type=" + tyype)

    // Name submitted with the comment.
    val theName =
      if (text.exists(_ contains AlwaysSpamMagicText)) {
        AkismetAlwaysSpamName
      }
      else anyName getOrElse {
        // Check both the username and the full name, by combining them.
        theUser.username.map(_ + " ").getOrElse("") + theUser.displayName
      }
    body.append("&comment_author=" + encode(theName))

    // Email address submitted with the comment.
    val theEmail = anyEmail getOrElse theUser.email
    body.append("&comment_author_email=" + encode(theEmail)) // TODO email inclusion configurable

    // The content that was submitted.
    text foreach { t =>
      if (t.nonEmpty)
        body.append("&comment_content=" + encode(t))
    }

    // URL submitted with comment.
    //comment_author_url (not supported)

    // The UTC timestamp of the creation of the comment, in ISO 8601 format. May be
    // omitted if the comment is sent to the API at the time it is created.
    //comment_date_gmt (omitted)

    // The UTC timestamp of the publication time for the post, page or thread
    // on which the comment was posted.
    //comment_post_modified_gmt  -- COULD include, need to load page

    // Indicates the language(s) in use on the blog or site, in ISO 639-1 format,
    // comma-separated. A site with articles in English and French might use "en, fr_ca".
    //blog_lang

    // The character encoding for the form values included in comment_* parameters,
    // such as "UTF-8" or "ISO-8859-1".
    body.append("&blog_charset=UTF-8")

    // The user role of the user who submitted the comment. This is an optional parameter.
    // If you set it to "administrator", Akismet will always return false.
    //user_role

    // This is an optional parameter. You can use it when submitting test queries to Akismet.
    body.append("&is_test" + true) // for now, later:  (if (p.Play.isProd) false else true))

    body.toString()
  }
}


object AntiSpam {

  def throwForbiddenIfSpam(isSpamReason: Option[String], errorCode: String) {
    isSpamReason foreach { reason =>
      throwForbidden(
        errorCode, "Sorry but our spam detection system thinks this is spam. Details: " + reason)
    }
  }

}

