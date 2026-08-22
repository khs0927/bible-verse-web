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
private const val INT8_BASE =
    "https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/resolve/main"
private const val INT8_PREFILL = "$INT8_BASE/moss_tts_prefill.onnx"
private const val INT8_GLOBAL = "$INT8_BASE/moss_tts_global_shared_int8.data"
private const val INT8_LOCAL_GRAPH = "$INT8_BASE/moss_tts_local_fixed_sampled_frame.onnx"
private const val INT8_LOCAL = "$INT8_BASE/moss_tts_local_fixed_sampled_frame_int8.data"
private const val CODEC_BASE =
    "https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/resolve/main"
private const val CODEC_DECODE_GRAPH = "$CODEC_BASE/moss_audio_tokenizer_decode_full.onnx"
private const val CODEC_DECODE = "$CODEC_BASE/moss_audio_tokenizer_decode_shared.data"
private const val ORT_WASM_BASE =
    "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.27.0/dist/"

private var int8PrefillSession: dynamic = null
private var int8LocalSession: dynamic = null
private var codecDecodeSession: dynamic = null

private fun status(message: String) {
    (document.getElementById("status") as? HTMLElement)?.textContent = message
}

private fun detail(message: String) {
    (document.getElementById("details") as? HTMLElement)?.textContent = message
}

private fun gate2Detail(message: String) {
    (document.getElementById("gate2-details") as? HTMLElement)?.textContent = message
}

private fun gate2bDetail(message: String) {
    (document.getElementById("gate2b-details") as? HTMLElement)?.textContent = message
}

