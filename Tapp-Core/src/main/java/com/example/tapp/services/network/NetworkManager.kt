package com.example.tapp.services.network

import com.example.tapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class NetworkManager {

    // General method to perform any HTTP request
    private suspend fun httpRequest(
        url: String,
        method: String,
        params: Map<String, Any>? = null,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Create URL and connection
            val connectionUrl = URL(url)
            val endpointPath = connectionUrl.path.ifBlank { "/" }
            Logger.logInfo("Starting $method request to $endpointPath")
            val connection = connectionUrl.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.doInput = true

            // Set headers
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // If the method requires a body, write the parameters as JSON
            if (method in listOf("POST", "PUT", "PATCH")) {
                connection.doOutput = true
                val requestBody = JSONObject().apply {
                    params?.forEach { (key, value) ->
                        when (value) {
                            is Map<*, *> -> put(key, JSONObject(value as Map<String, Any>))
                            else -> put(key, value)
                        }
                    }
                }.toString()

                connection.outputStream.use { outputStream ->
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use {
                        it.write(requestBody)
                        it.flush()
                    }
                }
            }

            // Get response code and handle input or error stream
            val responseCode = connection.responseCode
            Logger.logInfo("Response received: method=$method, path=$endpointPath, status=$responseCode")

            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    ?: "Unknown error"
            }

            val parsedResponse = BackendResponseParser.parseHttpResponse(responseCode, responseText)
            parsedResponse.onSuccess {
                Logger.logDebug("Parsed response received for $endpointPath")
            }.onFailure { error ->
                if (error is BackendApiException) {
                    Logger.logWarning(
                        "Backend error: path=$endpointPath, status=${error.statusCode ?: responseCode}, " +
                                "error_code=${error.errorCode ?: "none"}, message=${error.backendMessage}"
                    )
                } else {
                    Logger.logError(
                        "Response parsing failed: path=$endpointPath, status=$responseCode, " +
                                "error=${error::class.java.simpleName}"
                    )
                }
            }
            parsedResponse

        } catch (e: Exception) {
            Logger.logError("$method request failed locally: ${e::class.java.simpleName}")
            Result.failure(e)
        }
    }

    // GET Request
    suspend fun getRequest(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = httpRequest(url, "GET", null, headers)

    // POST Request
    suspend fun postRequest(
        url: String,
        params: Map<String, Any>,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = httpRequest(url, "POST", params, headers)

    // PUT Request
    suspend fun putRequest(
        url: String,
        params: Map<String, Any>,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = httpRequest(url, "PUT", params, headers)

    // DELETE Request
    suspend fun deleteRequest(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = httpRequest(url, "DELETE", null, headers)

    // PATCH Request (if needed)
    suspend fun patchRequest(
        url: String,
        params: Map<String, Any>,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> = httpRequest(url, "PATCH", params, headers)
}
