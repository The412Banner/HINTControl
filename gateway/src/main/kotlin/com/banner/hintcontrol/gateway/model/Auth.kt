// Data shapes ported from HINT Control by Zachary Wander (MIT License).
package com.banner.hintcontrol.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginResultData(
    val auth: AuthResultData? = null,
    val result: ResultData? = null,
)

@Serializable
data class AuthResultData(
    val token: String? = null,
    val expiration: Long? = null,
    val refreshCountLeft: Int? = null,
    val refreshCountMax: Int? = null,
)

@Serializable
data class ResultData(
    val error: String? = null,
    val message: String? = null,
    val statusCode: Int? = null,
)

@Serializable
data class UsernamePassword(
    val username: String,
    val password: String,
)

@Serializable
data class SetLoginAction(
    val usernameNew: String,
    val passwordNew: String,
)
