package com.nezhahq.agent.service.keepalive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nezhahq.agent.util.Logger
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface AudioOutput {
    val bufferSizeSamples: Int
    fun start()
    fun write(samples: ShortArray): Int
    fun interrupt()
    fun release()
}

internal fun interface AudioOutputFactory {
    fun create(): AudioOutput
}

internal class AudioKeepAlive(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val outputFactory: AudioOutputFactory = AndroidAudioOutputFactory()
) : KeepAliveResource {
    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private var writerJob: Job? = null
    private var activeOutput: AudioOutput? = null

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    override suspend fun close() = stop()

    private suspend fun start() = lifecycleMutex.withLock {
        val current = synchronized(stateLock) { writerJob }
        if (current?.isActive == true) return@withLock
        current?.join()

        val launched = scope.launch(dispatcher) { runWriter() }
        synchronized(stateLock) { writerJob = launched }
        launched.invokeOnCompletion {
            synchronized(stateLock) {
                if (writerJob === launched) writerJob = null
            }
        }
    }

    private suspend fun stop() = lifecycleMutex.withLock {
        val current = synchronized(stateLock) { writerJob } ?: return@withLock
        current.cancel()
        synchronized(stateLock) { activeOutput }?.let { output ->
            runCatching { output.interrupt() }
                .onFailure { Logger.e("$TAG: 中断音频写入异常", it) }
        }
        current.cancelAndJoin()
        synchronized(stateLock) {
            if (writerJob === current) writerJob = null
        }
    }

    private suspend fun runWriter() {
        var output: AudioOutput? = null
        try {
            output = outputFactory.create()
            synchronized(stateLock) { activeOutput = output }
            output.start()
            writeAudio(output)
        } catch (e: Exception) {
            Logger.e("$TAG: 音频保活会话失败", e)
        } finally {
            output?.let {
                runCatching { it.interrupt() }
                    .onFailure { error -> Logger.e("$TAG: 停止音频异常", error) }
                runCatching { it.release() }
                    .onFailure { error -> Logger.e("$TAG: 释放音频异常", error) }
            }
            synchronized(stateLock) {
                if (activeOutput === output) activeOutput = null
            }
        }
    }

    private suspend fun writeAudio(output: AudioOutput) {
        val samples = ShortArray(output.bufferSizeSamples)
        var phase = 0.0
        while (kotlin.coroutines.coroutineContext.isActive) {
            phase = fillSamples(samples, phase)
            val result = output.write(samples)
            if (result < 0) throw IOException("AudioTrack.write failed with code $result")
        }
    }

    private fun fillSamples(samples: ShortArray, initialPhase: Double): Double {
        if (Random.nextFloat() < 0.05f) {
            samples.fill(0)
            return initialPhase
        }

        val frequency = 18.0 + Random.nextDouble() * 4.0
        val amplitude = 5 + Random.nextInt(11)
        var phase = initialPhase
        samples.indices.forEach { index ->
            samples[index] = (sin(phase) * amplitude).toInt().toShort()
            phase += 2.0 * PI * frequency / SAMPLE_RATE
        }
        return phase % (2.0 * PI)
    }

    private companion object {
        const val TAG = "AudioKeepAlive"
        const val SAMPLE_RATE = 8_000
    }
}

private class AndroidAudioOutputFactory : AudioOutputFactory {
    override fun create(): AudioOutput {
        val bufferSizeBytes = AudioTrack.getMinBufferSize(
            8_000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(bufferSizeBytes > 0) { "Invalid AudioTrack buffer size: $bufferSizeBytes" }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(8_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return AndroidAudioOutput(track, (bufferSizeBytes / Short.SIZE_BYTES).coerceAtLeast(1))
    }
}

private class AndroidAudioOutput(
    private val track: AudioTrack,
    override val bufferSizeSamples: Int
) : AudioOutput {
    override fun start() = track.play()

    override fun write(samples: ShortArray): Int =
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)

    override fun interrupt() = track.stop()

    override fun release() = track.release()
}
