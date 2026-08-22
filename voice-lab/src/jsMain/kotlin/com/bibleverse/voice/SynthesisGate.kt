@file:Suppress("UnsafeCastFromDynamic")
@file:OptIn(ExperimentalStdlibApi::class)

package com.bibleverse.voice

import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.EagerInitialization
import kotlin.random.Random
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.fetch.RequestInit

private const val G3_INT8_BASE =
    "https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/resolve/main"
private const val G3_OFFICIAL_TTS_BASE =
    "https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main"
private const val G3_CODEC_BASE =
    "https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/resolve/main"

private const val G3_PREFILL_GRAPH = "$G3_INT8_BASE/moss_tts_prefill.onnx"
private const val G3_DECODE_GRAPH = "$G3_INT8_BASE/moss_tts_decode_step.onnx"
private const val G3_GLOBAL_WEIGHT = "$G3_INT8_BASE/moss_tts_global_shared_int8.data"
private const val G3_LOCAL_GRAPH = "$G3_INT8_BASE/moss_tts_local_fixed_sampled_frame.onnx"
private const val G3_LOCAL_WEIGHT = "$G3_INT8_BASE/moss_tts_local_fixed_sampled_frame_int8.data"
private const val G3_CODEC_GRAPH = "$G3_CODEC_BASE/moss_audio_tokenizer_decode_full.onnx"
private const val G3_CODEC_WEIGHT = "$G3_CODEC_BASE/moss_audio_tokenizer_decode_shared.data"
private const val G3_MANIFEST = "$G3_OFFICIAL_TTS_BASE/browser_poc_manifest.json"
private const val G3_TTS_META = "$G3_OFFICIAL_TTS_BASE/tts_browser_onnx_meta.json"
private const val G3_CODEC_META = "$G3_CODEC_BASE/codec_browser_onnx_meta.json"

private const val G3_VOICE = "Xiaoyu"
private const val G3_MAX_FRAMES = 96
private const val G3_SEED = 1234

private data class LoadedRuntime(
    val prefill: dynamic,
    val decode: dynamic,
    val local: dynamic,
    val codec: dynamic,
    val manifest: dynamic,
    val ttsMeta: dynamic,
    val codecMeta: dynamic,
)

private data class InputRows(
    val flattened: IntArray,
    val mask: IntArray,
    val sequenceLength: Int,
    val rowWidth: Int,
)

private class DecodeState(
    var globalHidden: dynamic,
    var pastResult: dynamic,
    var pastValidLength: Int,
    val frames: MutableList<IntArray>,
    val seen: Array<MutableSet<Int>>,
    val random: Random,
)

private fun synthesisRequestInit(): RequestInit = js("({ cache: 'default' })")
private fun jsObject(): dynamic = js("({})")
private fun jsArray(): dynamic = js("[]")
private fun toBytes(buffer: dynamic): dynamic = js("new Uint8Array(buffer)")

private fun int32Array(values: IntArray): dynamic {
    val output: dynamic = js("new Int32Array(values.length)")
    values.forEachIndexed { index, value -> output[index] = value }
    return output
}

private fun float32Array(values: FloatArray): dynamic {
    val output: dynamic = js("new Float32Array(values.length)")
    values.forEachIndexed { index, value -> output[index] = value }
    return output
}

private fun jsDims(vararg values: Int): dynamic {
    val output = jsArray()
    values.forEach { output.push(it) }
    return output
}

private fun tensor(type: String, data: dynamic, dims: dynamic): dynamic =
    js("new ort.Tensor(type, data, dims)")

private fun intTensor(values: IntArray, vararg dims: Int): dynamic =
    tensor("int32", int32Array(values), jsDims(*dims))

private fun floatTensor(values: FloatArray, vararg dims: Int): dynamic =
    tensor("float32", float32Array(values), jsDims(*dims))

private fun createG3Session(modelBytes: dynamic, weightBytes: dynamic, weightPath: String): dynamic {
    val external = jsObject()
    external.path = weightPath
    external.data = weightBytes

    val options = jsObject()
    options.executionProviders = arrayOf("wasm")
    options.graphOptimizationLevel = "all"
    options.executionMode = "sequential"
    options.externalData = arrayOf(external)

    return ort.InferenceSession.create(modelBytes, options)
}

