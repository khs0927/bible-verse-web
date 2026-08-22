@file:Suppress("UnsafeCastFromDynamic")
@file:OptIn(ExperimentalStdlibApi::class)

package com.bibleverse.voice

import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.EagerInitialization
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.fetch.RequestInit

private const val TOKENIZER_URL =
    "https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main/tokenizer.model"
internal const val KOREAN_TEST_SENTENCE = "하나님은 너를 정말 사랑하신단다."

internal var koreanGateTokenIds: IntArray? = null

private fun tokenizerRequestInit(): RequestInit = js("({ cache: 'no-store' })")

private fun tokenizerDetail(message: String) {
    (document.getElementById("gate3a-details") as? HTMLElement)?.textContent = message
}

private fun tokenizerBadge(text: String, state: String) {
    val element = document.getElementById("tokenizer-result") as? HTMLElement ?: return
    element.textContent = text
    element.className = "badge $state"
}

private fun tokenizerError(error: dynamic): String {
    val name = error?.name?.toString() ?: "UnknownError"
    val message = error?.message?.toString() ?: error?.toString() ?: "unknown error"
    return "$name: $message"
}

@EagerInitialization
private val tokenizerGateInstaller = run {
    val button = document.getElementById("run-tokenizer-probe") as? HTMLButtonElement

    if (button != null) {
        button.onclick = {
            button.disabled = true
            koreanGateTokenIds = null
            document.documentElement?.removeAttribute("data-voice-lab-gate3a")
            tokenizerBadge("다운로드 중", "running")
            tokenizerDetail("공식 tokenizer.model 약 471 KB 다운로드 중…")

            val startedAt = window.performance.now()
            var processor: SentencePieceProcessor? = null

            window.fetch(TOKENIZER_URL, tokenizerRequestInit()).then { response ->
                if (!response.ok) {
                    throw IllegalStateException("tokenizer HTTP ${response.status}")
                }
                response.arrayBuffer()
            }.then { buffer ->
                val byteLength = (buffer.byteLength as Number).toInt()
                if (byteLength < 100_000) {
                    throw IllegalStateException("tokenizer.model size is unexpectedly small: $byteLength")
                }

                tokenizerDetail("tokenizer.model ${byteLength / 1024} KB 완료 · SentencePiece WASM 초기화 중…")
                val instance = SentencePieceProcessor()
                processor = instance
                instance.loadFromB64StringModel(arrayBufferToBase64(buffer))
            }.then {
                val instance = processor ?: throw IllegalStateException("SentencePiece processor was not initialized")
                val ids = dynamicIntArray(instance.encodeIds(KOREAN_TEST_SENTENCE))
                val pieces = dynamicStringArray(instance.encodePieces(KOREAN_TEST_SENTENCE))

                if (ids.isEmpty()) {
                    throw IllegalStateException("한국어 문장이 0개 토큰으로 변환되었습니다.")
                }
                if (pieces.isEmpty()) {
                    throw IllegalStateException("SentencePiece piece 결과가 비어 있습니다.")
                }
                if (ids.size != pieces.size) {
                    throw IllegalStateException("token/piece count mismatch: ${ids.size}/${pieces.size}")
                }
                if (ids.any { it < 0 }) {
                    throw IllegalStateException("음수 token id가 생성되었습니다.")
                }

                koreanGateTokenIds = ids
                val elapsedMs = window.performance.now() - startedAt
                val previewIds = ids.take(12).joinToString(",")
                val previewPieces = pieces.take(8).joinToString(" | ")

                document.documentElement?.setAttribute("data-voice-lab-gate3a", "pass")
                document.documentElement?.setAttribute("data-voice-lab-gate3a-token-count", ids.size.toString())
                tokenizerBadge("PASS", "pass")
                tokenizerDetail(
                    "3A PASS · 한국어 ${ids.size} tokens · ${elapsedMs.asDynamic().toFixed(0)}ms · " +
                        "ids=[$previewIds] · pieces=$previewPieces. 다음은 INT8 decode-step + Xiaoyu builtin voice + 실제 WAV 합성입니다."
                )
                button.disabled = false
                null
            }.catch { error ->
                koreanGateTokenIds = null
                document.documentElement?.setAttribute("data-voice-lab-gate3a", "fail")
                tokenizerBadge("실패", "fail")
                tokenizerDetail("3A FAIL · ${tokenizerError(error)}")
                button.disabled = false
                null
            }

            null
        }
    }

    true
}
