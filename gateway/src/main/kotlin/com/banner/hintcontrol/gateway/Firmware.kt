package com.banner.hintcontrol.gateway

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

/** A firmware file chosen by the user. [open] is called once for the upload. */
class FirmwareFile(val name: String, val size: Long, val open: () -> InputStream)

enum class FirmwareUploadResult {
    /** The gateway answered HTTP 200 after receiving the whole file. */
    Accepted,

    /** The whole file was sent, then the connection closed without an answer (the gateway may be installing it). */
    SentNoAnswer,
}

object FirmwareSupport {
    /**
     * Gateways whose own web page has a firmware upload screen. Only checked on the TMO-G5AR
     * (firmware 1.00.06); other models are left out until someone confirms theirs has it.
     */
    fun isSupported(kind: GatewayKind, model: String?): Boolean = when (kind) {
        GatewayKind.Demo -> true
        GatewayKind.Tmi -> model?.contains("G5AR", ignoreCase = true) == true
        GatewayKind.Nokia -> false
    }
}

/** Streams the file as the request body and reports bytes sent. Never replayed by OkHttp. */
internal class FirmwareBody(
    private val file: FirmwareFile,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    @Volatile var sent = 0L
        private set

    override fun contentType(): MediaType = OCTET_STREAM

    override fun contentLength(): Long = file.size

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        file.open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                sent += n
                onProgress(sent)
            }
        }
        sink.flush()
    }

    companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