private fun fetchBytes(url: String): dynamic =
    window.fetch(url, synthesisRequestInit()).then { response ->
        if (!response.ok) throw IllegalStateException("HTTP ${response.status}: $url")
        response.arrayBuffer()
    }.then { buffer -> toBytes(buffer) }

private fun fetchJson(url: String): dynamic =
    window.fetch(url, synthesisRequestInit()).then { response ->
        if (!response.ok) throw IllegalStateException("HTTP ${response.status}: $url")
        response.text()
    }.then { text -> js("JSON.parse(text)") }

private fun synthesisDetail(message: String) {
    (document.getElementById("gate3b-details") as? HTMLElement)?.textContent = message
}

private fun synthesisBadge(text: String, state: String) {
    val element = document.getElementById("synthesis-result") as? HTMLElement ?: return
    element.textContent = text
    element.className = "badge $state"
}

private fun synthesisError(error: dynamic): String {
    val name = error?.name?.toString() ?: "UnknownError"
    val message = error?.message?.toString() ?: error?.toString() ?: "unknown error"
    return "$name: $message"
}

private fun disposeTensor(value: dynamic) {
    try {
        if (value != null && js("typeof value.dispose === 'function'") as Boolean) {
            value.dispose()
        }
    } catch (_: dynamic) {
        // Best-effort cleanup only.
    }
}

private fun disposeResult(result: dynamic) {
    if (result == null) return
    val keys: dynamic = js("Object.keys(result)")
    val count = (keys.length as Number).toInt()
    for (index in 0 until count) {
        disposeTensor(result[keys[index]])
    }
}

private fun extractLastHidden(tensorValue: dynamic): dynamic {
    val dims = dynamicIntArray(tensorValue.dims)
    val hiddenSize = dims.lastOrNull() ?: error("global_hidden has no dimensions")
    val data: dynamic = tensorValue.data
    val dataLength = (data.length as Number).toInt()
    if (hiddenSize <= 0 || dataLength < hiddenSize) {
        error("invalid global_hidden shape=${dims.joinToString("x")} data=$dataLength")
    }
    val output = FloatArray(hiddenSize)
    val offset = dataLength - hiddenSize
    for (index in 0 until hiddenSize) {
        output[index] = (data[offset + index] as Number).toFloat()
    }
    return floatTensor(output, 1, hiddenSize)
}

private fun findBuiltinVoice(manifest: dynamic, voice: String): dynamic {
    val voices: dynamic = manifest.builtin_voices
    val count = (voices.length as Number).toInt()
    for (index in 0 until count) {
        if (voices[index].voice?.toString() == voice) return voices[index]
    }
    error("builtin voice not found: $voice")
}

private fun dynamicIntList(values: dynamic): List<Int> =
    dynamicIntArray(values).toList()

private fun buildInputRows(tokens: IntArray, manifest: dynamic): InputRows {
    val cfg: dynamic = manifest.tts_config
    val templates: dynamic = manifest.prompt_templates
    val nVq = (cfg.n_vq as Number).toInt()
    val rowWidth = nVq + 1
    val audioPad = (cfg.audio_pad_token_id as Number).toInt()
    val audioStart = (cfg.audio_start_token_id as Number).toInt()
    val audioEnd = (cfg.audio_end_token_id as Number).toInt()
    val userSlot = ((cfg.audio_user_slot_token_id ?: 8) as Number).toInt()

    val selected = findBuiltinVoice(manifest, G3_VOICE)
    val promptCodes: dynamic = selected.prompt_audio_codes
    val promptCount = (promptCodes.length as Number).toInt()
    require(promptCount > 0) { "$G3_VOICE has no prompt_audio_codes" }

    val prefix = dynamicIntList(templates.user_prompt_prefix_token_ids) + audioStart
    val suffix = listOf(audioEnd) +
        dynamicIntList(templates.user_prompt_after_reference_token_ids) +
        tokens.toList() +
        dynamicIntList(templates.assistant_prompt_prefix_token_ids) +
        audioStart

    val rows = mutableListOf<IntArray>()
    prefix.forEach { token ->
        rows += IntArray(rowWidth) { index -> if (index == 0) token else audioPad }
    }

    for (rowIndex in 0 until promptCount) {
        val codes = dynamicIntArray(promptCodes[rowIndex])
        rows += IntArray(rowWidth) { index ->
            when {
                index == 0 -> userSlot
                index - 1 < minOf(codes.size, nVq) -> codes[index - 1]
                else -> audioPad
            }
        }
    }

    suffix.forEach { token ->
        rows += IntArray(rowWidth) { index -> if (index == 0) token else audioPad }
    }

    val flattened = IntArray(rows.size * rowWidth)
    var offset = 0
    rows.forEach { row ->
        row.forEach { value -> flattened[offset++] = value }
    }

    return InputRows(
        flattened = flattened,
        mask = IntArray(rows.size) { 1 },
        sequenceLength = rows.size,
        rowWidth = rowWidth,
    )
}

