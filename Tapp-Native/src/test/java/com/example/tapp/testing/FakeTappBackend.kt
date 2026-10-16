package com.example.tapp.testing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process stand-in for the Tapp API. It is installed as the JVM-wide http(s) handler, so the
 * SDK's real NetworkManager -> HttpURLConnection -> BackendResponseParser path runs unchanged and
 * every request is recorded.
 */
object FakeTappBackend {

    data class RecordedRequest(
        val method: String,
        val host: String,
        val endpoint: String,
        val headers: Map<String, String>,
        val body: String
    )

    sealed class Reply {
        data class Http(val status: Int, val body: String) : Reply()

        /** Thrown from getResponseCode(), like a dropped connection or a timeout. */
        data class Failure(val error: Exception) : Reply()
    }

    private const val PATH_PREFIX = "/v1/ref/"

    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val replies = mutableMapOf<String, MutableList<Reply>>()

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol == "https" || protocol == "http") FakeHandler else null
            }
            installed = true
        }
    }

    fun reset() {
        requests.clear()
        synchronized(replies) { replies.clear() }
    }

    /** Replies are consumed in order; the last one keeps being returned. */
    fun enqueue(endpoint: String, vararg reply: Reply) {
        synchronized(replies) { replies.getOrPut(endpoint) { mutableListOf() }.addAll(reply) }
    }

    fun json(status: Int, body: String) = Reply.Http(status, body)

    fun count(endpoint: String) = requests.count { it.endpoint == endpoint }

    fun bodiesOf(endpoint: String) = requests.filter { it.endpoint == endpoint }.map { it.body }

    internal fun nextReply(endpoint: String): Reply = synchronized(replies) {
        val queue = replies[endpoint]
        when {
            queue.isNullOrEmpty() -> Reply.Http(
                500,
                """{"success":false,"message":"No fake reply for $endpoint","error_code":"SERVER_ERROR"}"""
            )
            queue.size == 1 -> queue[0]
            else -> queue.removeAt(0)
        }
    }

    private object FakeHandler : URLStreamHandler() {
        override fun openConnection(url: URL) = FakeConnection(url)
    }

    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        private val requestBody = ByteArrayOutputStream()
        private var response: Reply.Http? = null

        override fun connect() {
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getOutputStream(): OutputStream = requestBody

        override fun getResponseCode(): Int = execute().status

        override fun getInputStream(): InputStream {
            val reply = execute()
            if (reply.status !in 200..299) throw IOException("HTTP ${reply.status}")
            return ByteArrayInputStream(reply.body.toByteArray(Charsets.UTF_8))
        }

        override fun getErrorStream(): InputStream? {
            val reply = execute()
            if (reply.status < 400) return null
            return ByteArrayInputStream(reply.body.toByteArray(Charsets.UTF_8))
        }

        private fun execute(): Reply.Http {
            response?.let { return it }
            val headers = requestProperties.mapValues { it.value.joinToString(",") }
            val endpoint = url.path.removePrefix(PATH_PREFIX)
            requests += RecordedRequest(
                method = requestMethod,
                host = url.host,
                endpoint = endpoint,
                headers = headers,
                body = requestBody.toString(Charsets.UTF_8.name())
            )
            connected = true
            return when (val reply = nextReply(endpoint)) {
                is Reply.Http -> reply.also { response = it }
                is Reply.Failure -> throw reply.error
            }
        }
    }
}
