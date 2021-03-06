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
package com.waz.service.call

import com.sun.jna.Pointer
import com.waz.api.NetworkMode
import com.waz.content.MembersStorage
import com.waz.model.ConversationData.ConversationType
import com.waz.model.otr.ClientId
import com.waz.model.{UserId, _}
import com.waz.service.call.Avs.ClosedReason.{AnsweredElsewhere, Normal, StillOngoing}
import com.waz.service.call.Avs.WCall
import com.waz.service.call.CallInfo.CallState._
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.messages.MessagesService
import com.waz.service.{MediaManagerService, NetworkModeService}
import com.waz.specs.AndroidFreeSpec
import com.waz.testutils.TestUserPreferences
import com.waz.threading.{SerialDispatchQueue, Threading}
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.wrappers.Context
import com.waz.utils.{RichInstant, Serialized}
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

class CallingServiceSpec extends AndroidFreeSpec {

  implicit val eventContext = EventContext.Implicits.global
  implicit val executionContext = new SerialDispatchQueue(name = "CallingServiceSpec")

  val context        = mock[Context]
  val avs            = mock[Avs]
  val flows          = mock[FlowManagerService]
  val members        = mock[MembersStorage]
  val media          = mock[MediaManagerService]
  val network        = mock[NetworkModeService]
  val convs          = mock[ConversationsContentUpdater]
  val callLogService = mock[CallLogService]
  val messages       = mock[MessagesService]

  val self     = UserId("selfUserId")
  val clientId = ClientId("selfClient")
  val account  = AccountId(self.str)

  feature("Basics") {
    scenario("CallingService intialization") {
      val pointer = new Pointer(0L)
      val service = initCallingService(pointer)
      result(service.wCall) shouldEqual pointer
    }

    scenario("Incoming call 1:1 call goes through SelfJoining to become SelfConnected") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Conv"), self, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfJoining))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfConnected && cur.others == Set(otherUser)))

      service.onIncomingCall(_1t1Conv.remoteId, otherUser, videoCall = false, shouldRing = true)
      (avs.answerCall _).expects(*, *, *).once().onCall { (_, _, _) =>
        service.onEstablishedCall(_1t1Conv.remoteId, otherUser)
      }
      service.startCall(_1t1Conv.id)
      result(checkpoint1.head)
      result(checkpoint2.head)
    }

