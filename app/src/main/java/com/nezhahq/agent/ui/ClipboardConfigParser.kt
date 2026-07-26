package com.nezhahq.agent.ui

/**
 * What the pasted script said about the client UUID.
 *
 * Three outcomes, not two: install scripts routinely ship `NZ_UUID=''` or a bare line continuation
 * where the value belongs, which means "generate one" rather than either a usable UUID or silence.
 * Collapsing that into a nullable string loses the difference between a script that asked for a new
 * UUID and one that never mentioned the subject — and only the latter should leave an existing
 * UUID alone.
 */
sealed interface ParsedUuid {
    /** The script carried a usable UUID. */
    data class Found(val value: String) : ParsedUuid

    /** The script declared a UUID but left it empty; the caller has to generate one. */
    data object Placeholder : ParsedUuid

    /** The script said nothing about a UUID; whatever is already configured still stands. */
    data object Absent : ParsedUuid
}

/**
 * Connection settings read out of a dashboard install script.
 *
 * Every field is optional, because a script may carry only some of them and the caller keeps what
 * it already had for the rest.
 */
data class ParsedClipboardConfig(
    val server: String? = null,
    val port: String? = null,
    val secret: String? = null,
    val uuid: ParsedUuid = ParsedUuid.Absent,
    val useTls: Boolean? = null
)

/**
 * Reads connection settings out of a pasted Nezha install command.
 *
 * Two generations of dashboard script are supported, and a paste may contain both:
 *  - flags, as in `-s host:port -p secret --tls`
 *  - environment variables, as in `NZ_SERVER=host:port NZ_CLIENT_SECRET=… NZ_UUID=… NZ_TLS=true`
 *
 * Environment variables win where the two disagree: a script carrying both is a newer one that kept
 * the old flags for compatibility.
 *
 * Pure on purpose. These patterns used to live inside the view model, which cannot be constructed
 * off a device, so none of them had ever been exercised by a test.
 */
object ClipboardConfigParser {

    private val FLAG_SERVER_WITH_PORT = Regex("-s\\s+([^:\\s]+):(\\d+)")
    private val FLAG_SERVER_ONLY = Regex("-s\\s+([^\\s:]+)")
    private val FLAG_SECRET = Regex("-p\\s+([^\\s]+)")
    private val FLAG_TLS_ON = Regex("(^|\\s)--tls(\\s|$)")
    private val FLAG_TLS_OFF = Regex("(^|\\s)--(no-tls|disable-tls)(\\s|$)")
    private val ENV_SERVER = Regex("NZ_SERVER=([^:\\s]+):(\\d+)")
    private val ENV_SECRET = Regex("NZ_CLIENT_SECRET=([^\\s]+)")
    private val ENV_UUID = Regex("NZ_UUID=([^\\s]+)")
    private val ENV_TLS = Regex("NZ_TLS=([^\\s]+)")
    private val SURROUNDING_QUOTES = Regex("^['\"]|['\"]$")

    fun parse(input: String): ParsedClipboardConfig {
        if (input.isBlank()) return ParsedClipboardConfig()

        var server: String? = null
        var port: String? = null
        var secret: String? = null
        var useTls: Boolean? = null

        val flagServerWithPort = FLAG_SERVER_WITH_PORT.find(input)
        if (flagServerWithPort != null) {
            server = flagServerWithPort.groupValues[1]
            port = flagServerWithPort.groupValues[2]
        } else {
            FLAG_SERVER_ONLY.find(input)?.let { server = it.groupValues[1] }
        }

        FLAG_SECRET.find(input)?.let { secret = it.groupValues[1] }

        if (FLAG_TLS_ON.containsMatchIn(input)) useTls = true
        if (FLAG_TLS_OFF.containsMatchIn(input)) useTls = false

        ENV_SERVER.find(input)?.let {
            server = it.groupValues[1]
            port = it.groupValues[2]
        }
        ENV_SECRET.find(input)?.let { secret = it.groupValues[1] }
        ENV_TLS.find(input)?.let { match ->
            parseBooleanLike(match.groupValues[1])?.let { useTls = it }
        }

        return ParsedClipboardConfig(
            server = server,
            port = port,
            secret = secret,
            uuid = parseUuid(input),
            useTls = useTls
        )
    }

    private fun parseUuid(input: String): ParsedUuid {
        val raw = ENV_UUID.find(input)?.groupValues?.get(1) ?: return ParsedUuid.Absent
        val unquoted = raw.replace(SURROUNDING_QUOTES, "")
        return if (unquoted.isBlank() || unquoted == "\\") {
            ParsedUuid.Placeholder
        } else {
            ParsedUuid.Found(unquoted)
        }
    }

    /** Accepts the spellings shell scripts use for a boolean; null when it is none of them. */
    fun parseBooleanLike(rawValue: String): Boolean? {
        val normalized = rawValue
            .trim()
            .trim('\'', '"')
            .trimEnd(';')
            .lowercase()
        return when (normalized) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }
}
