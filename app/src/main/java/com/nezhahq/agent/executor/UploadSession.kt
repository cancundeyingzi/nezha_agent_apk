package com.nezhahq.agent.executor

import java.io.File
import java.io.FileOutputStream

internal sealed class UploadWriteResult {
    data object AwaitingMore : UploadWriteResult()
    data object Complete : UploadWriteResult()
    data class Rejected(val message: String) : UploadWriteResult()
}

/**
 * Owns one file upload lifecycle: declared size, received bytes, temp file and stream.
 * Oversend is rejected before writing so malformed peers cannot poison the cache file.
 */
internal class UploadSession private constructor(
    val targetPath: String,
    val declaredSize: Long,
    val cacheFile: File,
    private val stream: FileOutputStream
) {
    @Volatile
    var received: Long = 0L
        private set

    val isComplete: Boolean
        get() = received == declaredSize

    @Synchronized
    fun writeChunk(data: ByteArray): UploadWriteResult {
        if (data.isEmpty()) {
            return if (isComplete) UploadWriteResult.Complete else UploadWriteResult.AwaitingMore
        }

        val nextReceived = received + data.size.toLong()
        if (nextReceived > declaredSize) {
            return UploadWriteResult.Rejected(
                "Upload data exceeds declared size: declared=$declaredSize, received=$received, chunk=${data.size}"
            )
        }

        stream.write(data)
        received = nextReceived
        return if (isComplete) {
            stream.flush()
            stream.close()
            UploadWriteResult.Complete
        } else {
            UploadWriteResult.AwaitingMore
        }
    }

    @Synchronized
    fun close() {
        stream.close()
    }

    @Synchronized
    fun abort() {
        try { close() } catch (_: Exception) {}
        try { cacheFile.delete() } catch (_: Exception) {}
    }

    companion object {
        const val MAX_UPLOAD_BYTES: Long = 100L * 1024L * 1024L

        fun create(targetPath: String, declaredSize: Long, cacheFile: File): UploadSession {
            validateDeclaredSize(declaredSize)
            cacheFile.parentFile?.mkdirs()
            return UploadSession(targetPath, declaredSize, cacheFile, FileOutputStream(cacheFile))
        }

        fun validateDeclaredSize(declaredSize: Long) {
            require(declaredSize >= 0L) { "Upload size must not be negative." }
            require(declaredSize <= MAX_UPLOAD_BYTES) {
                "Upload size exceeds ${MAX_UPLOAD_BYTES / 1024 / 1024}MiB limit."
            }
        }
    }
}
