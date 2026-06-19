package com.nezhahq.agent.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exactSizeUploadCompletesAcrossMultipleChunks() {
        val cacheFile = temp.newFile("upload.tmp")
        val session = UploadSession.create("/sdcard/target.bin", 5L, cacheFile)

        assertTrue(session.writeChunk(byteArrayOf(1, 2)) is UploadWriteResult.AwaitingMore)
        assertTrue(session.writeChunk(byteArrayOf(3, 4, 5)) is UploadWriteResult.Complete)

        assertEquals(5L, session.received)
        assertEquals(5L, cacheFile.length())
    }

    @Test
    fun oversendIsRejectedBeforeWritingChunk() {
        val cacheFile = temp.newFile("upload.tmp")
        val session = UploadSession.create("/sdcard/target.bin", 3L, cacheFile)

        assertTrue(session.writeChunk(byteArrayOf(1, 2)) is UploadWriteResult.AwaitingMore)
        val result = session.writeChunk(byteArrayOf(3, 4))

        assertTrue(result is UploadWriteResult.Rejected)
        assertEquals(2L, session.received)
        assertEquals(2L, cacheFile.length())
        session.abort()
    }

    @Test
    fun zeroByteUploadIsImmediatelyComplete() {
        val cacheFile = temp.newFile("empty.tmp")
        val session = UploadSession.create("/sdcard/empty.bin", 0L, cacheFile)

        assertTrue(session.isComplete)
        assertTrue(session.writeChunk(ByteArray(0)) is UploadWriteResult.Complete)
        assertEquals(0L, cacheFile.length())
        session.close()
    }

    @Test
    fun invalidDeclaredSizesAreRejectedBeforeFileOpen() {
        val negativeFile = temp.newFile("negative.tmp")
        val tooLargeFile = temp.newFile("too-large.tmp")

        assertThrows(IllegalArgumentException::class.java) {
            UploadSession.create("/sdcard/negative.bin", -1L, negativeFile)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UploadSession.create(
                "/sdcard/too-large.bin",
                UploadSession.MAX_UPLOAD_BYTES + 1L,
                tooLargeFile
            )
        }
    }

    @Test
    fun abortClosesAndDeletesCacheFile() {
        val cacheFile = temp.newFile("abort.tmp")
        val session = UploadSession.create("/sdcard/abort.bin", 4L, cacheFile)

        session.writeChunk(byteArrayOf(1, 2))
        session.abort()

        assertFalse(cacheFile.exists())
    }
}
