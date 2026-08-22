@file:Suppress("UnsafeCastFromDynamic")

package com.bibleverse.voice

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.fetch.RequestInit

@JsModule("onnxruntime-web")
@JsNonModule
external val ort: dynamic

private const val MODEL_META_URL =
    "https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main/tts_browser_onnx_meta.json"

private const val FP32_GLOBAL =
    "https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main/moss_tts_global_shared.data"
private const val FP32_LOCAL =
    "https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main/moss_tts_local_shared.data"
private const val INT8_GLOBAL =
    "https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/resolve/main/moss_tts_global_shared_int8.data"
private const val INT8_LOCAL =
    "https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/resolve/main/moss_tts_local_fixed_sampled_frame_int8.data"
private const val CODEC_DECODE =
    "https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/resolve/main/moss_audio_tokenizer_decode_shared.data"

private fun status(message: String) {
    (document.getElementById("status") as? HTMLElement)?.textContent = message
}

private fun detail(message: String) {
    (document.getElementById("details") as? HTMLElement)?.textContent = message
}

private fun gate2Detail(message: String) {
    (document.getElementById("gate2-details") as? HTMLElement)?.textContent = message
}

private fun setBadge(id: String, text: String, state: String) {
    val element = document.getElementById(id) as? HTMLElement ?: return
    element.textContent = text
    element.className = "badge $state"
}

private fun describeJsError(error: dynamic): String {
    val name = error?.name?.toString() ?: "UnknownError"
    val message = error?.message?.toString() ?: error?.toString() ?: "unknown error"
    return "$name: $message"
}

private fun emptyRequestInit(): RequestInit = js("({})")
private fun headRequestInit(): RequestInit = js("({ method: 'HEAD', cache: 'no-store' })")

private fun formatContentLength(raw: String?): String {
    val bytes = raw?.toDoubleOrNull() ?: return "접근 가능"
    return when {
        bytes >= 1_000_000_000.0 -> "${(bytes / 1_000_000_000.0).toFixed(2)} GB"
        bytes >= 1_000_000.0 -> "${(bytes / 1_000_000.0).toFixed(1)} MB"
        bytes >= 1_000.0 -> "${(bytes / 1_000.0).toFixed(1)} KB"
        else -> "${bytes.toLong()} B"
    }
}

private fun Double.toFixed(digits: Int): String = asDynamic().toFixed(digits) as String

private fun probeHead(
    url: String,
    onSuccess: (String) -> Unit,
    onFailure: (String) -> Unit,
    onFinished: () -> Unit,
) {
    window.fetch(url, headRequestInit()).then { response ->
        if (!response.ok) {
            throw IllegalStateException("HTTP ${response.status}")
        }
        onSuccess(formatContentLength(response.headers.get("content-length")))
        null
    }.catch { error ->
        onFailure(describeJsError(error))
        null
    }.finally {
        onFinished()
    }
}

