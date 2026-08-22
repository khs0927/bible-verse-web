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

private fun status(message: String) {
    (document.getElementById("status") as? HTMLElement)?.textContent = message
}

private fun detail(message: String) {
    (document.getElementById("details") as? HTMLElement)?.textContent = message
}

private fun describeJsError(error: dynamic): String {
    val name = error?.name?.toString() ?: "UnknownError"
    val message = error?.message?.toString() ?: error?.toString() ?: "unknown error"
    return "$name: $message"
}

private fun emptyRequestInit(): RequestInit = js("({})")

fun main() {
    status("Kotlin/JS 런타임 로드 완료 — Gate 1 시작 가능")
    document.documentElement?.setAttribute("data-voice-lab-runtime", "ready")

    val button = document.getElementById("run-preflight") as? HTMLButtonElement
    if (button == null) {
        status("사전검증 실패: 시작 버튼을 찾을 수 없습니다.")
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
                val hasTokenizer = text.contains("tokenizer.model")

                if (!hasPrefill || !hasDecode) {
                    throw IllegalStateException("MOSS browser metadata에 필수 ONNX graph가 없습니다.")
                }

                document.documentElement?.setAttribute("data-voice-lab-gate1", "pass")
                status("3/3 통과 — Kotlin/JS + ONNX Runtime Web + MOSS 공식 모델 경로 준비 완료")
                detail(
                    "WASM single-thread preflight PASS · prefill=$hasPrefill · " +
                        "decode=$hasDecode · tokenizer=$hasTokenizer. " +
                        "다음 단계에서 실제 세션 로드와 짧은 한국어 합성을 연결합니다."
                )
                null
            }.catch { error ->
                document.documentElement?.setAttribute("data-voice-lab-gate1", "fail")
                status("사전검증 실패: ${describeJsError(error)}")
                null
            }
        }

        null
    }
}
