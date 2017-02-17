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
package com.waz.znet

import android.net.Uri
import com.waz.HockeyApp
import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.impl.{Credentials, ErrorResponse}
import com.waz.client.RegistrationClient
import com.waz.model.{AccountId, EmailAddress}
import com.waz.service.BackendConfig
import com.waz.threading.CancellableFuture.CancelException
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.{ExponentialBackoff, JsonEncoder}
import com.waz.znet.AuthenticationManager._
import com.waz.znet.ContentEncoder.{EmptyRequestContent, JsonContentEncoder}
import com.waz.znet.Response.{Status, SuccessHttpStatus}
import org.json.JSONObject

import scala.concurrent.duration._
import scala.util.control.NonFatal

class LoginClient(client: AsyncClient, backend: BackendConfig) {
  import com.waz.znet.LoginClient._
  private implicit val dispatcher = new SerialDispatchQueue(name = "LoginClient")

  private[znet] var lastRequestTime = 0L
  private[znet] var failedAttempts = 0
  private var lastResponse = Status.Success
  private var loginFuture = CancellableFuture.successful[LoginResult](Left(ErrorResponse.Cancelled))

  def requestDelay =
    if (failedAttempts == 0) Duration.Zero
    else {
      val minDelay = if (lastResponse == Status.RateLimiting || lastResponse == Status.LoginRateLimiting) 5.seconds else Duration.Zero
      val nextRunTime = lastRequestTime + Throttling.delay(failedAttempts, minDelay).toMillis
      math.max(nextRunTime - System.currentTimeMillis(), 0).millis
    }

  def login(accountId: AccountId, credentials: Credentials): CancellableFuture[LoginResult] = throttled(loginNow(accountId, credentials))

  def access(cookie: Cookie, token: Option[Token]) = throttled(accessNow(cookie, token))

  def throttled(request: => CancellableFuture[LoginResult]): CancellableFuture[LoginResult] = dispatcher {
    loginFuture = loginFuture.recover {
      case e: CancelException => Left(ErrorResponse.Cancelled)
      case ex: Throwable =>
        HockeyApp.saveException(ex, "Unexpected error when trying to log in.")
        Left(ErrorResponse.internalError("Unexpected error when trying to log in: " + ex.getMessage))
    } flatMap { _ =>
      verbose(s"throttling, delay: $requestDelay")
      CancellableFuture.delay(requestDelay)
    } flatMap { _ =>
      verbose(s"starting request")
      lastRequestTime = System.currentTimeMillis()
      request map {
        case Left(error) =>
          failedAttempts += 1
          lastResponse = error.getCode
          Left(error)
        case resp =>
          failedAttempts = 0
          lastResponse = Status.Success
          resp
      }
    }
    loginFuture
  }.flatten

  def loginNow(userId: AccountId, credentials: Credentials) = {
    debug(s"trying to login: $credentials")
    client(loginUri, Request.PostMethod, loginRequestBody(userId, credentials), timeout = RegistrationClient.timeout) map responseHandler
  }
  def accessNow(cookie: Cookie, token: Option[Token]) = {
    val headers = token.fold(Request.EmptyHeaders)(_.headers) ++ cookie.headers
    client(accessUri, Request.PostMethod, EmptyRequestContent, headers, timeout = RegistrationClient.timeout) map responseHandler
  }

  def requestVerificationEmail(email: EmailAddress): CancellableFuture[Either[ErrorResponse, Unit]] = {
    client(activateSendUri, Request.PostMethod, JsonContentEncoder(JsonEncoder(_.put("email", email.str)))) map {
      case Response(SuccessHttpStatus(), resp, _) => Right(())
      case Response(_, ErrorResponse(code, msg, label), _) =>
        info(s"requestVerificationEmail failed with error: ($code, $msg, $label)")
        Left(ErrorResponse(code, msg, label))
      case resp =>
        error(s"Unexpected response from resendVerificationEmail: $resp")
        Left(ErrorResponse(400, resp.toString, "unknown"))
    }
  }

  private val responseHandler: PartialFunction[Response, LoginResult] = {
    case Response(SuccessHttpStatus(), JsonObjectResponse(TokenResponse(token, exp, ttype)), responseHeaders) =>
      debug(s"receivedAccessToken: '$token', headers: $responseHeaders")
      Right((Token(token, ttype, System.currentTimeMillis() + exp * 1000), getCookieFromHeaders(responseHeaders)))
    case r @ Response(status, ErrorResponse(code, msg, label), _) =>
      warn(s"failed login attempt: $r")
      Left(ErrorResponse(code, msg, label))
    case r @ Response(status, _, _) => Left(ErrorResponse(status.status, s"unexpected login response: $r", ""))
  }

  private val loginUri = Uri.parse(backend.baseUrl).buildUpon().encodedPath(LoginPath).encodedQuery("persist=true").build()
  private val accessUri = Uri.parse(backend.baseUrl).buildUpon().encodedPath(AccessPath).build()
  private val activateSendUri = Uri.parse(backend.baseUrl).buildUpon().encodedPath(ActivateSendPath).build()
}

object LoginClient {
  private implicit val logTag: LogTag = logTagFor(LoginClient)
  type LoginResult = Either[ErrorResponse, (Token, Cookie)]
  type AccessToken = (String, Int, String)

  val SetCookie = "Set-Cookie"
  val Cookie = "Cookie"
  val CookieHeader = ".*zuid=([^;]+).*".r
  val LoginPath = "/login"
  val AccessPath = "/access"
  val ActivateSendPath = "/activate/send"

  val Throttling = new ExponentialBackoff(1000.millis, 10.seconds)

  def loginRequestBody(user: AccountId, credentials: Credentials) = JsonContentEncoder(JsonEncoder { o =>
    o.put("label", user.str)  // this label can be later used for cookie revocation
    credentials.addToLoginJson(o)
  })

  def getCookieFromHeaders(headers: Response.Headers): Option[Cookie] = headers(SetCookie) flatMap {
    case header @ CookieHeader(cookie) =>
      verbose(s"parsed cookie from header: $header, cookie: $cookie")
      Some(AuthenticationManager.Cookie(cookie))
    case header =>
      warn(s"Unexpected content for Set-Cookie header: $header")
      None
  }

  object TokenResponse {
    def unapply(json: JSONObject): Option[AccessToken] =
      if (json.has("access_token") && json.has("expires_in") && json.has("token_type")) {
        try {
          Some((json.getString("access_token"), json.getInt("expires_in"), json.getString("token_type")))
        } catch {
          case NonFatal(_) => None
        }
      } else None
  }
}