private fun loadRuntime(onReady: (LoadedRuntime) -> Unit, onFailure: (dynamic) -> Unit) {
    synthesisDetail("manifest / ONNX metadata 확인 중…")
    var manifest: dynamic = null
    var ttsMeta: dynamic = null
    var codecMeta: dynamic = null
    var globalWeight: dynamic = null
    var prefillSession: dynamic = null
    var decodeSession: dynamic = null
    var localSession: dynamic = null

    fetchJson(G3_MANIFEST).then { value ->
        manifest = value
        fetchJson(G3_TTS_META)
    }.then { value ->
        ttsMeta = value
        fetchJson(G3_CODEC_META)
    }.then { value ->
        codecMeta = value
        synthesisDetail("INT8 global shared weight 111 MB 다운로드 중…")
        fetchBytes(G3_GLOBAL_WEIGHT)
    }.then { value ->
        globalWeight = value
        synthesisDetail("global weight 완료 · prefill graph / session 생성 중…")
        fetchBytes(G3_PREFILL_GRAPH)
    }.then { graph ->
        createG3Session(graph, globalWeight, "moss_tts_global_shared_int8.data")
    }.then { session ->
        prefillSession = session
        synthesisDetail("prefill 준비 완료 · global decode-step session 생성 중…")
        fetchBytes(G3_DECODE_GRAPH)
    }.then { graph ->
        createG3Session(graph, globalWeight, "moss_tts_global_shared_int8.data")
    }.then { session ->
        decodeSession = session
        globalWeight = null
        synthesisDetail("global 2개 session 완료 · local 85 MB 다운로드 중…")
        fetchBytes(G3_LOCAL_WEIGHT)
    }.then { localWeight ->
        fetchBytes(G3_LOCAL_GRAPH).then { graph ->
            createG3Session(graph, localWeight, "moss_tts_local_fixed_sampled_frame_int8.data")
        }
    }.then { session ->
        localSession = session
        synthesisDetail("local session 완료 · codec decoder 44 MB 다운로드 중…")
        fetchBytes(G3_CODEC_WEIGHT)
    }.then { codecWeight ->
        fetchBytes(G3_CODEC_GRAPH).then { graph ->
            createG3Session(graph, codecWeight, "moss_audio_tokenizer_decode_shared.data")
        }
    }.then { codecSession ->
        onReady(
            LoadedRuntime(
                prefill = prefillSession,
                decode = decodeSession,
                local = localSession,
                codec = codecSession,
                manifest = manifest,
                ttsMeta = ttsMeta,
                codecMeta = codecMeta,
            )
        )
        null
    }.catch { error ->
        onFailure(error)
        null
    }
}

