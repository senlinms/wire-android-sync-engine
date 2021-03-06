/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.push

import android.net.Uri
import com.koushikdutta.async.http.WebSocket
import com.waz.api.NetworkMode
import com.waz.api.impl.ErrorResponse
import com.waz.content.UserPreferences.LastStableNotification
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.{EventPipeline, NetworkModeService, ZmsLifeCycle}
import com.waz.specs.AndroidFreeSpec
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.PushNotificationsClient.LoadNotificationsResponse
import com.waz.sync.client.{PushNotification, PushNotificationsClient}
import com.waz.testutils.{TestBackoff, TestGlobalPreferences, TestUserPreferences}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.events.Signal
import com.waz.utils.wrappers.Context
import com.waz.znet._
import org.json.JSONObject

import scala.concurrent.Future
import scala.concurrent.duration._

class PushServiceSpec extends AndroidFreeSpec { test =>

  val wsConnected = Signal(false)

  val account = AccountId()
  val clientId  = ClientId()
  val context = mock[Context]
  val pipeline = mock[EventPipeline]
  val websocket = mock[WebSocketClientService]
  val receivedPushes = mock[ReceivedPushStorage]
  val znet = mock[ZNetClient]
  val sync = mock[SyncServiceHandle]
  val prefs     = new TestGlobalPreferences
  val userPrefs = new TestUserPreferences
  val network = mock[NetworkModeService]
  val lifeCycle = mock[ZmsLifeCycle]

  implicit val ctx = Threading.Background
  val client = mock[PushNotificationsClient]

  val wsClient = Signal(Option.empty[WebSocketClient])
  val uiActive = Signal(true)

  (websocket.connected _).expects().anyNumberOfTimes().returning(wsConnected)
  (websocket.client _).expects().anyNumberOfTimes().returning(wsClient)

  val processedEvents = Signal(Set.empty[Event]).disableAutowiring()

  (pipeline.apply _).expects(*).anyNumberOfTimes().onCall { ev: Traversable[Event] =>
    processedEvents.mutate(_ ++ ev.toSet)
    Future.successful({})
  }
  (lifeCycle.uiActive _).expects().anyNumberOfTimes().returning(uiActive)

  val syncPerformed = Signal(0)
  (sync.performFullSync _).expects().anyNumberOfTimes().onCall { _ =>
    Future.successful { syncPerformed.mutate { _ + 1 } }
  }

  val networkMode = Signal(NetworkMode._4G)
  (network.networkMode _).expects().anyNumberOfTimes.returning(networkMode)
  (network.getNetworkOperatorName _).expects().anyNumberOfTimes().returning("Network operator")

  (receivedPushes.list _).expects().anyNumberOfTimes().returning(Future.successful(Seq.empty))
  (receivedPushes.removeAll _).expects(*).anyNumberOfTimes().returning(Future.successful({}))

  def getService = new PushServiceImpl(context, userPrefs, prefs, receivedPushes, client, clientId, account, pipeline, websocket, network, lifeCycle, sync) {
    override lazy val wakeLock: WakeLock = new FakeLock
  }

  val ws = new WebSocketClient(context, AccountId(), mock[AsyncClient], Uri.parse(""), mock[AccessTokenProvider]) {
    override lazy val wakeLock: WakeLock = new FakeLock

    override def connect(): CancellableFuture[WebSocket] = CancellableFuture.successful(mock[WebSocket])
  }

  private lazy val idPref = userPrefs.preference(LastStableNotification)

  override def beforeEach(): Unit = {
    super.beforeEach()
    idPref := Some(lastId)
    result(idPref.signal.filter(_.contains(lastId)).head)
  }

