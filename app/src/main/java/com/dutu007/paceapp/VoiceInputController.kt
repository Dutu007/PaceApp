package com.dutu007.paceapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

fun buildWavBytes(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val totalDataLen = pcmData.size
    val totalChunkLen = 36 + totalDataLen

    val out = ByteArray(44 + totalDataLen)
    val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray(Charsets.US_ASCII))
    buf.putInt(totalChunkLen)
    buf.put("WAVE".toByteArray(Charsets.US_ASCII))
    buf.put("fmt ".toByteArray(Charsets.US_ASCII))
    buf.putInt(16) // PCM subchunk size
    buf.putShort(1) // PCM format = 1
    buf.putShort(channels.toShort())
    buf.putInt(sampleRate)
    buf.putInt(byteRate)
    buf.putShort(blockAlign.toShort())
    buf.putShort(bitsPerSample.toShort())
    buf.put("data".toByteArray(Charsets.US_ASCII))
    buf.putInt(totalDataLen)
    System.arraycopy(pcmData, 0, out, 44, totalDataLen)
    return out
}

class VoiceInputController(
    private val context: Context,
    private val onAmplitude: (Float) -> Unit,
    private val onAudioReady: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var recording = false
    @Volatile
    private var cancelling = false
    private var workerThread: Thread? = null
    private var startTimeMs = 0L

    val isAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("请先授予麦克风权限")
            return false
        }
        if (recording) return true

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError("当前设备音频录音初始化失败")
            return false
        }
        val bufSize = maxOf(minBuf * 2, sampleRate) // >= 0.5s buffer

        val recorder = createBestAudioRecord(sampleRate, bufSize)
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            onError("麦克风被其他应用占用或无法启动录音")
            return false
        }

        cancelling = false
        recording = true
        startTimeMs = System.currentTimeMillis()

        workerThread = thread(start = true, name = "PaceVoiceRecorder") {
            val pcmStream = ByteArrayOutputStream(sampleRate * 8)
            val shortBuf = ShortArray(bufSize / 2)
            val byteBuf = ByteArray(shortBuf.size * 2)
            val maxBytes = sampleRate * 2 * 90 // cap 90s (~2.88 MB)
            var peakAmplitude = 0f

            try {
                recorder.startRecording()
                while (recording && !cancelling && pcmStream.size() < maxBytes) {
                    val readShorts = recorder.read(shortBuf, 0, shortBuf.size)
                    if (readShorts < 0) break
                    if (readShorts == 0) continue

                    var sumSq = 0.0
                    var outIdx = 0
                    for (i in 0 until readShorts) {
                        val s = shortBuf[i]
                        val norm = s / 32768.0
                        sumSq += norm * norm
                        byteBuf[outIdx++] = (s.toInt() and 0xFF).toByte()
                        byteBuf[outIdx++] = ((s.toInt() shr 8) and 0xFF).toByte()
                    }
                    pcmStream.write(byteBuf, 0, outIdx)

                    val rms = sqrt(sumSq / readShorts).toFloat()
                    val normalized = (rms * 4.2f).coerceIn(0f, 1f)
                    if (normalized > peakAmplitude) peakAmplitude = normalized
                    mainHandler.post {
                        if (recording && !cancelling) onAmplitude(normalized)
                    }
                }
            } catch (t: Throwable) {
                if (!cancelling) {
                    mainHandler.post { onError("录音异常：${t.message ?: "请重试"}") }
                }
                cancelling = true
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }

            if (cancelling) {
                return@thread
            }

            val pcm = pcmStream.toByteArray()
            val durationMs = System.currentTimeMillis() - startTimeMs
            if (durationMs < 350L || pcm.size < 8000) {
                mainHandler.post { onError("说话时间太短，请按住多说一点") }
                return@thread
            }
            if (peakAmplitude < 0.004f) {
                mainHandler.post { onError("未检测到有效声音，请靠近麦克风重试") }
                return@thread
            }

            val wav = buildWavBytes(pcm, sampleRate = sampleRate)
            mainHandler.post { onAudioReady(wav) }
        }
        return true
    }

    fun finish(cancel: Boolean) {
        cancelling = cancel
        recording = false
        onAmplitude(0f)
    }

    fun destroy() {
        cancelling = true
        recording = false
        runCatching { workerThread?.join(150) }
        workerThread = null
    }

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(sampleRate: Int, bufSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }
}

@Composable
fun rememberVoiceInputController(
    context: Context,
    onAmplitude: (Float) -> Unit,
    onAudioReady: (ByteArray) -> Unit,
    onError: (String) -> Unit,
): VoiceInputController {
    val latestAmp = rememberUpdatedState(onAmplitude)
    val latestReady = rememberUpdatedState(onAudioReady)
    val latestError = rememberUpdatedState(onError)

    val controller = remember(context) {
        VoiceInputController(
            context = context,
            onAmplitude = { latestAmp.value(it) },
            onAudioReady = { latestReady.value(it) },
            onError = { latestError.value(it) },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}