private fun runLocalFrame(
    runtime: LoadedRuntime,
    state: DecodeState,
    nVq: Int,
    codebookSize: Int,
    onReady: (Boolean, IntArray) -> Unit,
    onFailure: (dynamic) -> Unit,
) {
    val seenMask = IntArray(nVq * codebookSize)
    state.seen.forEachIndexed { channel, values ->
        val base = channel * codebookSize
        values.forEach { token ->
            if (token in 0 until codebookSize) seenMask[base + token] = 1
        }
    }

    val assistantRandom = floatArrayOf(state.random.nextDouble(1e-6, 1.0 - 1e-6).toFloat())
    val audioRandom = FloatArray(nVq) { state.random.nextDouble(1e-6, 1.0 - 1e-6).toFloat() }
    val feeds = jsObject()
    feeds.global_hidden = state.globalHidden
    feeds.repetition_seen_mask = intTensor(seenMask, 1, nVq, codebookSize)
    feeds.assistant_random_u = floatTensor(assistantRandom, 1)
    feeds.audio_random_u = floatTensor(audioRandom, 1, nVq)

    runtime.local.run(feeds).then { outputs ->
        val continueData: dynamic = outputs.should_continue.data
        val shouldContinue = (continueData[0] as Number).toInt() > 0
        val frame = dynamicIntArray(outputs.frame_token_ids.data)
        disposeTensor(feeds.repetition_seen_mask)
        disposeTensor(feeds.assistant_random_u)
        disposeTensor(feeds.audio_random_u)
        disposeResult(outputs)
        onReady(shouldContinue, frame)
        null
    }.catch { error ->
        disposeTensor(feeds.repetition_seen_mask)
        disposeTensor(feeds.assistant_random_u)
        disposeTensor(feeds.audio_random_u)
        onFailure(error)
        null
    }
}

private fun decodeLoop(
    runtime: LoadedRuntime,
    state: DecodeState,
    manifest: dynamic,
    ttsMeta: dynamic,
    step: Int,
    onDone: (List<IntArray>) -> Unit,
    onFailure: (dynamic) -> Unit,
) {
    if (step >= G3_MAX_FRAMES) {
        disposeTensor(state.globalHidden)
        disposeResult(state.pastResult)
        onDone(state.frames)
        return
    }

    val cfg: dynamic = manifest.tts_config
    val nVq = (cfg.n_vq as Number).toInt()
    val codebookSizes = dynamicIntArray(cfg.audio_codebook_sizes)
    val codebookSize = codebookSizes.firstOrNull() ?: 1024
    val rowWidth = nVq + 1
    val audioPad = (cfg.audio_pad_token_id as Number).toInt()
    val assistantSlot = (cfg.audio_assistant_slot_token_id as Number).toInt()

    runLocalFrame(runtime, state, nVq, codebookSize, local@{ shouldContinue, frame ->
        if (!shouldContinue) {
            disposeTensor(state.globalHidden)
            disposeResult(state.pastResult)
            onDone(state.frames)
            return@local
        }
        if (frame.size < nVq) {
            onFailure(IllegalStateException("local frame too short: ${frame.size}/$nVq"))
            return@local
        }

        val audioRow = IntArray(rowWidth) { index -> if (index == 0) assistantSlot else audioPad }
        for (channel in 0 until nVq) {
            val token = frame[channel]
            audioRow[channel + 1] = token
            state.seen[channel].add(token)
        }
        state.frames += frame.copyOf(nVq)

        val inputTensor = intTensor(audioRow, 1, 1, rowWidth)
        val pastLengthTensor = intTensor(intArrayOf(state.pastValidLength), 1)
        val feeds = jsObject()
        feeds.input_ids = inputTensor
        feeds.past_valid_lengths = pastLengthTensor

        val inputNames = dynamicStringArray(ttsMeta.onnx.decode_input_names)
        val outputNames = dynamicStringArray(ttsMeta.onnx.decode_output_names)
        val pastInputs = inputNames.drop(2)
        val presentOutputs = outputNames.drop(1)
        require(pastInputs.size == presentOutputs.size) {
            "KV name mismatch: ${pastInputs.size}/${presentOutputs.size}"
        }
        for (index in pastInputs.indices) {
            feeds[pastInputs[index]] = state.pastResult[presentOutputs[index]]
        }

        runtime.decode.run(feeds).then { outputs ->
            val nextHidden = extractLastHidden(outputs.global_hidden)
            disposeTensor(inputTensor)
            disposeTensor(pastLengthTensor)
            disposeTensor(state.globalHidden)
            disposeResult(state.pastResult)
            state.globalHidden = nextHidden
            state.pastResult = outputs
            state.pastValidLength += 1

            if ((step + 1) % 8 == 0) {
                synthesisDetail("한국어 음성 생성 중… ${step + 1} frames")
            }
            window.setTimeout({
                decodeLoop(runtime, state, manifest, ttsMeta, step + 1, onDone, onFailure)
            }, 0)
            null
        }.catch { error ->
            disposeTensor(inputTensor)
            disposeTensor(pastLengthTensor)
            onFailure(error)
            null
        }
    }, onFailure)
}

