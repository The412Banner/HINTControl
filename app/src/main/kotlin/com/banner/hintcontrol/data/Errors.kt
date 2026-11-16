package com.banner.hintcontrol.data

import com.banner.hintcontrol.gateway.GatewayException

fun Throwable.userMessage(): String = when (this) {
    is GatewayException.Unreachable -> "Can't reach the gateway at $host. Check that this phone is on the gateway's Wi-Fi or network."
    is GatewayException.NoGatewayFound -> "No T-Mobile gateway answered at ${tried.joinToString()}."
    is GatewayException -> message ?: "Gateway error"
    is NeedsLoginException -> "Log in to the gateway to see this."
    is NoGatewayException -> "Set up a gateway first."
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

val Throwable.isOffline: Boolean get() = this is GatewayException.Unreachable

val Throwable.needsLogin: Boolean
    get() = this is NeedsLoginException || this is GatewayException.Unauthorized
