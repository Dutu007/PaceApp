package com.dutu007.paceapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

class VoiceInputController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (List<String>) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var active by mutableStateOf(false)
    private var cancelling = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(): Boolean {
        if (!isAvailable) {
            onError("当前设备未开启系统语音识别服务")
            return false
        }
        destroyRecognizer()
        cancelling = false
        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (sr == null) {
            onError("无法初始化语音识别")
            return false
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (cancelling) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                active = false
                if (cancelling) return
                val list = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?.take(3)
                    .orEmpty()
                if (list.isEmpty()) {
                    onError("没有听清，请再说一次")
                } else {
                    onResult(list)
                }
            }

            override fun onError(error: Int) {
                active = false
                if (cancelling) return
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "麦克风录音异常"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请先授予麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到清晰语音，请重试"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到说话内容"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正忙，请稍后再试"
                    else -> "语音识别失败 ($error)"
                }
                onError(message)
            }
        })
        recognizer = sr
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        return runCatching {
            sr.startListening(intent)
            active = true
            true
        }.getOrElse {
            active = false
            onError("启动语音识别失败")
            false
        }
    }

    fun finish(cancel: Boolean) {
        cancelling = cancel
        if (!active) return
        active = false
        runCatching {
            if (cancel) recognizer?.cancel() else recognizer?.stopListening()
        }
    }

    fun destroyRecognizer() {
        active = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

@Composable
fun rememberVoiceInputController(
    context: Context,
    onPartial: (String) -> Unit,
    onResult: (List<String>) -> Unit,
    onError: (String) -> Unit,
): VoiceInputController {
    val partialState = rememberUpdatedState(onPartial)
    val resultState = rememberUpdatedState(onResult)
    val errorState = rememberUpdatedState(onError)

    val controller = remember(context) {
        VoiceInputController(
            context = context.applicationContext,
            onPartial = { partialState.value(it) },
            onResult = { resultState.value(it) },
            onError = { errorState.value(it) },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.destroyRecognizer() }
    }
    return controller
}