    scenario("Incoming group call goes through SelfJoining to become SelfConnected") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), self, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfJoining))
      val checkpoint2 = callCheckpoint(service, _.contains(groupConv.id),
        _.exists(cur => cur.convId == groupConv.id && cur.state == SelfConnected && cur.others == Set(groupMember1, groupMember2)))

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      (avs.answerCall _).expects(*, *, *).once().onCall { (_, _, _) =>
        service.onEstablishedCall(groupConv.remoteId, groupMember1)
        service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      }
      service.startCall(groupConv.id)
      result(checkpoint1.head)
      result(checkpoint2.head)
    }

    scenario("Outgoing 1:1 call goes through SelfCalling to SelfJoining to SelfConnected") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(otherUser.str), RConvId(), Some("1:1 Conv"), self, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfCalling && cur.caller == self && cur.others == Set(otherUser)))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfJoining && cur.caller == self && cur.others == Set(otherUser)))
      val checkpoint3 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfConnected && cur.caller == self && cur.others == Set(otherUser)))

      (avs.startCall _).expects(*, *, *, *, *).once().returning(Future.successful(0))

      service.startCall(_1t1Conv.id)
      result(checkpoint1.head)

      service.onOtherSideAnsweredCall(_1t1Conv.remoteId)
      result(checkpoint2.head)

      service.onEstablishedCall(_1t1Conv.remoteId, otherUser)
      result(checkpoint3.head)
    }

    scenario("Outgoing group call goes through SelfCalling to SelfJoining to SelfConnected") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), self, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()

      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfCalling && cur.caller == self && cur.others == Set(self)))
      val checkpoint2 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfJoining && cur.caller == self && cur.others == Set(self)))
      val checkpoint3 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfConnected && cur.caller == self && cur.others == Set(groupMember1)))
      val checkpoint4 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfConnected && cur.caller == self && cur.others == Set(groupMember1, groupMember2)))

      (avs.startCall _).expects(*, *, *, *, *).once().returning(Future.successful(0))

      service.startCall(groupConv.id)
      result(checkpoint1.head)

      service.onOtherSideAnsweredCall(groupConv.remoteId)
      result(checkpoint2.head)

      //TODO which user from a group conversation gets passed down here?
      service.onEstablishedCall(groupConv.remoteId, groupMember1)
      println(result(service.currentCall.map(_.get.others).head))
      result(checkpoint3.head)

      service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      result(checkpoint4.head)
    }

    scenario("Team conversation with only 1 other member should be treated as 1:1 conversation - outgoing") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Team Conv"), self, ConversationType.Group, Some(TeamId())) //all team convs are goup by type

      (convs.convByRemoteId _).expects(_1t1Conv.remoteId).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(_1t1Conv.id).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (members.getByConvs _).expects(Set(_1t1Conv.id)).twice().returning(Future.successful(IndexedSeq(otherUser, self).map(u => ConversationMemberData(u, _1t1Conv.id))))
      (avs.startCall _).expects(*, _1t1Conv.remoteId, false, false, *).once().returning(Future.successful(0))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfCalling && cur.caller == self && cur.others == Set(otherUser)))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfJoining && cur.caller == self && cur.others == Set(otherUser)))
      val checkpoint3 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfConnected && cur.caller == self && cur.others == Set(otherUser)))

      service.startCall(_1t1Conv.id)
      result(checkpoint1.head)

      service.onOtherSideAnsweredCall(_1t1Conv.remoteId)
      result(checkpoint2.head)

      service.onEstablishedCall(_1t1Conv.remoteId, otherUser)
      result(checkpoint3.head)
    }

    scenario("Team conversation with only 1 other member should be treated as 1:1 conversation - incoming") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Team Conv"), self, ConversationType.Group, Some(TeamId()))

      (convs.convByRemoteId _).expects(_1t1Conv.remoteId).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(_1t1Conv.id).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (members.getByConvs _).expects(Set(_1t1Conv.id)).repeated(2).returning(Future.successful(IndexedSeq(otherUser, self).map(u => ConversationMemberData(u, _1t1Conv.id))))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfJoining))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == SelfConnected && cur.others == Set(otherUser)))

      service.onIncomingCall(_1t1Conv.remoteId, otherUser, videoCall = false, shouldRing = true)
      (avs.answerCall _).expects(*, _1t1Conv.remoteId, *).once().onCall { (_, _, _) =>
        service.onEstablishedCall(_1t1Conv.remoteId, otherUser)
      }
      service.startCall(_1t1Conv.id)
      result(checkpoint1.head)
      result(checkpoint2.head)
    }

    scenario("Group Team conversation treated as group call") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), self, ConversationType.Group, Some(TeamId()))

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (members.getByConvs _).expects(Set(groupConv.id)).once().returning(Future.successful(IndexedSeq(groupMember1, groupMember2, self).map(u => ConversationMemberData(u, groupConv.id))))

      val service = initCallingService()

      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfCalling && cur.caller == self && cur.others == Set(self)))
      val checkpoint2 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfJoining && cur.caller == self && cur.others == Set(self)))
      val checkpoint3 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfConnected && cur.caller == self && cur.others == Set(groupMember1)))
      val checkpoint4 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfConnected && cur.caller == self && cur.others == Set(groupMember1, groupMember2)))

      (avs.startCall _).expects(*, *, *, *, *).once().returning(Future.successful(0))

      service.startCall(groupConv.id)
      result(checkpoint1.head)

      service.onOtherSideAnsweredCall(groupConv.remoteId)
      result(checkpoint2.head)

      //TODO which user from a group conversation gets passed down here?
      service.onEstablishedCall(groupConv.remoteId, groupMember1)
      println(result(service.currentCall.map(_.get.others).head))
      result(checkpoint3.head)

      service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      result(checkpoint4.head)
    }
  }

  feature("Ending calls") {
    scenario("Leave group call that will continue running in the background with state Ongoing - established time should not be affected") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), self, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()
      val estTime = clock.instant() + 10.seconds

      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(c => c.state == SelfConnected && c.estabTime.contains(estTime)))
//      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(c => c.state == SelfConnected))
      val checkpoint2 = callCheckpoint(service, _.get(groupConv.id).exists(c => c.state == Ongoing && c.estabTime.contains(estTime)), _.isEmpty)