private fun decodePcm(
    runtime: LoadedRuntime,
    frames: List<IntArray>,
    onReady: (FloatArray, Int) -> Unit,
    onFailure: (dynamic) -> Unit,
) {
    if (frames.isEmpty()) {
        onFailure(IllegalStateException("no audio frames generated"))
        return
    }

    val nVq = frames.first().size
    val flat = IntArray(frames.size * nVq)
    var offset = 0
    frames.forEach { frame -> frame.forEach { flat[offset++] = it } }

    val codes = intTensor(flat, 1, frames.size, nVq)
    val lengths = intTensor(intArrayOf(frames.size), 1)
    val feeds = jsObject()
    feeds.audio_codes = codes
    feeds.audio_code_lengths = lengths

    runtime.codec.run(feeds).then { outputs ->
        val audioTensor: dynamic = outputs.audio
        val dims = dynamicIntArray(audioTensor.dims)
        val audioData: dynamic = audioTensor.data
        val reported = (outputs.audio_lengths.data[0] as Number).toInt()
        val channels = if (dims.size >= 3) dims[dims.size - 2] else 1
        val samplesPerChannel = dims.lastOrNull() ?: 0
        val length = minOf(reported, samplesPerChannel)
        if (length <= 0) throw IllegalStateException("codec returned empty audio: dims=${dims.joinToString("x")}")

        val mono = FloatArray(length)
        for (sample in 0 until length) {
            var sum = 0.0
            for (channel in 0 until channels) {
                val index = channel * samplesPerChannel + sample
                sum += (audioData[index] as Number).toDouble()
            }
            mono[sample] = (sum / channels.coerceAtLeast(1)).toFloat()
        }

        val sampleRate = (runtime.codecMeta.codec_config.sample_rate as Number).toInt()
        disposeTensor(codes)
        disposeTensor(lengths)
        disposeResult(outputs)
        onReady(mono, sampleRate)
        null
    }.catch { error ->
        disposeTensor(codes)
        disposeTensor(lengths)
        onFailure(error)
        null
    }
}

private fun createWavBytes(samples: FloatArray, sampleRate: Int): dynamic {
    val byteLength = 44 + samples.size * 2
    val buffer: dynamic = js("new ArrayBuffer(byteLength)")
    val view: dynamic = js("new DataView(buffer)")

    fun writeAscii(offset: Int, text: String) {
        text.forEachIndexed { index, character -> view.setUint8(offset + index, character.code) }
    }

    writeAscii(0, "RIFF")
    view.setUint32(4, byteLength - 8, true)
    writeAscii(8, "WAVE")
    writeAscii(12, "fmt ")
    view.setUint32(16, 16, true)
    view.setUint16(20, 1, true)
    view.setUint16(22, 1, true)
    view.setUint32(24, sampleRate, true)
    view.setUint32(28, sampleRate * 2, true)
    view.setUint16(32, 2, true)
    view.setUint16(34, 16, true)
    writeAscii(36, "data")
    view.setUint32(40, samples.size * 2, true)

    samples.forEachIndexed { index, sample ->
        val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
        view.setInt16(44 + index * 2, pcm, true)
    }
    return toBytes(buffer)
}

private fun publishAudio(samples: FloatArray, sampleRate: Int): Int {
    val wavBytes = createWavBytes(samples, sampleRate)
    val blob: dynamic = js("new Blob([wavBytes], { type: 'audio/wav' })")
    val url: String = js("URL.createObjectURL(blob)") as String
    val audio: dynamic = document.getElementById("synthesis-audio")
    audio.src = url
    audio.controls = true
    document.documentElement?.setAttribute("data-voice-lab-audio-url", url)
    document.documentElement?.setAttribute("data-voice-lab-audio-bytes", (wavBytes.length as Number).toString())
    return (wavBytes.length as Number).toInt()
}