fun main() {
    status("Kotlin/JS 런타임 로드 완료 — Gate 1 시작 가능")
    document.documentElement?.setAttribute("data-voice-lab-runtime", "ready")

    val button = document.getElementById("run-preflight") as? HTMLButtonElement
    val sizeButton = document.getElementById("run-size-probe") as? HTMLButtonElement

    if (button == null || sizeButton == null) {
        status("사전검증 실패: 테스트 버튼을 찾을 수 없습니다.")
        return
    }

    val wasmAvailable = js("typeof WebAssembly !== 'undefined'") as Boolean
    val webGpuAvailable = js("typeof navigator !== 'undefined' && 'gpu' in navigator") as Boolean
    val hardwareConcurrency = window.navigator.hardwareConcurrency

    detail(
        "Kotlin/JS ready · WASM=$wasmAvailable · WebGPU=$webGpuAvailable · " +
            "logical cores=$hardwareConcurrency · first test uses WASM single-thread"
    )

    if (!wasmAvailable) {
        status("사전검증 실패: 이 브라우저에서 WebAssembly를 사용할 수 없습니다.")
        button.disabled = true
        return
    }

    button.onclick = {
        status("1/3 ONNX Runtime 초기화 확인 중…")

        var runtimeReady = true
        try {
            ort.env.wasm.numThreads = 1
            ort.env.wasm.proxy = false
        } catch (error: dynamic) {
            runtimeReady = false
            status("ONNX Runtime 설정 실패: ${describeJsError(error)}")
        }

        if (runtimeReady) {
            status("2/3 MOSS-TTS-Nano 공식 브라우저 메타데이터 확인 중…")

            window.fetch(MODEL_META_URL, emptyRequestInit()).then { response ->
                if (!response.ok) {
                    throw IllegalStateException("HTTP ${response.status}")
                }
                response.text()
            }.then { text ->
                val hasPrefill = text.contains("moss_tts_prefill.onnx")
                val hasDecode = text.contains("moss_tts_decode_step.onnx")

                if (!hasPrefill || !hasDecode) {
                    throw IllegalStateException("MOSS browser metadata에 필수 ONNX graph가 없습니다.")
                }

                document.documentElement?.setAttribute("data-voice-lab-gate1", "pass")
                status("3/3 통과 — Kotlin/JS + ONNX Runtime Web + MOSS 공식 모델 경로 준비 완료")
                detail(
                    "WASM single-thread preflight PASS · prefill=$hasPrefill · decode=$hasDecode. " +
                        "Gate 2A에서 FP32와 INT8 다운로드 규모를 비교합니다."
                )
                sizeButton.disabled = false
                gate2Detail("Gate 1 PASS. 수백 MB 본문 다운로드 없이 HEAD 요청으로 후보를 비교할 수 있습니다.")
                null
            }.catch { error ->
                document.documentElement?.setAttribute("data-voice-lab-gate1", "fail")
                status("사전검증 실패: ${describeJsError(error)}")
                null
            }
        }

        null
    }

    sizeButton.onclick = {
        sizeButton.disabled = true
        document.documentElement?.removeAttribute("data-voice-lab-gate2a")
        setBadge("fp32-result", "확인 중", "running")
        setBadge("int8-result", "확인 중", "running")
        setBadge("codec-result", "확인 중", "running")
        gate2Detail("5개 핵심 weight URL을 HEAD 요청으로 확인 중…")

        var completed = 0
        var failures = 0
        var fp32Passed = 0
        var int8Passed = 0
        var codecPassed = 0
        val observed = mutableListOf<String>()

        fun finishOne() {
            completed += 1
            if (completed == 5) {
                if (failures == 0 && fp32Passed == 2 && int8Passed == 2 && codecPassed == 1) {
                    document.documentElement?.setAttribute("data-voice-lab-gate2a", "pass")
                    gate2Detail(
                        "2A PASS · FP32 약 718 MB 대비 INT8 약 243 MB(합성용 decoder 포함). " +
                            "다운로드가 약 66% 작으므로 Gate 2B는 INT8 후보만 실제 session load로 검증합니다. " +
                            "한국어 음질은 아직 검증 전입니다. ${observed.joinToString(" · ")}"
                    )
                } else {
                    document.documentElement?.setAttribute("data-voice-lab-gate2a", "fail")
                    gate2Detail("2A FAIL · 브라우저에서 접근할 수 없는 핵심 자산이 있습니다. 실패=$failures")
                    sizeButton.disabled = false
                }
            }
        }

        fun fail(groupId: String, error: String) {
            failures += 1
            setBadge(groupId, "실패", "fail")
            observed += "실패:$error"
        }

        probeHead(FP32_GLOBAL, {
            fp32Passed += 1
            observed += "FP32-global $it"
            if (fp32Passed == 2) setBadge("fp32-result", "접근 가능", "pass")
        }, { fail("fp32-result", it) }, ::finishOne)

        probeHead(FP32_LOCAL, {
            fp32Passed += 1
            observed += "FP32-local $it"
            if (fp32Passed == 2) setBadge("fp32-result", "접근 가능", "pass")
        }, { fail("fp32-result", it) }, ::finishOne)

        probeHead(INT8_GLOBAL, {
            int8Passed += 1
            observed += "INT8-global $it"
            if (int8Passed == 2) setBadge("int8-result", "접근 가능", "pass")
        }, { fail("int8-result", it) }, ::finishOne)

        probeHead(INT8_LOCAL, {
            int8Passed += 1
            observed += "INT8-local $it"
            if (int8Passed == 2) setBadge("int8-result", "접근 가능", "pass")
        }, { fail("int8-result", it) }, ::finishOne)

        probeHead(CODEC_DECODE, {
            codecPassed += 1
            observed += "codec-decode $it"
            setBadge("codec-result", "접근 가능", "pass")
        }, { fail("codec-result", it) }, ::finishOne)

        null
    }
}