  feature("/notifications") {
    scenario("get a notification from cloud messaging push") {
      wsClient ! Some(ws)
      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(pushNotification), hasMore = false, None) ))
      )

      getService.cloudPushNotificationsToProcess ! Set(pushNotification.id)
      result(idPref.signal.filter(_.contains(pushNotification.id)).head)
    }

    scenario("fetch last notification when there is no local last notification id") {

      idPref := None
      result(idPref.signal.filter(_.isEmpty).head)

      (client.loadNotifications _).expects(*, *).once().returning(
          CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(pushNotification), hasMore = false, None) ))
      )

      getService

      wsClient ! Some(ws)
      wsConnected ! true

      result(idPref.signal.filter(_.contains(pushNotification.id)).head)
      result(syncPerformed.filter(_ == 0).head)
    }

    scenario("fetch notifications and update last id") {
      val notification1 = PushNotification(Uid(), Nil)
      val notification2 = PushNotification(Uid(), Nil)
      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(notification1, notification2), hasMore = false, None) ))
      )

      getService
      wsClient ! Some(ws)
      wsConnected ! true

      result(idPref.signal.filter(_.contains(notification2.id)).head)
      awaitAllTasks
      result(syncPerformed.filter(_ == 0).head)
    }

    scenario("request slow sync and fetch last notification if /notifications returns error") {
      clock + 10.minutes
      val time = clock.instant()
      (client.loadNotifications _).expects(*, *).once().returning(
          CancellableFuture.successful(Left(ErrorResponse(Response.Status.NotFound, "", "")))
      )

      (client.loadNotifications _).expects(*, *).once().returning(
          CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(PushNotification(Uid(), Nil), PushNotification(Uid(), Nil)), hasMore = false, None) ))
      )

      val service = getService
      wsClient ! Some(ws)
      wsConnected ! true

      result(service.onHistoryLost.filter(_ == time).head)
      result(syncPerformed.filter(_ == 1).head)
    }
  }

  feature("network changes") {
    scenario("schedule a delayed retry after a failed load") {
      val notification1 = PushNotification(Uid(), Nil)
      val notification2 = PushNotification(Uid(), Nil)

      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Left(ErrorResponse.InternalError))
      )
      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(notification1, notification2), hasMore = false, None) ))
      )

      PushService.syncHistoryBackoff = TestBackoff()

      getService
      wsClient ! Some(ws)
      wsConnected ! true

      result(idPref.signal.filter(_.contains(notification2.id)).head)
    }

    scenario("sync history on network change after a failed load") {
      val notification1 = PushNotification(Uid(), Nil)
      val notification2 = PushNotification(Uid(), Nil)

      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Left(ErrorResponse.InternalError))
      )
      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(notification1, notification2), hasMore = false, None) ))
      )

      val service = getService
      PushService.syncHistoryBackoff = TestBackoff(testDelay = 1.day) // long enough so we don't have to worry about it
      wsClient ! Some(ws)
      wsConnected ! true

      await(service.waitingForRetry.filter(_ == true).head)
      networkMode ! NetworkMode.WIFI // switching to wifi should trigger reloading

      result(idPref.signal.filter(_.contains(notification2.id)).head)
    }

    scenario("don't sync history on network change without a failed load") {

      val notification1 = PushNotification(Uid(), Nil)
      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Right( LoadNotificationsResponse(Vector(notification1), hasMore = false, None) ))
      )

      PushService.syncHistoryBackoff = TestBackoff(testDelay = 1.days) // long enough so we don't have to worry about it

      getService

      wsClient ! Some(ws)
      wsConnected ! true

      result(idPref.signal.filter(_.contains(notification1.id)).head)

      networkMode ! NetworkMode.WIFI // switching to wifi should NOT trigger reloading
      awaitAllTasks
      result(idPref.signal.filter(_.contains(notification1.id)).head)
    }
  }

  feature("web socket notifications") {

    scenario("receive notifications first with a fetch, then with a push") {

      val pushNot = PushNotification(Uid("push-not-id"), Nil)
      val wsJson = new JSONObject(notJson)
      val wsNot = PushNotification.NotificationDecoder(wsJson)

      (client.loadNotifications _).expects(*, *).once().returning(
        CancellableFuture.successful(Right(LoadNotificationsResponse(Vector(pushNot), hasMore = false, None)))
      )

      getService
      wsClient ! Some(ws)
      wsConnected ! true

      result(idPref.signal.filter(_.contains(pushNot.id)).head)

      ws.onMessage ! JsonObjectResponse(wsJson)

      result(idPref.signal.filter(_.contains(wsNot.id)).head)
    }

    scenario("receive a push notification during a fetch") {

      /**
        * Note, here we will end up processing the websocket notification twice if it is included in the fetch payload.
        * However, this scenario should not happen often and the worst that can happen is that we process an event twice,
        * getting duplicate errors from Cryptobox or discarding the event further down the line.
        */

      val pushNot = PushNotification(Uid("push-not-id"), Seq(OtrClientRemoveEvent(ClientId())))
      val wsJson = new JSONObject(notJson)
      val wsNot = PushNotification.NotificationDecoder(wsJson)

      (client.loadNotifications _).expects(*, *).once().onCall { _ =>
        ws.onMessage.publish(JsonObjectResponse(wsJson), Threading.Background)
        CancellableFuture.successful {
          Right(LoadNotificationsResponse(Vector(pushNot, wsNot), hasMore = false, None))
        }
      }

      getService
      wsClient ! Some(ws)
      wsConnected ! true

      result(idPref.signal.filter(_.contains(wsNot.id)).head) // processed as second even though received first
      result(processedEvents.filter(_.size == 2).head)
    }
  }

  feature("Fetch retries") {

    scenario("Retry should occur after delay expires") {
      val notification1 = PushNotification(Uid(), Nil)
      (client.loadNotifications _).expects(Some(lastId), *).once().returning(
        CancellableFuture.successful(Left(ErrorResponse.InternalError))
      )

      // this should be triggered by a network change and it still should use lastId, not pushNotification.id
      (client.loadNotifications _).expects(Some(lastId), *).once().returning(
        CancellableFuture.successful(Right(LoadNotificationsResponse(Vector(notification1, pushNotification), hasMore = false, None)))
      )

      PushService.syncHistoryBackoff = TestBackoff()
      getService
      wsConnected ! true
      wsClient ! Some(ws)

      result(idPref.signal.filter(_.contains(pushNotification.id)).head)
    }

    scenario("Opening websocket while waiting for a fetch retry should immediately drop the delay and re-try") {
      val notification1 = PushNotification(Uid("not1"), Nil)
      wsClient ! Some(ws)
      PushService.syncHistoryBackoff = TestBackoff(testDelay = 1.day) //plenty of time in which the websocket can become active

      (client.loadNotifications _).expects(Some(lastId), *).once().returning(
        CancellableFuture.successful(Left(ErrorResponse.InternalError))
      )

      // this should be triggered by a websocket connectivity change and it still should use lastId, not pushNotification.id
      (client.loadNotifications _).expects(*, *).anyNumberOfTimes().returning(
        CancellableFuture.successful(Right(LoadNotificationsResponse(Vector(notification1, pushNotification), hasMore = false, None)))
      )

      (client.loadNotifications _).expects(Some(pushNotification.id), *).never()
      val service = getService

      service.cloudPushNotificationsToProcess.mutate(_ + notification1.id)

      await(service.waitingForRetry.filter(_ == true).head)
      wsConnected ! true
      result(idPref.signal.filter(_.contains(pushNotification.id)).head)
    }
  }

  val lastId = Uid("last-id")
  val notJson = s"""
      | {
      |         "id":"ws-not-id",
      |         "payload":[
      |           {
      |             "type": "user.client-remove",
      |             "client": {
      |               "id": "some-client"
      |             }
      |          }
      |        ]
      | }
    """.stripMargin
  val notObject = new JSONObject(notJson)
  val pushNotification = PushNotification.NotificationDecoder(notObject)
}
