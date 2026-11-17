package com.banner.hintcontrol.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** A large reply that arrives in pieces (like a long device list) must not be read on the caller's thread. */
    @Test
    fun `await reads the body off the calling thread`() = runBlocking {
        val size = 96_000
        server.enqueue(MockResponse().setBody("x".repeat(size)).throttleBody(8_192, 5, TimeUnit.MILLISECONDS))
        val readers = ConcurrentHashMap.newKeySet<Thread>()
        val http = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = requireNotNull(response.body)
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        readers += Thread.currentThread()
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(source.asResponseBody(body.contentType(), body.contentLength())).build()
            }
            .build()

        val caller = Thread.currentThread()
        val result = http.await(Request.Builder().url(server.url("/clients")).build())

        assertEquals(200, result.code)
        assertEquals(size, result.body.length)
        assertTrue(readers.isNotEmpty())
        assertFalse("body was read on the calling thread", caller in readers)
    }
}
