package com.nezhahq.agent.executor

import android.content.ContextWrapper
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import proto.Nezha
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FileManagerProtocolTest {

    @Test
    fun `second header follows all bytes declared by first header`() = runBlocking {
        val firstBytes = ByteArray(BUFFER_SIZE + 17) { (it % 251).toByte() }
        val secondBytes = byteArrayOf(9, 8, 7, 6)
        val source = RecordingDownloadSource(
            mapOf("first" to firstBytes, "second" to secondBytes)
        )
        val output = Collections.synchronizedList(mutableListOf<ByteArray>())
        val manager = FileManager(
            context = ContextWrapper(null),
            streamId = "test-stream",
            openIoStream = successfulSession(
                downloads = listOf("first", "second"),
                expectedOutputFrames = 6,
                output = output
            ),
            downloadSourceOverride = source
        )

        withTimeout(TEST_TIMEOUT_MS) { manager.run() }

        val protocolFrames = output.drop(1)
        assertDownload(protocolFrames, 0, firstBytes).also { secondHeaderIndex ->
            assertDownload(protocolFrames, secondHeaderIndex, secondBytes)
        }
        assertEquals(listOf("first", "second"), source.openedPaths)
        assertTrue(source.openedStreams.all { it.closed })
    }

    @Test
    fun `cancellation closes first stream and never opens second`() = runBlocking {
        val firstStream = BlockingInputStream()
        val source = object : DownloadFileSource {
            val openedPaths = Collections.synchronizedList(mutableListOf<String>())

            override suspend fun size(path: String): Long = 1

            override suspend fun open(path: String): InputStream {
                openedPaths += path
                return if (path == "first") firstStream else ByteArrayInputStream(byteArrayOf(1))
            }
        }
        val manager = FileManager(
            context = ContextWrapper(null),
            streamId = "test-stream",
            openIoStream = cancelledSession(listOf("first", "second")),
            downloadSourceOverride = source
        )
        val session = launch { manager.run() }

        withTimeout(TEST_TIMEOUT_MS) { firstStream.readStarted.await() }
        session.cancel()
        withTimeout(TEST_TIMEOUT_MS) { session.join() }

        assertTrue(firstStream.closed)
        assertEquals(listOf("first"), source.openedPaths)
    }

    @Test
    fun `stalled outgoing stream bounds download read ahead`() = runBlocking {
        val firstRead = CompletableDeferred<Unit>()
        val readCount = AtomicInteger()
        val source = object : DownloadFileSource {
            override suspend fun size(path: String): Long = (BUFFER_SIZE * 10L)

            override suspend fun open(path: String): InputStream =
                CountingInputStream(
                    remainingBytes = BUFFER_SIZE * 10,
                    readCount = readCount,
                    firstRead = firstRead
                )
        }
        val manager = FileManager(
            context = ContextWrapper(null),
            streamId = "bounded-output",
            openIoStream = {
                flow {
                    emit(downloadRequest("large"))
                    awaitCancellation()
                }
            },
            downloadSourceOverride = source
        )
        val session = launch { manager.run() }

        try {
            withTimeout(TEST_TIMEOUT_MS) { firstRead.await() }
            delay(100)
            assertEquals(
                "download continued reading while its bounded output channel was full",
                1,
                readCount.get()
            )
        } finally {
            session.cancel()
            withTimeout(TEST_TIMEOUT_MS) { session.join() }
        }
    }

    private fun successfulSession(
        downloads: List<String>,
        expectedOutputFrames: Int,
        output: MutableList<ByteArray>
    ): (Flow<Nezha.IOStreamData>) -> Flow<Nezha.IOStreamData> = { outgoing ->
        channelFlow {
            val outputComplete = CompletableDeferred<Unit>()
            val outputJob = launch {
                outgoing.collect { message ->
                    val bytes = message.data.toByteArray()
                    if (bytes.isNotEmpty()) {
                        output += bytes
                        if (output.size == expectedOutputFrames) outputComplete.complete(Unit)
                    }
                }
            }
            try {
                downloads.forEach { send(downloadRequest(it)) }
                withTimeout(TEST_TIMEOUT_MS) { outputComplete.await() }
            } finally {
                outputJob.cancelAndJoinSafely()
            }
        }
    }

    private fun cancelledSession(
        downloads: List<String>
    ): (Flow<Nezha.IOStreamData>) -> Flow<Nezha.IOStreamData> = { outgoing ->
        channelFlow {
            val outputJob = launch { outgoing.collect {} }
            try {
                downloads.forEach { send(downloadRequest(it)) }
                withTimeout(TEST_TIMEOUT_MS) { awaitCancellation() }
            } finally {
                outputJob.cancelAndJoinSafely()
            }
        }
    }

    private suspend fun Job.cancelAndJoinSafely() {
        cancel()
        withContext(NonCancellable) {
            withTimeout(TEST_TIMEOUT_MS) { join() }
        }
    }

    private fun assertDownload(
        frames: List<ByteArray>,
        headerIndex: Int,
        expectedData: ByteArray
    ): Int {
        val header = frames[headerIndex]
        assertTrue(header.copyOfRange(0, 4).contentEquals(FILE_DATA_MAGIC))
        val declaredSize = ByteBuffer.wrap(header, 4, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(expectedData.size.toLong(), declaredSize)

        var frameIndex = headerIndex + 1
        var received = 0
        val data = ByteArray(expectedData.size)
        while (received < declaredSize) {
            val frame = frames[frameIndex++]
            assertFalse(
                "next header arrived before declared data was complete",
                frame.size >= FILE_DATA_MAGIC.size
                        && frame.copyOfRange(0, FILE_DATA_MAGIC.size).contentEquals(FILE_DATA_MAGIC)
            )
            frame.copyInto(data, destinationOffset = received)
            received += frame.size
        }
        assertEquals(declaredSize, received.toLong())
        assertArrayEquals(expectedData, data)
        return frameIndex
    }

    private fun downloadRequest(path: String): Nezha.IOStreamData =
        Nezha.IOStreamData.newBuilder()
            .setData(ByteString.copyFrom(byteArrayOf(0x01) + path.toByteArray()))
            .build()

    private class RecordingDownloadSource(
        private val files: Map<String, ByteArray>
    ) : DownloadFileSource {
        val openedPaths = Collections.synchronizedList(mutableListOf<String>())
        val openedStreams = Collections.synchronizedList(mutableListOf<CloseTrackingInputStream>())

        override suspend fun size(path: String): Long? = files[path]?.size?.toLong()

        override suspend fun open(path: String): InputStream? {
            val bytes = files[path] ?: return null
            openedPaths += path
            return CloseTrackingInputStream(bytes).also { openedStreams += it }
        }
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        @Volatile var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CompletableDeferred<Unit>()
        private val closeSignal = CountDownLatch(1)

        @Volatile var closed = false
            private set

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readStarted.complete(Unit)
            if (!closeSignal.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("cancellation did not close the blocked download")
            }
            throw IOException("stream closed")
        }

        override fun close() {
            closed = true
            closeSignal.countDown()
        }
    }

    private class CountingInputStream(
        remainingBytes: Int,
        private val readCount: AtomicInteger,
        private val firstRead: CompletableDeferred<Unit>
    ) : InputStream() {
        private var remaining = remainingBytes

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return -1
            val bytesRead = minOf(length, remaining)
            remaining -= bytesRead
            readCount.incrementAndGet()
            firstRead.complete(Unit)
            return bytesRead
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val TEST_TIMEOUT_MS = 5_000L
        val FILE_DATA_MAGIC = byteArrayOf(0x4E, 0x5A, 0x54, 0x44)
    }
}
