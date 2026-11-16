package com.banner.hintcontrol.gateway

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer from the gateway (wrong address, not on its network, gateway offline). */
    class Unreachable(val host: String, cause: Throwable? = null) :
        GatewayException("Can't reach the gateway at $host", cause)

    /** Wrong username or password, or the session could not be renewed. */
    class Unauthorized(message: String = REJECTED) : GatewayException(message) {
        /** True when a login attempt was refused (not "log in first" / an expired session). */
        val passwordRejected: Boolean get() = message == REJECTED

        companion object {
            const val REJECTED = "The gateway rejected the username or password"
        }
    }

    /** The gateway locked logins after too many attempts. */
    class TooManyAttempts(message: String) : GatewayException(message)

    class Http(val code: Int, message: String) : GatewayException(message)

    /** The gateway answered with something that could not be read. */
    class BadResponse(what: String, cause: Throwable? = null) :
        GatewayException("Unexpected answer from the gateway ($what)", cause)

    class NotSupported(feature: String) : GatewayException("$feature isn't available on this gateway")

    /** The connection dropped before the whole firmware file was sent. */
    class UploadInterrupted(val sent: Long, val total: Long, cause: Throwable? = null) :
        GatewayException("The connection dropped after ${sent / 1_048_576} of ${total / 1_048_576} MB, so the gateway didn't get the whole file", cause)

    class NoGatewayFound(val tried: List<String>) :
        GatewayException("No T-Mobile gateway found at ${tried.joinToString()}")
}
