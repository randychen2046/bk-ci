package com.tencent.devops.auth.service.oauth2

import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthOauth2ClientDetailsDao
import com.tencent.devops.auth.pojo.ClientDetailsInfo
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.model.auth.tables.records.TAuthOauth2ClientDetailsRecord
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class Oauth2ClientService constructor(
    private val dslContext: DSLContext,
    private val authOauth2ClientDetailsDao: AuthOauth2ClientDetailsDao
) {

    companion object {
        private val logger = LoggerFactory.getLogger(Oauth2ClientService::class.java)
    }

    fun getClientDetails(clientId: String): ClientDetailsInfo {
        return authOauth2ClientDetailsDao.get(
            dslContext = dslContext,
            clientId = clientId
        )?.convert() ?: throw ErrorCodeException(
            errorCode = AuthMessageCode.ERROR_CLIENT_NOT_EXIST,
            params = arrayOf(clientId),
            defaultMessage = "the client $clientId not exists"
        )
    }

    fun TAuthOauth2ClientDetailsRecord.convert(): ClientDetailsInfo {
        return ClientDetailsInfo(
            clientId = clientId,
            clientSecret = clientSecret,
            scope = scope,
            authorizedGrantTypes = authorizedGrantTypes,
            redirectUri = webServerRedirectUri,
            accessTokenValidity = accessTokenValidity,
            refreshTokenValidity = refreshTokenValidity,
        )
    }

    @Suppress("ThrowsCount")
    fun verifyClientInformation(
        clientId: String,
        clientDetails: ClientDetailsInfo,
        clientSecret: String? = null,
        redirectUri: String? = null,
        grantType: String? = null,
    ): Boolean {
        val authorizedGrantTypes = clientDetails.authorizedGrantTypes.split(",")
        if (grantType != null && !authorizedGrantTypes.contains(grantType)) {
            logger.warn("The client($clientId) does not support the authorization code type")
            throw ErrorCodeException(
                errorCode = AuthMessageCode.INVALID_AUTHORIZATION_TYPE,
                params = arrayOf(clientId),
                defaultMessage = "The client($clientId) does not support the authorization code type"
            )
        }
        if (redirectUri != null && redirectUri != clientDetails.redirectUri) {
            logger.warn("The redirectUri is invalid|$clientId|$redirectUri")
            throw ErrorCodeException(
                errorCode = AuthMessageCode.INVALID_REDIRECT_URI,
                params = arrayOf(redirectUri),
                defaultMessage = "The redirectUri($redirectUri) is invalid"
            )
        }
        if (clientSecret != null && clientSecret != clientDetails.clientSecret) {
            logger.warn("The client($clientId) secret is invalid")
            throw ErrorCodeException(
                errorCode = AuthMessageCode.INVALID_CLIENT_SECRET,
                params = arrayOf(clientId),
                defaultMessage = "The client($clientId) secret is invalid"
            )
        }
        return true
    }
}
