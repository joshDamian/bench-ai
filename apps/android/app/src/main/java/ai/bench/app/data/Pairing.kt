package ai.bench.app.data

import java.net.URI

/** Where the server is and how this device proves itself to it. */
data class ServerConfig(val baseUrl: String, val token: String?, val deviceName: String? = null)

/** A pairing link split into the server it names and the one-time code. */
data class PairLink(val baseUrl: String, val code: String)

object Pairing {
    /**
     * Reads what `bench pair` shows - the QR's URL, `https://host/#/pair/CODE` - or a bare
     * server address with the code typed separately. Null when it is neither.
     */
    fun parse(input: String, separateCode: String? = null): PairLink? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val base = "$scheme://$host$port"
        val fromFragment = uri.rawFragment?.let { Regex("^/?pair/([A-Za-z0-9_-]+)").find(it)?.groupValues?.get(1) }
        val code = fromFragment ?: separateCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PairLink(base, code)
    }

    /** The token from the `Set-Cookie: bench=...` the server answers a pairing with. */
    fun tokenFromCookies(headers: List<String>): String? =
        headers.firstNotNullOfOrNull { Regex("""(?:^|;\s*)(?:bench|aic)=([^;]+)""").find(it)?.groupValues?.get(1) }
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    /** A server on this machine's loopback needs no pairing; anything else does. */
    fun isLoopback(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }
}
