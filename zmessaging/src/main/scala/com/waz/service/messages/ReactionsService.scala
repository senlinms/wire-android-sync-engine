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
package com.waz.service.messages

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.content.{Likes, ReactionsStorageImpl}
import com.waz.model._
import com.waz.service.UserServiceImpl
import com.waz.sync.SyncServiceHandle
import com.waz.threading.Threading
import com.waz.utils._
import org.threeten.bp.Instant
import org.threeten.bp.Instant.EPOCH

import scala.concurrent.Future

class ReactionsService(storage: ReactionsStorageImpl, messages: MessagesContentUpdater, sync: SyncServiceHandle, users: UserServiceImpl, selfUserId: UserId) {
  import Threading.Implicits.Background

  def like(conv: ConvId, msg: MessageId): Future[Likes] = addReaction(conv, msg, Liking.Action.Like)

  def unlike(conv: ConvId, msg: MessageId): Future[Likes] = addReaction(conv, msg, Liking.Action.Unlike)

  private def addReaction(conv: ConvId, msg: MessageId, action: Liking.Action): Future[Likes] = {
    verbose(s"addLiking: $conv $msg, $action")
    val reaction = Liking(msg, selfUserId, EPOCH, action) // EPOCH is used to signal "local" in-band
    for {
      likes  <- storage.addOrUpdate(reaction)
      _      <- sync.postLiking(conv, reaction)
    } yield likes
  }

  def updateLocalReaction(local: Liking, backendTime: Instant) = storage.update(local.id, { stored =>
    if (stored.timestamp <= local.timestamp) stored.copy(action = local.action, timestamp = backendTime)
    else stored
  })

  def processReactions(likings: Seq[Liking]): Future[Seq[Likes]] = Future.traverse(likings) { storage.addOrUpdate } // FIXME: use batching
}

case class MessageAndLikes(message: MessageData, likes: IndexedSeq[UserId], likedBySelf: Boolean)
object MessageAndLikes extends ((MessageData, IndexedSeq[UserId], Boolean) => MessageAndLikes) {
  val Empty = MessageAndLikes(MessageData.Empty, Vector.empty, likedBySelf = false)
  val Deleted = MessageAndLikes(MessageData.Deleted, Vector.empty, likedBySelf = false)
}