private fun gate2cDetail(message: String) {
    (document.getElementById("gate2c-details") as? HTMLElement)?.textContent = message
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
private fun toUint8Array(buffer: dynamic): dynamic = js("new Uint8Array(buffer)")

private fun createSessionPromise(modelBytes: dynamic, weightBytes: dynamic, weightPath: String): dynamic {
    val externalDataEntry = js("({})")
    externalDataEntry.path = weightPath
    externalDataEntry.data = weightBytes

    val sessionOptions = js("({})")
    sessionOptions.executionProviders = arrayOf("wasm")
    sessionOptions.graphOptimizationLevel = "all"
    sessionOptions.executionMode = "sequential"
    sessionOptions.externalData = arrayOf(externalDataEntry)

    return ort.InferenceSession.create(modelBytes, sessionOptions)
}

private fun formatContentLength(raw: String?): String {
    val bytes = raw?.toDoubleOrNull() ?: return "접근 가능"
    return formatBytes(bytes)
}

private fun formatBytes(bytes: Double): String = when {
    bytes >= 1_000_000_000.0 -> "${(bytes / 1_000_000_000.0).toFixed(2)} GB"
    bytes >= 1_000_000.0 -> "${(bytes / 1_000_000.0).toFixed(1)} MB"
    bytes >= 1_000.0 -> "${(bytes / 1_000.0).toFixed(1)} KB"
    else -> "${bytes.toLong()} B"
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
    val sessionButton = document.getElementById("run-session-probe") as? HTMLButtonElement
    val coreButton = document.getElementById("run-core-sessions") as? HTMLButtonElement

    if (button == null || sizeButton == null || sessionButton == null || coreButton == null) {
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
            ort.env.wasm.wasmPaths = ORT_WASM_BASE
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
        sessionButton.disabled = true
        coreButton.disabled = true
        document.documentElement?.removeAttribute("data-voice-lab-gate2a")
        document.documentElement?.removeAttribute("data-voice-lab-gate2b")
        document.documentElement?.removeAttribute("data-voice-lab-gate2c")
        setBadge("fp32-result", "확인 중", "running")
        setBadge("int8-result", "확인 중", "running")
        setBadge("codec-result", "확인 중", "running")
        setBadge("session-result", "대기", "pending")
        setBadge("local-session-result", "대기", "pending")
        setBadge("codec-session-result", "대기", "pending")
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
                    sessionButton.disabled = false
                    gate2bDetail("Gate 2A PASS. INT8 prefill + 111 MB shared weight 실제 로드를 시작할 수 있습니다.")
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

    sessionButton.onclick = {
        sessionButton.disabled = true
        coreButton.disabled = true
        document.documentElement?.removeAttribute("data-voice-lab-gate2b")
        document.documentElement?.removeAttribute("data-voice-lab-gate2c")
        setBadge("session-result", "다운로드 중", "running")
        setBadge("local-session-result", "대기", "pending")
        setBadge("codec-session-result", "대기", "pending")
        gate2bDetail("INT8 prefill graph 다운로드 중…")

        val startedAt = window.performance.now()
        var graphReadyAt = startedAt
        var weightReadyAt = startedAt
        var modelBytes: dynamic = null

        window.fetch(INT8_PREFILL, emptyRequestInit()).then { response ->
            if (!response.ok) throw IllegalStateException("prefill HTTP ${response.status}")
            response.arrayBuffer()
        }.then { buffer ->
            modelBytes = toUint8Array(buffer)
            graphReadyAt = window.performance.now()
            gate2bDetail(
                "prefill ${formatBytes((modelBytes.byteLength as Number).toDouble())} 완료 · " +
                    "INT8 shared weight 111 MB 다운로드 중…"
            )
            window.fetch(INT8_GLOBAL, emptyRequestInit())
        }.then { response ->
            if (!response.ok) throw IllegalStateException("weight HTTP ${response.status}")
            response.arrayBuffer()
        }.then { buffer ->
            val weightBytes = toUint8Array(buffer)
            weightReadyAt = window.performance.now()
            setBadge("session-result", "세션 생성 중", "running")
            gate2bDetail(
                "weight ${formatBytes((weightBytes.byteLength as Number).toDouble())} 완료 · " +
                    "WASM single-thread InferenceSession.create 실행 중…"
            )

            val sessionPromise: dynamic = createSessionPromise(
                modelBytes,
                weightBytes,
                "moss_tts_global_shared_int8.data",
            )
            sessionPromise.then(
                { session: dynamic ->
                    int8PrefillSession = session
                    val readyAt = window.performance.now()
                    val graphMs = graphReadyAt - startedAt
                    val weightMs = weightReadyAt - graphReadyAt
                    val sessionMs = readyAt - weightReadyAt
                    val totalMs = readyAt - startedAt
                    val inputCount = (session.inputNames.length as Number).toInt()
                    val outputCount = (session.outputNames.length as Number).toInt()

                    document.documentElement?.setAttribute("data-voice-lab-gate2b", "pass")
                    setBadge("session-result", "PASS", "pass")
                    gate2bDetail(
                        "2B PASS · INT8 prefill ONNX session 생성 성공 · inputs=$inputCount · outputs=$outputCount · " +
                            "graph=${graphMs.toFixed(0)}ms · weight=${weightMs.toFixed(0)}ms · " +
                            "session=${sessionMs.toFixed(0)}ms · total=${totalMs.toFixed(0)}ms. " +
                            "다음은 local sampler + codec decoder 연결입니다."
                    )
                    coreButton.disabled = false
                    gate2cDetail("Gate 2B PASS. global session을 유지한 채 local + codec session을 추가할 수 있습니다.")
                    null
                },
                { error: dynamic ->
                    document.documentElement?.setAttribute("data-voice-lab-gate2b", "fail")
                    setBadge("session-result", "실패", "fail")
                    gate2bDetail("2B FAIL · ${describeJsError(error)}")
                    sessionButton.disabled = false
                    null
                },
            )
            null
        }.catch { error ->
            document.documentElement?.setAttribute("data-voice-lab-gate2b", "fail")
            setBadge("session-result", "실패", "fail")
            gate2bDetail("2B FAIL · 다운로드 단계 · ${describeJsError(error)}")
            sessionButton.disabled = false
            null
        }

        null
    }

    coreButton.onclick = {
        coreButton.disabled = true
        document.documentElement?.removeAttribute("data-voice-lab-gate2c")
        setBadge("local-session-result", "다운로드 중", "running")
        setBadge("codec-session-result", "대기", "pending")

        if (int8PrefillSession == null) {
            document.documentElement?.setAttribute("data-voice-lab-gate2c", "fail")
            setBadge("local-session-result", "실패", "fail")
            gate2cDetail("2C FAIL · global prefill session이 메모리에 없습니다.")
            coreButton.disabled = false
        } else {
            val coreStartedAt = window.performance.now()
            var localModelBytes: dynamic = null
            var localLoadStartedAt = coreStartedAt

            fun failCore(stage: String, error: dynamic) {
                document.documentElement?.setAttribute("data-voice-lab-gate2c", "fail")
                gate2cDetail("2C FAIL · $stage · ${describeJsError(error)}")
                coreButton.disabled = false
            }

            fun loadCodec(localReadyAt: Double) {
                setBadge("codec-session-result", "다운로드 중", "running")
                gate2cDetail("local sampler PASS · Audio Tokenizer decoder graph 다운로드 중…")
                var codecModelBytes: dynamic = null
                val codecStartedAt = window.performance.now()

                window.fetch(CODEC_DECODE_GRAPH, emptyRequestInit()).then { response ->
                    if (!response.ok) throw IllegalStateException("codec graph HTTP ${response.status}")
                    response.arrayBuffer()
                }.then { buffer ->
                    codecModelBytes = toUint8Array(buffer)
                    gate2cDetail(
                        "codec graph ${formatBytes((codecModelBytes.byteLength as Number).toDouble())} 완료 · " +
                            "44.2 MB decoder weight 다운로드 중…"
                    )
                    window.fetch(CODEC_DECODE, emptyRequestInit())
                }.then { response ->
                    if (!response.ok) throw IllegalStateException("codec weight HTTP ${response.status}")
                    response.arrayBuffer()
                }.then { buffer ->
                    val codecWeightBytes = toUint8Array(buffer)
                    setBadge("codec-session-result", "세션 생성 중", "running")
                    val codecPromise: dynamic = createSessionPromise(
                        codecModelBytes,
                        codecWeightBytes,
                        "moss_audio_tokenizer_decode_shared.data",
                    )
                    codecPromise.then(
                        { session: dynamic ->
                            codecDecodeSession = session
                            val readyAt = window.performance.now()
                            val localTotalMs = localReadyAt - localLoadStartedAt
                            val codecTotalMs = readyAt - codecStartedAt
                            val allTotalMs = readyAt - coreStartedAt
                            val localInputs = (int8LocalSession.inputNames.length as Number).toInt()
                            val localOutputs = (int8LocalSession.outputNames.length as Number).toInt()
                            val codecInputs = (session.inputNames.length as Number).toInt()
                            val codecOutputs = (session.outputNames.length as Number).toInt()

                            setBadge("codec-session-result", "PASS", "pass")
                            document.documentElement?.setAttribute("data-voice-lab-gate2c", "pass")
                            gate2cDetail(
                                "2C PASS · global + local + codec 핵심 세션 동시 유지 성공 · " +
                                    "local I/O=$localInputs/$localOutputs · codec I/O=$codecInputs/$codecOutputs · " +
                                    "local=${localTotalMs.toFixed(0)}ms · codec=${codecTotalMs.toFixed(0)}ms · " +
                                    "2C total=${allTotalMs.toFixed(0)}ms. 다음 Gate는 tokenizer + decode-step + 실제 한국어 1문장 합성입니다."
                            )
                            null
                        },
                        { error: dynamic ->
                            setBadge("codec-session-result", "실패", "fail")
                            failCore("codec session", error)
                            null
                        },
                    )
                    null
                }.catch { error ->
                    setBadge("codec-session-result", "실패", "fail")
                    failCore("codec download", error)
                    null
                }
            }

            gate2cDetail("INT8 local sampler graph 다운로드 중…")
            window.fetch(INT8_LOCAL_GRAPH, emptyRequestInit()).then { response ->
                if (!response.ok) throw IllegalStateException("local graph HTTP ${response.status}")
                response.arrayBuffer()
            }.then { buffer ->
                localModelBytes = toUint8Array(buffer)
                gate2cDetail(
                    "local graph ${formatBytes((localModelBytes.byteLength as Number).toDouble())} 완료 · " +
                        "85 MB local weight 다운로드 중…"
                )
                window.fetch(INT8_LOCAL, emptyRequestInit())
            }.then { response ->
                if (!response.ok) throw IllegalStateException("local weight HTTP ${response.status}")
                response.arrayBuffer()
            }.then { buffer ->
                val localWeightBytes = toUint8Array(buffer)
                setBadge("local-session-result", "세션 생성 중", "running")
                val localPromise: dynamic = createSessionPromise(
                    localModelBytes,
                    localWeightBytes,
                    "moss_tts_local_fixed_sampled_frame_int8.data",
                )
                localPromise.then(
                    { session: dynamic ->
                        int8LocalSession = session
                        val localReadyAt = window.performance.now()
                        setBadge("local-session-result", "PASS", "pass")
                        loadCodec(localReadyAt)
                        null
                    },
                    { error: dynamic ->
                        setBadge("local-session-result", "실패", "fail")
                        failCore("local session", error)
                        null
                    },
                )
                null
            }.catch { error ->
                setBadge("local-session-result", "실패", "fail")
                failCore("local download", error)
                null
            }
        }

        null
    }
}