private fun runSynthesis(runtime: LoadedRuntime, tokens: IntArray, startedAt: Double) {
    val rows = buildInputRows(tokens, runtime.manifest)
    val inputIds = intTensor(rows.flattened, 1, rows.sequenceLength, rows.rowWidth)
    val mask = intTensor(rows.mask, 1, rows.sequenceLength)
    val feeds = jsObject()
    feeds.input_ids = inputIds
    feeds.attention_mask = mask

    synthesisDetail("$G3_VOICE · prefill 실행 중… input rows=${rows.sequenceLength}")
    runtime.prefill.run(feeds).then { outputs ->
        val globalHidden = extractLastHidden(outputs.global_hidden)
        disposeTensor(inputIds)
        disposeTensor(mask)

        val nVq = (runtime.manifest.tts_config.n_vq as Number).toInt()
        val state = DecodeState(
            globalHidden = globalHidden,
            pastResult = outputs,
            pastValidLength = rows.sequenceLength,
            frames = mutableListOf(),
            seen = Array(nVq) { mutableSetOf() },
            random = Random(G3_SEED),
        )

        synthesisDetail("prefill 완료 · 한국어 audio tokens 생성 시작…")
        decodeLoop(runtime, state, runtime.manifest, runtime.ttsMeta, 0, { frames ->
            synthesisDetail("${frames.size} frames 생성 · 48kHz waveform 복원 중…")
            decodePcm(runtime, frames, { pcm, sampleRate ->
                val wavBytes = publishAudio(pcm, sampleRate)
                val finishedAt = window.performance.now()
                val elapsed = finishedAt - startedAt
                val durationSeconds = pcm.size.toDouble() / sampleRate.toDouble()

                document.documentElement?.setAttribute("data-voice-lab-gate3b", "pass")
                document.documentElement?.setAttribute("data-voice-lab-generated-frames", frames.size.toString())
                synthesisBadge("WAV 생성 PASS", "pass")
                synthesisDetail(
                    "3B TECH PASS · voice=$G3_VOICE · ${frames.size} frames · ${durationSeconds.asDynamic().toFixed(2)}s audio · " +
                        "$sampleRate Hz · ${wavBytes / 1024} KB · total=${elapsed.asDynamic().toFixed(0)}ms. " +
                        "이제 실제 청음으로 한국어 발음/자연스러움을 판정해야 합니다."
                )
            }, { error -> failSynthesis(error) })
        }, { error -> failSynthesis(error) })
        null
    }.catch { error ->
        disposeTensor(inputIds)
        disposeTensor(mask)
        failSynthesis(error)
        null
    }
}

private fun failSynthesis(error: dynamic) {
    document.documentElement?.setAttribute("data-voice-lab-gate3b", "fail")
    synthesisBadge("실패", "fail")
    synthesisDetail("3B FAIL · ${synthesisError(error)}")
}

@EagerInitialization
private val synthesisGateInstaller = run {
    val button = document.getElementById("run-synthesis") as? HTMLButtonElement
    if (button != null) {
        button.onclick = click@{
            val tokens = koreanGateTokenIds
            if (tokens == null || tokens.isEmpty()) {
                failSynthesis(IllegalStateException("먼저 Gate 3A 한국어 토큰화를 통과해야 합니다."))
                return@click null
            }

            button.disabled = true
            synthesisBadge("모델 준비 중", "running")
            document.documentElement?.removeAttribute("data-voice-lab-gate3b")
            val startedAt = window.performance.now()

            try {
                ort.env.wasm.numThreads = 1
                ort.env.wasm.proxy = false
            } catch (error: dynamic) {
                failSynthesis(error)
                button.disabled = false
                return@click null
            }

            loadRuntime({ runtime ->
                synthesisDetail("4개 ONNX session 준비 완료 · 실제 한국어 합성 시작…")
                runSynthesis(runtime, tokens, startedAt)
            }, { error ->
                failSynthesis(error)
                button.disabled = false
            })
            null
        }
    }
    true
}