//      val checkpoint2 = callCheckpoint(service, _.get(groupConv.id).exists(c => c.state == Ongoing), _.isEmpty)

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)

      clock + 10.seconds

      println(clock.instant)

      (avs.answerCall _).expects(*, *, *).once().onCall { (_, _, _) =>
        println(s"callback time: ${clock.instant}")
        service.onEstablishedCall(groupConv.remoteId, groupMember1)
        service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      }
      service.startCall(groupConv.id)
      result(checkpoint1.head)

      (avs.endCall _).expects(*, *).once().onCall { (rId, isGroup) =>
        service.onClosedCall(StillOngoing, groupConv.remoteId, clock.instant(), groupMember1)
      }

      clock + 10.seconds

      service.endCall(groupConv.id)
      result(checkpoint2.head)
    }

    scenario("Incoming group call answered on another device") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), self, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(_.state == OtherCalling))
      val checkpoint2 = callCheckpoint(service, _.isEmpty, _.isEmpty)

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      result(checkpoint1.head)

      service.onClosedCall(AnsweredElsewhere, groupConv.remoteId, Instant.now, groupMember1)
      result(checkpoint2.head)
    }

    scenario("Reject incoming 1:1 call should remove it from activeCall, and then also from backgroundCalls after timeout") {
      val otherUser = UserId("otherUser")
      val _1t1Conv = ConversationData(ConvId(), RConvId(), Some("1:1 Conv"), self, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(_1t1Conv)))

      val service = initCallingService()
      val checkpoint1 = callCheckpoint(service, _.contains(_1t1Conv.id), _.exists(cur => cur.convId == _1t1Conv.id && cur.state == OtherCalling))
      val checkpoint2 = callCheckpoint(service, _.contains(_1t1Conv.id), _.isEmpty)
      val checkpoint3 = callCheckpoint(service, _.isEmpty, _.isEmpty)

      service.onIncomingCall(_1t1Conv.remoteId, otherUser, videoCall = false, shouldRing = true)
      result(checkpoint1.head)

      (avs.rejectCall _).expects(*, *).once().onCall { (_, _) =>
        service.onClosedCall(StillOngoing, _1t1Conv.remoteId, Instant.now, otherUser)
      }
      service.endCall(_1t1Conv.id)
      result(checkpoint2.head)

      service.onClosedCall(Normal, _1t1Conv.remoteId, Instant.now, otherUser)
      result(checkpoint3.head)
    }

    scenario("Cancel outgoing group call should remove it from background and active calls") {
      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId(), RConvId(), Some("Group Conv"), self, ConversationType.Group)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))
      (convs.convById _).expects(*).anyNumberOfTimes().returning(Future.successful(Some(groupConv)))

      val service = initCallingService()

      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.exists(cur => cur.convId == groupConv.id && cur.state == SelfCalling && cur.caller == self && cur.others == Set(self)))
      val checkpoint2 = callCheckpoint(service, _.isEmpty, _.isEmpty)

      (avs.startCall _).expects(*, *, *, *, *).once().returning(Future.successful(0))
      service.startCall(groupConv.id)
      result(checkpoint1.head)

      (avs.endCall _).expects(*, groupConv.remoteId).once().onCall { (_, _) =>
        service.onClosedCall(Normal, groupConv.remoteId, Instant.now, groupMember1)
      }
      service.endCall(groupConv.id)
      result(checkpoint2.head)
    }
  }

  feature("Simultaneous calls") {

    scenario("Receive incoming call while 1:1 call ongoing - should become active if ongoing call is dropped") {

      val ongoingUserId = UserId()
      val ongoingConv = ConversationData(ConvId(ongoingUserId.str), RConvId(ongoingUserId.str), Some("Ongoing Conv"), self, ConversationType.OneToOne)

      val incomingUserId = UserId()
      val incomingConv = ConversationData(ConvId(incomingUserId.str), RConvId(incomingUserId.str), Some("Incoming Conv"), self, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().onCall { rConvId: RConvId =>
        Future.successful(rConvId match {
          case ongoingConv.remoteId => Some(ongoingConv)
          case incomingConv.remoteId => Some(incomingConv)
          case _ => None
        })
      }
      (convs.convById _).expects(ConvId(ongoingUserId.str)).anyNumberOfTimes().returning(Future.successful(Some(ongoingConv)))

      val service = initCallingService()

      val checkpoint1 = callCheckpoint(service, _.contains(ongoingConv.id), cur => cur.exists(_.state == SelfConnected) && cur.exists(_.others.contains(ongoingUserId)))

      service.onIncomingCall(ongoingConv.remoteId, ongoingUserId, videoCall = false, shouldRing = true)
      (avs.answerCall _).expects(*, *, *).once().onCall { (_, _, _) =>
        service.onEstablishedCall(ongoingConv.remoteId, ongoingUserId)
      }
      service.startCall(ongoingConv.id)
      await(checkpoint1.head)

      //Both calls should be in available calls, but the ongoing call should be current
      val checkpoint2 = callCheckpoint(service, { avail =>
        avail.contains(ongoingConv.id) && avail.get(incomingConv.id).exists(_.state == OtherCalling)
      }, cur => cur.exists(_.state == SelfConnected) && cur.exists(_.others.contains(ongoingUserId)))

      service.onIncomingCall(incomingConv.remoteId, incomingUserId, videoCall = false, shouldRing = false) //Receive the second call after first is established
      await(checkpoint2.head)

      //Hang up the ongoing call - incoming 1:1 call should become current
      val checkpoint3 = callCheckpoint(service, _.contains(incomingConv.id), cur => cur.exists(_.state == OtherCalling) && cur.exists(_.others.contains(incomingUserId)))
      (avs.endCall _).expects(*, ongoingConv.remoteId).once().onCall { (_, _) =>
        service.onClosedCall(Normal, ongoingConv.remoteId, Instant.now, ongoingUserId)
      }
      service.endCall(ongoingConv.id)
      await(checkpoint3.head)
    }

    scenario("With a background group call, receive a 1:1 call, finish it, and then still join the group call afterwards") {

      val groupMember1 = UserId("groupUser1")
      val groupMember2 = UserId("groupUser2")
      val groupConv = ConversationData(ConvId("group_conv"), RConvId("r_group_conv"), Some("Group Conv"), self, ConversationType.Group)

      val otoUser = UserId("otoUser")
      val otoConv = ConversationData(ConvId(otoUser.str), RConvId(otoUser.str), Some("1:1 Conv"), self, ConversationType.OneToOne)

      (convs.convByRemoteId _).expects(*).anyNumberOfTimes().onCall { rConvId: RConvId =>
        Future.successful(rConvId match {
          case groupConv.remoteId => Some(groupConv)
          case otoConv.remoteId => Some(otoConv)
          case _ => None
        })
      }

      (convs.convById _).expects(*).anyNumberOfTimes().onCall { convId: ConvId =>
        Future.successful(convId match {
          case groupConv.id => Some(groupConv)
          case otoConv.id => Some(otoConv)
          case _ => None
        })
      }

      (avs.setVideoSendActive _).expects(*, otoConv.remoteId, false).anyNumberOfTimes()
      val service = initCallingService()

      //Checkpoint 1: Receive and reject a group call
      val checkpoint1 = callCheckpoint(service, _.contains(groupConv.id), _.isEmpty)

      service.onIncomingCall(groupConv.remoteId, groupMember1, videoCall = false, shouldRing = true)
      (avs.rejectCall _).expects(*, *).anyNumberOfTimes().onCall { (_, _) =>
        service.onClosedCall(StillOngoing, groupConv.remoteId, Instant.now, groupMember1)
      }
      service.endCall(groupConv.id) //user rejects the group call

      result(checkpoint1.head)

      //Checkpoint 2: Receive and accept a 1:1 call
      val checkpoint2 = callCheckpoint(service,
        act => act.contains(groupConv.id) && act.contains(otoConv.id),
        _.exists(curr => curr.others.contains(otoUser) && curr.state == SelfConnected))

      service.onIncomingCall(otoConv.remoteId, otoUser, videoCall = false, shouldRing = true)
      (avs.answerCall _).expects(*, *, *).once().onCall { (rId, _, _) =>
        service.onEstablishedCall(otoConv.remoteId, otoUser)
      }
      service.startCall(otoConv.id) //user accepts 1:1 call

      result(checkpoint2.head)

      //Checkpoint 3: 1:1 call is finished
      val checkpoint3 = callCheckpoint(service, _.contains(groupConv.id), _.isEmpty)

      (avs.endCall _).expects(*, *).once().onCall { (rId, _) =>
        service.onClosedCall(Normal, otoConv.remoteId, Instant.now, otoUser)
      }
      service.endCall(otoConv.id)

      result(checkpoint3.head)

      //Checkpoint 4: Join group call
      val checkpoint4 = callCheckpoint(service, _.contains(groupConv.id), _.exists(curr => curr.others == Set(groupMember1, groupMember2) && curr.state == SelfConnected))

      (avs.answerCall _).expects(*, *, *).once().onCall { (rId, _, _) =>
        service.onEstablishedCall(groupConv.remoteId, groupMember1)
        service.onGroupChanged(groupConv.remoteId, Set(groupMember1, groupMember2))
      }
      service.startCall(groupConv.id)

      result(checkpoint4.head)
    }
  }

  feature("tracking") {
    scenario("AVS metrics parsing") {
      val metrics = "{\"version\":\"avs 3.5.37 (arm\\/linux)\",\"protocol-version\":\"3.0\",\"group\":false,\"direction\":\"Outgoing\",\"answered\":false,\"estab_time(ms)\":0,\"audio_setup_time(ms)\":0,\"dtls\":false,\"ice\":false,\"video\":true,\"media_time(s)\":0,\"mic_vol(dB)\":0,\"spk_vol(dB)\":0,\"avg_rtt\":0,\"max_rtt\":0,\"avg_jb_loss\":0,\"max_jb_loss\":0,\"avg_jb_size\":0,\"max_jb_size\":0,\"avg_loss_u\":0,\"max_loss_u\":0,\"avg_loss_d\":0,\"max_loss_d\":0,\"avg_rate_d\":0,\"min_rate_d\":0,\"avg_pkt_rate_d\":0,\"min_pkt_rate_d\":0,\"a_dropouts\":0,\"avg_rate_u\":0,\"min_rate_u\":0,\"avg_pkt_rate_u\":0,\"min_pkt_rate_u\":0,\"audio_route\":\"\",\"v_avg_rate_d\":0,\"v_min_rate_d\":0,\"v_max_rate_d\":0,\"v_avg_frame_rate_d\":0,\"v_min_frame_rate_d\":0,\"v_max_frame_rate_d\":0,\"v_dropouts\":0,\"v_avg_rate_u\":0,\"v_min_rate_u\":0,\"v_max_rate_u\":0,\"v_avg_frame_rate_u\":0,\"v_min_frame_rate_u\":0,\"v_max_frame_rate_u\":0,\"turn_alloc\":40,\"nat_estab\":0,\"dtls_estab\":0,\"ecall_error\":0,\"local_cand\":\"???\",\"remote_cand\":\"???\",\"crypto\":\"None\"}"
      import com.waz.utils.RichJSON
      val map = new JSONObject(metrics).topLevelStringMap

      map.size         shouldEqual 53
      map("version")   shouldEqual "avs 3.5.37 (arm/linux)"
      map("direction") shouldEqual "Outgoing"
    }
  }

  scenario("Test...") {
    //TODO this is simplest example of what's happening on receiving a video call (between incoming call and video state change)
    //it would be good to find a better way of fixing this, but I'll leave this here for now for future ease of testing

    val lock = 1

    val f1 = {
      Serialized.future(lock) {
        for {
          a <- Future {
            println("1A")
            Thread.sleep(1000)
            1
          }(Threading.Background)
          b <- Future {
            println("1B")
            Thread.sleep(1000)
            2
          } (Threading.Background)
        } yield println(s"Result of operations: ${a + b}")
      }.map(res => res)
    }

    val f2 =
      Future {
        println("2A")
        Thread.sleep(1000)
        (Threading.Background)
      }.map { _ =>
        println("performing task 2")
      }


    result(Future.sequence(Seq(f1, f2)))

  }
  
  def callCheckpoint(service: CallingService, activeCheck: Map[ConvId, CallInfo] => Boolean, currentCheck: Option[CallInfo] => Boolean) =
    (for {
      active <- service.availableCalls
      current <- service.currentCall
    } yield (active, current)).filter { case (active, current) =>
      activeCheck(active) && currentCheck(current)
    }

  def signalTest[A](signal: Signal[A])(test: A => Boolean)(trigger: => Unit): Unit = {
    signal.disableAutowiring()
    trigger
    result(signal.filter(test).head)
  }

  def initCallingService(wCall: WCall = new Pointer(0L)) = {
    val prefs = new TestUserPreferences()
    (context.startService _).expects(*).anyNumberOfTimes().returning(true)
    (flows.flowManager _).expects().once().returning(None)
    (callLogService.addEstablishedCall _).expects(*, *, *).anyNumberOfTimes().returning(Future.successful({}))
    (messages.addMissedCallMessage(_:RConvId, _:UserId, _:Instant)).expects(*, *, *).anyNumberOfTimes().returning(Future.successful(None))
    (messages.addMissedCallMessage(_:ConvId, _:UserId, _:Instant)).expects(*, *, *).anyNumberOfTimes().returning(Future.successful(None))
    (messages.addSuccessfulCallMessage _).expects(*, *, *, *).anyNumberOfTimes().returning(Future.successful(None))
    (network.networkMode _).expects().once().returning(Signal.empty[NetworkMode])

    (avs.registerAccount _).expects(*).once().returning(Future.successful(wCall))
    val service = new CallingService(self, clientId, account, context, avs, convs, members, null, flows, messages, media, null, callLogService, network, null, null, prefs)
    result(service.wCall)
    service
  }
}
