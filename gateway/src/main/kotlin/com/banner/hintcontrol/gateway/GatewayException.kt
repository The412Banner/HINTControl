package com.banner.hintcontrol.gateway

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer from the gateway (wrong address, not on its network, gateway offline). */
    class Unreachable(val host: String, cause: Throwable? = null) :
        GatewayException("Can't reach the gateway at $host", cause)

    /** Wrong username or password, or the session could not be renewed. */
    class Unauthorized(message: String = "The gateway rejected the username or password") :
        GatewayException(message)

    /** The gateway locked logins after too many attempts. */
    class TooManyAttempts(message: String) : GatewayException(message)

    class Http(val code: Int, message: String) : GatewayException(message)

    /** The gateway answered with something that could not be read. */
    class BadResponse(what: String, cause: Throwable? = null) :
        GatewayException("Unexpected answer from the gateway ($what)", cause)

    class NotSupported(feature: String) : GatewayException("$feature isn't available on this gateway")

    class NoGatewayFound(val tried: List<String>) :
        GatewayException("No T-Mobile gateway found at ${tried.joinToString()}")
}
