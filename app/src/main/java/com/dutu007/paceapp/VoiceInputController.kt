package com.dutu007.paceapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

private sealed interface SpeechCandidate {
    data class Component(val component: ComponentName, val pkg: String) : SpeechCandidate
    data object OnDevice : SpeechCandidate
    data object SystemDefault : SpeechCandidate
}

fun buildSystemSpeechIntent(prompt: String = "请说话，识别后可先编辑再发送"): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }
}

fun hasSystemSpeechDialog(context: Context): Boolean {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    return intent.resolveActivity(context.packageManager) != null
}

class VoiceInputController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (List<String>) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit = {},
    private val onRequestSystemDialog: (() -> Unit)? = null,
) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var cancelling = false
    private var ready = false
    private var heardSpeech = false
    private var deliveredFinal = false
    private var remainingCandidates = ArrayDeque<SpeechCandidate>()
    private var currentCandidate: SpeechCandidate? = null
    private val disconnectedCandidates = mutableSetOf<SpeechCandidate>()

    private val isOnDeviceSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    val isAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) ||
            discoverRecognitionServices().isNotEmpty() ||
            isOnDeviceSupported ||
            hasSystemSpeechDialog(context)

    fun start(): Boolean {
        if (!isAvailable) {
            onError("当前设备未开启系统语音识别服务")
            return false
        }
        destroyRecognizer()
        cancelling = false
        ready = false
        heardSpeech = false
        deliveredFinal = false

        val ordered = buildCandidateOrder()
        remainingCandidates = ArrayDeque(ordered)
        if (remainingCandidates.isEmpty()) {
            if (trySystemDialogFallback()) return true
            onError("当前设备未检测到可用的语音识别引擎")
            return false
        }

        // If every inline candidate already failed with disconnect/unavailable in this session,
        // skip straight to the interactive system speech dialog when available.
        if (ordered.all { it in disconnectedCandidates } && hasSystemSpeechDialog(context) && onRequestSystemDialog != null) {
            active = false
            onListeningChanged(false)
            onRequestSystemDialog.invoke()
            return false
        }

        return startNextCandidate()
    }

    fun finish(cancel: Boolean) {
        cancelling = cancel
        val sr = recognizer
        active = false
        onListeningChanged(false)
        if (sr == null) return
        if (cancel) {
            runCatching { sr.cancel() }
            destroyRecognizer()
        } else {
            runCatching { sr.stopListening() }
        }
    }

    fun destroy() {
        cancelling = true
        active = false
        onListeningChanged(false)
        destroyRecognizer()
    }

    private fun startNextCandidate(): Boolean {
        destroyRecognizer()
        while (remainingCandidates.isNotEmpty()) {
            val candidate = remainingCandidates.removeFirst()
            currentCandidate = candidate
            ready = false
            heardSpeech = false
            deliveredFinal = false

            val sr = createRecognizerFor(candidate) ?: continue
            recognizer = sr
            sr.setRecognitionListener(createListener(candidate))

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                if (candidate is SpeechCandidate.OnDevice) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            return try {
                sr.startListening(intent)
                active = true
                onListeningChanged(true)
                true
            } catch (_: Throwable) {
                disconnectedCandidates += candidate
                destroyRecognizer()
                continue
            }
        }
        active = false
        onListeningChanged(false)
        return false
    }

    private fun createRecognizerFor(candidate: SpeechCandidate): SpeechRecognizer? {
        return when (candidate) {
            is SpeechCandidate.Component -> runCatching {
                SpeechRecognizer.createSpeechRecognizer(context, candidate.component)
            }.getOrNull()
            is SpeechCandidate.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            } else {
                null
            }
            is SpeechCandidate.SystemDefault -> runCatching {
                SpeechRecognizer.createSpeechRecognizer(context)
            }.getOrNull()
        }
    }

    private fun createListener(candidate: SpeechCandidate) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            ready = true
        }

        override fun onBeginningOfSpeech() {
            heardSpeech = true
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            active = false
            onListeningChanged(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (cancelling) return
            val first = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!first.isNullOrBlank()) {
                heardSpeech = true
                onPartial(first)
            }
        }

        override fun onResults(results: Bundle?) {
            active = false
            onListeningChanged(false)
            if (cancelling) return
            deliveredFinal = true
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList().orEmpty()
            if (list.isEmpty()) {
                onError("未识别到清晰语音，请重试")
            } else {
                onResult(list)
            }
            destroyRecognizer()
        }

        override fun onError(error: Int) {
            if (cancelling || deliveredFinal) {
                active = false
                onListeningChanged(false)
                destroyRecognizer()
                return
            }

            // Codes 1,2,4,5,11,12,13,14 indicate service disconnected / unreachable / unsupported engine.
            val isEngineFailure = error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_SERVER ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == 11 || error == 12 || error == 13 || error == 14

            if (isEngineFailure) {
                disconnectedCandidates += candidate
                if (!heardSpeech && remainingCandidates.isNotEmpty() && (active || !ready)) {
                    if (startNextCandidate()) {
                        return
                    }
                }
                active = false
                onListeningChanged(false)
                destroyRecognizer()
                if (!heardSpeech && trySystemDialogFallback()) {
                    return
                }
            } else {
                active = false
                onListeningChanged(false)
                destroyRecognizer()
            }

            onError(mapRecognitionError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun trySystemDialogFallback(): Boolean {
        val cb = onRequestSystemDialog ?: return false
        if (!hasSystemSpeechDialog(context)) return false
        cb.invoke()
        return true
    }

    private fun buildCandidateOrder(): List<SpeechCandidate> {
        val services = discoverRecognitionServices()
        val nonGoogle = mutableListOf<SpeechCandidate>()
        val google = mutableListOf<SpeechCandidate>()
        for (comp in services) {
            val item = SpeechCandidate.Component(comp, comp.packageName)
            if (comp.packageName.startsWith("com.google.")) {
                google += item
            } else {
                nonGoogle += item
            }
        }
        val list = mutableListOf<SpeechCandidate>()
        list += nonGoogle
        if (isOnDeviceSupported) {
            list += SpeechCandidate.OnDevice
        }
        list += SpeechCandidate.SystemDefault
        list += google
        return list.sortedBy { if (it in disconnectedCandidates) 1 else 0 }
    }

    private fun discoverRecognitionServices(): List<ComponentName> {
        val pm = context.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val infos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())
        return infos.mapNotNull { info ->
            val svc = info.serviceInfo ?: return@mapNotNull null
            ComponentName(svc.packageName, svc.name)
        }
    }

    private fun mapRecognitionError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "麦克风录音异常"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请先授予麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络超时，请轻点麦克风尝试系统语音或使用键盘输入法语音"
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到清晰语音，请重试"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到说话内容"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正忙，请稍后再试"
        10 -> "语音请求过于频繁，请稍后再试"
        11 -> "系统语音服务未连接 (11)，请轻点麦克风打开系统语音或切换本机语音引擎"
        12, 13 -> "当前系统语音引擎未安装中文离线语言包 ($error)"
        else -> "语音识别失败 ($error)"
    }

    private fun destroyRecognizer() {
        recognizer?.let { sr ->
            runCatching { sr.destroy() }
        }
        recognizer = null
    }
}

@Composable
fun rememberVoiceInputController(
    context: Context,
    onPartial: (String) -> Unit,
    onResult: (List<String>) -> Unit,
    onError: (String) -> Unit,
    onListeningChanged: (Boolean) -> Unit = {},
    onRequestSystemDialog: (() -> Unit)? = null,
): VoiceInputController {
    val latestPartial = rememberUpdatedState(onPartial)
    val latestResult = rememberUpdatedState(onResult)
    val latestError = rememberUpdatedState(onError)
    val latestListening = rememberUpdatedState(onListeningChanged)
    val latestSystemDialog = rememberUpdatedState(onRequestSystemDialog)

    val controller = remember(context) {
        VoiceInputController(
            context = context,
            onPartial = { latestPartial.value(it) },
            onResult = { latestResult.value(it) },
            onError = { latestError.value(it) },
            onListeningChanged = { latestListening.value(it) },
            onRequestSystemDialog = { latestSystemDialog.value?.invoke() },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}
