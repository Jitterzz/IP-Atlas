package app.ipatlas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** A web request that came back with an error code ([retryAfterSec] from the Retry-After header, if sent). */
class HttpError(val code: Int, val body: String, val retryAfterSec: Long? = null) : IOException("HTTP $code")

/** A service answered, but said it couldn't do the lookup. */
class LookupError(message: String) : Exception(message)

object Http {
    /** Sent with every request so the services can tell who's asking. */
    var userAgent: String = "IPAtlas/1.0 (Android)"

    suspend fun get(url: String, accept: String = "application/json", timeoutMs: Int = 9_000): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", accept)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw HttpError(code, body.take(400), conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
                }
                body
            } finally {
                conn.disconnect()
            }
        }

    suspend fun json(url: String, accept: String = "application/json"): JSONObject {
        val body = get(url, accept)
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw LookupError("The answer from ${hostOf(url)} couldn't be read.")
        }
    }

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')

    /** A short, plain reason a request failed, for showing on screen. */
    fun reason(e: Throwable): String = when (e) {
        is HttpError -> when (e.code) {
            429 -> "is out of free lookups for now"
            401, 403 -> "turned the request away"
            404 -> "doesn't have that address"
            in 500..599 -> "is having trouble (error ${e.code})"
            else -> "answered with error ${e.code}"
        }
        is UnknownHostException -> "couldn't be reached (no internet?)"
        is SocketTimeoutException -> "took too long to answer"
        is LookupError -> e.message ?: "couldn't look that up"
        is IOException -> "couldn't be reached"
        else -> e.message ?: "couldn't look that up"
    }
}

// Small helpers so missing values, JSON nulls, "" and "-" all come out as null.

internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    val s = when (v) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> return null
    }.trim()
    return s.takeIf { it.isNotEmpty() && it != "-" && !it.equals("null", ignoreCase = true) }
}

internal fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() && !it.isInfinite() }
}

internal fun JSONObject.bool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0", "" -> false
            else -> null
        }
        else -> null
    }
}

internal fun JSONObject.obj(key: String): JSONObject? = if (has(key) && !isNull(key)) optJSONObject(key) else null

internal fun JSONObject.arr(key: String): JSONArray? = if (has(key) && !isNull(key)) optJSONArray(key) else null

internal fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { i ->
        if (isNull(i)) null else opt(i)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
