/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
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

import akka.actor._
import akka.pattern.gracefulStop
import com.debiki.core._
import com.debiki.core.Prelude._
import com.debiki.dao.rdb.{RdbDaoFactory, Rdb}
import debiki.dao.{SystemDao, SiteDao, CachingSiteDaoFactory, CachingSystemDao}
import debiki.dao.migrations.ScalaBasedMigrations
import debiki.antispam.AntiSpam
//import com.twitter.ostrich.stats.Stats
//import com.twitter.ostrich.{admin => toa}
import java.{lang => jl}
import play.{api => p}
import play.api.libs.concurrent.Akka
import play.api.Play
import play.api.Play.current
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.matching.Regex


object Globals extends Globals



/** App server startup and shutdown hooks, plus global stuff like
  * the systemDao and tenantDao:s and an email actor.
  *
  * It's a class, so it can be tweaked during unit testing.
  */
class Globals {

  def isInitialized = _state ne null

  private var _state: State = null

  private def state: State = {
    assert(_state ne null, "No Globals.State created, please call onServerStartup()")
    _state
  }


  val applicationSecret =
    Play.configuration.getString("application.secret").getOrDie(
      "application.secret config value missing [DwE75FX0]")


  def systemDao = state.systemDao


  def siteDao(siteId: SiteId): SiteDao =
    state.siteDaoFactory.newSiteDao(siteId)


  def antiSpam: AntiSpam = state.antiSpam


  def sendEmail(email: Email, websiteId: String) {
    state.mailerActorRef ! (email, websiteId)
  }

  /** Either exactly all sites uses HTTPS, or all of them use HTTP.
    * A mixed configuration makes little sense I think:
    * 1) On the public internet, obviously HTTPS should be used, always.
    * 2) On an intranet, HTTP might be okay. And HTTPS. But not a combination of HTTP and HTTPS:
    *   a) What about an intranet with some sites using HTTPS and some using HTTPS?
    *     Then the organization would setup a Certificate Authority, install
    *     certs in the members' browsers, but then uses them only sometimes, for some
    *     sites? Why? That seems weird. Supporting this seems like not-well-spent-time.
    *   b) What about sites that are accessible over HTTP on the intranet and HTTPS
    *     on the public Internet? Then email links will break (they'll use either http or https).
    *   c) What about some sites accessible only over HTTP on an intranet,
    *     and others accessible only on the public Internet over HTTPS?
    *     Then some firewall has to block access to the HTTP sites from the public internet.
    *     Seems like a risky and unusual configuration. Not well spent time.
    *     They might as well use two servers instead, one for the public internet,
    *     one internally?
    * -->
    *  Either HTTP for all sites (assuming a trusted intranet), or HTTPS for all sites.
    */
  def secure = state.secure

  def scheme = state.scheme


  def port = state.port

  def colonPort =
    if (secure && port == 443) ""
    else if (!secure && port == 80) ""
    else s":$port"


  def originOf(site: Site): Option[String] = site.canonicalHost.map(originOf)
  def originOf(host: SiteHost): String = originOf(host.hostname)
  def originOf(hostOrHostname: String): String = {
    val (hostname, port) = hostOrHostname.span(_ != ':')
    dieIf(port.nonEmpty && port != colonPort, "DwE47SK2", s"Bad port: '$hostOrHostname'")
    s"$scheme://$hostname$colonPort"
  }
  def originOf(request: p.mvc.Request[_]): String = s"$scheme://${request.host}"


  def baseDomainWithPort = state.baseDomainWithPort
  def baseDomainNoPort = state.baseDomainNoPort


  def firstSiteHostname = state.firstSiteHostname

  /** New sites may be created only from this hostname. */
  def anyCreateSiteHostname = state.anyCreateSiteHostname


  def poweredBy = s"$scheme://www.debiki.com"


  /** If a hostname matches this pattern, the site id can be extracted directly from the url.
    */
  def siteByIdHostnameRegex = state.siteByIdHostnameRegex

  def SiteByIdHostnamePrefix = "site-"

  def siteByIdOrigin(siteId: String) =
    s"$scheme://$SiteByIdHostnamePrefix$siteId.$baseDomainWithPort"


  /** The Twitter Ostrich admin service, listens on port 9100. */
  /*
  private val _ostrichAdminService = new toa.AdminHttpService(9100, 20, Stats,
    new toa.RuntimeEnvironment(getClass))
   */


  def onServerStartup(app: p.Application) {
    if (_state ne null)
      throw new jl.IllegalStateException(o"""Server already running, was it not properly
        shut down last time? Please hit CTRL+C to kill it. [DwE83KJ9]""")

    p.Logger.info("Search disabled, see debiki-dao-rdb [DwM4KEWKB2]")

    _state = new State

    // The render engines might be needed by some Java evolutions applied below.
    debiki.ReactRenderer.startCreatingRenderEngines()

    state.systemDao.applyEvolutions()

    // For now, disable in dev mode — because of the port conflict that
    // causes an error on reload and restart, see below (search for "conflict").
    /*
    _ostrichAdminService.start()
    Logger.info("Twitter Ostrich listening on port "+
       _ostrichAdminService.address.getPort)
     */
  }


