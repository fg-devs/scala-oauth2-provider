package scalaoauth2.provider

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class GrantHandlerResult(tokenType: String, accessToken: String, expiresIn: Option[Long], refreshToken: Option[String], scope: Option[String])

trait GrantHandler {
  /**
   * Controls whether client credentials are required.  Defaults to true but can be overridden to be false when needed.
   * Per the OAuth2 specification, client credentials are required for all grant types except password, where it is up
   * to the authorization provider whether to make them required or not.
   */
  def clientCredentialRequired = true

  def handleRequest[U](request: AuthorizationRequest, authorizationHandler: AuthorizationHandler[U]): Future[GrantHandlerResult]

  /**
   * Returns valid access token.
   */
  protected def issueAccessToken[U](handler: AuthorizationHandler[U], authInfo: AuthInfo[U]): Future[GrantHandlerResult] = {
    handler.getStoredAccessToken(authInfo).flatMap {
      case Some(token) if shouldRefreshAccessToken(token) => token.refreshToken.map {
        handler.refreshAccessToken(authInfo, _)
      }.getOrElse {
        handler.createAccessToken(authInfo)
      }
      case Some(token) => Future.successful(token)
      case None => handler.createAccessToken(authInfo)
    }.map(createGrantHandlerResult)
  }

  protected def shouldRefreshAccessToken(token: AccessToken) = token.isExpired

  protected def createGrantHandlerResult(accessToken: AccessToken) = GrantHandlerResult(
    "Bearer",
    accessToken.token,
    accessToken.expiresIn,
    accessToken.refreshToken,
    accessToken.scope
  )

}

class RefreshToken extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val refreshTokenRequest = new RefreshTokenRequest(request)
    val clientCredential = refreshTokenRequest.clientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))
    val refreshToken = refreshTokenRequest.refreshToken

    handler.findAuthInfoByRefreshToken(refreshToken).flatMap { authInfoOption =>
      val authInfo = authInfoOption.getOrElse(throw new InvalidGrant("Authorized information is not found by the refresh token"))
      if (authInfo.clientId != Some(clientCredential.clientId)) {
        throw new InvalidClient
      }

      handler.refreshAccessToken(authInfo, refreshToken).map(createGrantHandlerResult)
    }
  }
}

class Password extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val passwordRequest = new PasswordRequest(request)
    if (clientCredentialRequired && passwordRequest.clientCredential.isEmpty) {
      throw new InvalidRequest("Client credential is required")
    }

    handler.findUser(passwordRequest).flatMap { maybeUser =>
      val user = maybeUser.getOrElse(throw new InvalidGrant("username or password is incorrect"))
      val scope = passwordRequest.scope
      val maybeClientId = passwordRequest.clientCredential.map(_.clientId)
      val authInfo = AuthInfo(user, maybeClientId, scope, None)

      issueAccessToken(handler, authInfo)
    }
  }
}

class ClientCredentials extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val clientCredentialsRequest = new ClientCredentialsRequest(request)
    val clientCredential = clientCredentialsRequest.clientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))
    val scope = clientCredentialsRequest.scope

    handler.findUser(clientCredentialsRequest).flatMap { optionalUser =>
      val user = optionalUser.getOrElse(throw new InvalidGrant("client_id or client_secret or scope is incorrect"))
      val authInfo = AuthInfo(user, Some(clientCredential.clientId), scope, None)

      issueAccessToken(handler, authInfo)
    }
  }

}

class AuthorizationCode extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val authorizationCodeRequest = new AuthorizationCodeRequest(request)
    val clientCredential = authorizationCodeRequest.clientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))
    val clientId = clientCredential.clientId
    val code = authorizationCodeRequest.code
    val redirectUri = authorizationCodeRequest.redirectUri

    handler.findAuthInfoByCode(code).flatMap { optionalAuthInfo =>
      val authInfo = optionalAuthInfo.getOrElse(throw new InvalidGrant("Authorized information is not found by the code"))
      if (authInfo.clientId != Some(clientId)) {
        throw new InvalidClient
      }

      if (authInfo.redirectUri.isDefined && authInfo.redirectUri != redirectUri) {
        throw new RedirectUriMismatch
      }

      val f = issueAccessToken(handler, authInfo)
      f onSuccess { case _ => handler.deleteAuthCode(code) }
      f
    }
  }

}

class Implicit extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val implicitRequest = new ImplicitRequest(request)
    val clientCredential = implicitRequest.clientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))

    handler.findUser(implicitRequest).flatMap { maybeUser =>
      val user = maybeUser.getOrElse(throw new InvalidGrant("user cannot be authenticated"))
      val scope = implicitRequest.scope
      val authInfo = AuthInfo(user, Some(clientCredential.clientId), scope, None)

      issueAccessToken(handler, authInfo)
    }
  }

  /**
   * Implicit grant doesn't support refresh token
   */
  protected override def shouldRefreshAccessToken(accessToken: AccessToken) = false

  /**
   * Implicit grant must not return refresh token
   */
  protected override def createGrantHandlerResult(accessToken: AccessToken) = super.createGrantHandlerResult(accessToken).copy(refreshToken = None)

}
