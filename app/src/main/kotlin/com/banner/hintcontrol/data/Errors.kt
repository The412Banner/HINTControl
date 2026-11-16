package com.banner.hintcontrol.data

import com.banner.hintcontrol.gateway.GatewayException

private const val VPN_NOTE = " A VPN is on, which can block the gateway. Turn it off, or allow local network access in the VPN app, then try again."
private const val RESTART_NOTE = "If you're sure it's right, restart the gateway (unplug it for 10 seconds) and try again: some gateways stop accepting the correct password until they restart."

fun Throwable.userMessage(): String = when (this) {
    is GatewayException.Unreachable ->
        "Can't reach the gateway at $host. Check that this phone is on the gateway's Wi-Fi or network." + vpnNote()
    is GatewayException.NoGatewayFound -> "No T-Mobile gateway answered at ${tried.joinToString()}." + vpnNote()
    is GatewayException.Unauthorized -> if (passwordRejected) "${GatewayException.Unauthorized.REJECTED}. $RESTART_NOTE" else message ?: "Log in again."
    is GatewayException.TooManyAttempts -> "${message ?: "Too many attempts."} If the password was right, restarting the gateway can help."
    is GatewayException -> message ?: "Gateway error"
    is NeedsLoginException -> "Log in to the gateway to see this."
    is NoGatewayException -> "Set up a gateway first."
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

private fun vpnNote(): String = if (NetworkInfo.vpnActive()) VPN_NOTE else ""

val Throwable.isOffline: Boolean get() = this is GatewayException.Unreachable

val Throwable.needsLogin: Boolean
    get() = this is NeedsLoginException || this is GatewayException.Unauthorized
