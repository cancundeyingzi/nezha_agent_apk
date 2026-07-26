package com.nezhahq.agent.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotPathTest {

    @Test
    fun aBlankRequestLandsOnTheGeneratedNameInTheDefaultDirectory() {
        assertEquals("$DEFAULT_DIR/$GENERATED", resolve(""))
        assertEquals("$DEFAULT_DIR/$GENERATED", resolve("   "))
    }

    @Test
    fun aRelativeFileNameResolvesUnderTheDefaultDirectory() {
        assertEquals("$DEFAULT_DIR/shot.png", resolve("shot.png"))
        assertEquals("$DEFAULT_DIR/sub/shot.png", resolve("sub/shot.png"))
    }

    @Test
    fun aRelativeDirectoryGetsTheGeneratedNameAppended() {
        assertEquals("$DEFAULT_DIR/sub/$GENERATED", resolve("sub/"))
    }

    @Test
    fun aRelativePathClimbingOutOfTheDefaultDirectoryIsRejected() {
        assertNull(resolve("../../data/local/tmp/x.png"))
        assertNull(resolve("sub/../../../etc/x.png"))
        assertNull(resolve(".."))
    }

    /** The climb is only rejected when it actually leaves; staying inside must keep working. */
    @Test
    fun aRelativePathThatClimbsBackInsideIsAccepted() {
        assertEquals("$DEFAULT_DIR/shot.png", resolve("sub/../shot.png"))
        assertEquals("$DEFAULT_DIR/shot.png", resolve("./shot.png"))
    }

    @Test
    fun theDefaultDirectoryItselfIsNotAUsableTarget() {
        assertNull(resolve("sub/.."))
    }

    @Test
    fun anAbsolutePathIsHonouredAsGiven() {
        assertEquals("/data/local/tmp/x.png", resolve("/data/local/tmp/x.png"))
        assertEquals("/sdcard/$GENERATED", resolve("/sdcard/"))
    }

    /** Absolute paths are deliberately unconstrained; see resolveScreenshotPath's documentation. */
    @Test
    fun anAbsolutePathMayContainTraversal() {
        assertEquals("/data/x.png", resolve("/sdcard/../data/x.png"))
        assertNull(resolve("/../.."))
    }

    @Test
    fun backslashesAreTreatedAsSeparators() {
        assertEquals("$DEFAULT_DIR/sub/shot.png", resolve("sub\\shot.png"))
        assertNull(resolve("..\\..\\x.png"))
    }

    @Test
    fun redundantSeparatorsAreCollapsed() {
        assertEquals("$DEFAULT_DIR/sub/shot.png", resolve("sub//shot.png"))
        assertEquals("/data/x.png", resolve("//data///x.png"))
    }

    private fun resolve(requested: String): String? = resolveScreenshotPath(
        requestedPath = requested,
        defaultDirectory = DEFAULT_DIR,
        generatedFileName = GENERATED
    )

    private companion object {
        const val DEFAULT_DIR = "/storage/emulated/0"
        const val GENERATED = "nezha_screenshot_20260726_120000.png"
    }
}
