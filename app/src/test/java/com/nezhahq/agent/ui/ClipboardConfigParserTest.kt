package com.nezhahq.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The install scripts users actually paste.
 *
 * These patterns decide which server the agent talks to and with which secret, and until they were
 * lifted out of the view model nothing could reach them: the view model needs an Android
 * `Application` to construct.
 */
class ClipboardConfigParserTest {

    @Test
    fun aBlankPasteYieldsNothing() {
        val parsed = ClipboardConfigParser.parse("   \n  ")

        assertNull(parsed.server)
        assertNull(parsed.port)
        assertNull(parsed.secret)
        assertNull(parsed.useTls)
        assertSame(ParsedUuid.Absent, parsed.uuid)
    }

    @Test
    fun theLegacyFlagFormIsRead() {
        val parsed = ClipboardConfigParser.parse(
            "./nezha-agent -s panel.example.com:5555 -p abc123 --tls"
        )

        assertEquals("panel.example.com", parsed.server)
        assertEquals("5555", parsed.port)
        assertEquals("abc123", parsed.secret)
        assertEquals(true, parsed.useTls)
    }

    @Test
    fun aLegacyServerWithoutAPortLeavesThePortAlone() {
        val parsed = ClipboardConfigParser.parse("-s panel.example.com -p abc123")

        assertEquals("panel.example.com", parsed.server)
        assertNull(parsed.port)
    }

    @Test
    fun bothSpellingsOfTheDisableTlsFlagAreRead() {
        assertEquals(false, ClipboardConfigParser.parse("-s h:1 --no-tls").useTls)
        assertEquals(false, ClipboardConfigParser.parse("-s h:1 --disable-tls").useTls)
    }

    /** `--tls-something-else` is not `--tls`; the flag has to stand alone. */
    @Test
    fun aFlagThatMerelyStartsWithTlsDoesNotEnableIt() {
        assertNull(ClipboardConfigParser.parse("-s h:1 --tlsx").useTls)
    }

    @Test
    fun theEnvironmentVariableFormIsRead() {
        val parsed = ClipboardConfigParser.parse(
            "NZ_SERVER=panel.example.com:8008 NZ_CLIENT_SECRET=s3cret " +
                "NZ_UUID=11111111-2222-3333-4444-555555555555 NZ_TLS=true ./install.sh"
        )

        assertEquals("panel.example.com", parsed.server)
        assertEquals("8008", parsed.port)
        assertEquals("s3cret", parsed.secret)
        assertEquals(true, parsed.useTls)
        assertEquals(ParsedUuid.Found("11111111-2222-3333-4444-555555555555"), parsed.uuid)
    }

    /**
     * A newer script keeps the old flags for compatibility, so where the two disagree the
     * environment variables are the ones that describe the panel the user is installing against.
     */
    @Test
    fun environmentVariablesOverrideTheLegacyFlags() {
        val parsed = ClipboardConfigParser.parse(
            "-s old.example.com:1111 -p oldsecret --no-tls " +
                "NZ_SERVER=new.example.com:2222 NZ_CLIENT_SECRET=newsecret NZ_TLS=true"
        )

        assertEquals("new.example.com", parsed.server)
        assertEquals("2222", parsed.port)
        assertEquals("newsecret", parsed.secret)
        assertEquals(true, parsed.useTls)
    }

    @Test
    fun aQuotedUuidIsUnwrapped() {
        assertEquals(
            ParsedUuid.Found("abc-def"),
            ClipboardConfigParser.parse("NZ_UUID='abc-def'").uuid
        )
        assertEquals(
            ParsedUuid.Found("abc-def"),
            ClipboardConfigParser.parse("NZ_UUID=\"abc-def\"").uuid
        )
    }

    /**
     * Scripts ship these where a UUID belongs, and both mean "generate one" — distinct from saying
     * nothing, which must leave an already-configured UUID untouched.
     */
    @Test
    fun anEmptyOrContinuedUuidIsAPlaceholderRatherThanAValue() {
        assertSame(ParsedUuid.Placeholder, ClipboardConfigParser.parse("NZ_UUID=''").uuid)
        assertSame(ParsedUuid.Placeholder, ClipboardConfigParser.parse("NZ_UUID=\"\"").uuid)
        assertSame(ParsedUuid.Placeholder, ClipboardConfigParser.parse("NZ_UUID=\\").uuid)
    }

    @Test
    fun aScriptWithoutAUuidReportsAbsenceRatherThanAPlaceholder() {
        assertSame(
            ParsedUuid.Absent,
            ClipboardConfigParser.parse("NZ_SERVER=h:1 NZ_CLIENT_SECRET=s").uuid
        )
    }

    @Test
    fun theBooleanSpellingsShellScriptsUseAreAccepted() {
        listOf("true", "1", "yes", "on", "TRUE", "'true'", "\"on\"", "true;").forEach {
            assertEquals("expected $it to read as true", true, parseBoolean(it))
        }
        listOf("false", "0", "no", "off", "FALSE", "'false'", "\"off\"", "false;").forEach {
            assertEquals("expected $it to read as false", false, parseBoolean(it))
        }
    }

    @Test
    fun anUnrecognisedBooleanIsLeftUndecided() {
        assertNull(parseBoolean("maybe"))
        assertNull(parseBoolean(""))
    }

    /** An undecidable NZ_TLS must not silently turn TLS off. */
    @Test
    fun anUnrecognisedTlsValueLeavesTheSettingAlone() {
        assertNull(ClipboardConfigParser.parse("NZ_SERVER=h:1 NZ_TLS=maybe").useTls)
        assertEquals(true, ClipboardConfigParser.parse("-s h:1 --tls NZ_TLS=maybe").useTls)
    }

    private fun parseBoolean(raw: String): Boolean? = ClipboardConfigParser.parseBooleanLike(raw)
}