  def onServerShutdown(app: p.Application) {
    // Play.start() first calls Play.stop(), so:
    if (_state eq null)
      return

    p.Logger.info("Shutting down, gracefully...")
    // Shutdown the notifier before the mailer, so no notifications are lost
    // because there was no mailer that could send them.
    shutdownActorAndWait(state.notifierActorRef)
    shutdownActorAndWait(state.mailerActorRef)
    state.dbDaoFactory.shutdown()
    _state = null

    //_ostrichAdminService.shutdown()

    // COULD stop Twitter Ostrich on reload too -- currently there's a
    // port conflict on reload.
    // See: <https://groups.google.com/
    //    forum/?fromgroups#!topic/play-framework/g6uixxX2BVw>
    // "There is an Actor system reserved for the application code that is
    // automatically shutdown when the application restart. You can access it
    // in:  play.api.libs.Akka.system"

  }


  private def shutdownActorAndWait(actorRef: ActorRef): Boolean = {
    val future = gracefulStop(actorRef, state.ShutdownTimeout)
    val stopped = Await.result(future, state.ShutdownTimeout)
    stopped
  }


  private class State {

    val ShutdownTimeout = 30 seconds

    val antiSpam = new AntiSpam()
    antiSpam.start()

    val dbDaoFactory = new RdbDaoFactory(
      makeDataSource(), ScalaBasedMigrations, Akka.system, debiki.ReactRenderer,
      anyFullTextSearchDbPath, Play.isTest, fastStartSkipSearch = fastStartSkipSearch)

    val siteDaoFactory = new CachingSiteDaoFactory(dbDaoFactory)

    val mailerActorRef = Mailer.startNewActor(Akka.system, siteDaoFactory)

    val notifierActorRef = Notifier.startNewActor(Akka.system, systemDao, siteDaoFactory)

    def systemDao: SystemDao = new CachingSystemDao(dbDaoFactory.systemDbDao)

    private def fastStartSkipSearch =
      Play.configuration.getBoolean("crazyFastStartSkipSearch") getOrElse false

    private def anyFullTextSearchDbPath =
      Play.configuration.getString("fullTextSearchDb.dataPath")

    val secure = Play.configuration.getBoolean("debiki.secure") getOrElse {
      p.Logger.info("Config value 'debiki.secure' missing; defaulting to true. [DwM3KEF2]")
      true
    }

    def scheme = if (secure) "https" else "http"

    val port: Int = {
      if (Play.isTest) {
        // Not on classpath: play.api.test.Helpers.testServerPort
        // Instead, duplicate its implementation here:
        sys.props.get("testserver.port").map(_.toInt) getOrElse 19001
      }
      else if (Play.isDev) {
        sys.props.get(s"$scheme.port").map(_.toInt) getOrElse 9000
      }
      else {
        Play.configuration.getInt("debiki.port") getOrElse {
          if (secure) 443
          else 80
        }
      }
    }

    val baseDomainNoPort =
      if (Play.isTest) "localhost"
      else Play.configuration.getString("debiki.baseDomain") getOrElse "localhost"

    val baseDomainWithPort =
      if (secure && port == 443) baseDomainNoPort
      else if (!secure && port == 80) baseDomainNoPort
      else s"$baseDomainNoPort:$port"


    /** The hostname of the site created by default when setting up a new server. */
    val firstSiteHostname = Play.configuration.getString("debiki.hostname")

    if (firstSiteHostname.exists(_ contains ':'))
      p.Logger.error("Config value debiki.hostname contains ':' [DwE4KUWF7]")

    val anyCreateSiteHostname = Play.configuration.getString("debiki.createSiteHostname")

    // The hostname must be directly below the base domain, otherwise
    // wildcard HTTPS certificates won't work: they cover 1 level below the
    // base domain only, e.g. host.example.com but not sub.host.example.com,
    // if the cert was issued for *.example.com.
    val siteByIdHostnameRegex: Regex =
      s"""^$SiteByIdHostnamePrefix(.*)\\.$baseDomainNoPort$$""".r

    private def makeDataSource() = {
      //val dataSourceName = if (Play.isTest) "test" else "default"
      //val dataSource = p.db.DB.getDataSource(dataSourceName)
      val dataSource = Debiki.getPostgreSqlDataSource()
      val db = new Rdb(dataSource)

      // Log which database we've connected to.
      //val boneDataSource = dataSource.asInstanceOf[com.jolbox.bonecp.BoneCPDataSource]
      //p.Logger.info(o"""Connected to database:
      //  ${boneDataSource.getJdbcUrl} as user ${boneDataSource.getUsername}.""")
      db
    }
  }

}

