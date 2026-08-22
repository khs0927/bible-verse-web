@file:Suppress("UnsafeCastFromDynamic")
@file:JsModule("@sctg/sentencepiece-js")
@file:JsNonModule

package com.bibleverse.voice

import kotlin.js.Promise

external class SentencePieceProcessor {
    fun loadFromB64StringModel(model: String): Promise<dynamic>
    fun encodeIds(text: String): dynamic
    fun encodePieces(text: String): dynamic
}

internal fun arrayBufferToBase64(buffer: dynamic): String {
    val bytes: dynamic = js("new Uint8Array(buffer)")
    val chunkSize = 0x8000
    val parts: dynamic = js("[]")
    var offset = 0
    val length = (bytes.length as Number).toInt()

    while (offset < length) {
        val end = minOf(offset + chunkSize, length)
        val slice: dynamic = bytes.subarray(offset, end)
        parts.push(js("String.fromCharCode.apply(null, slice)"))
        offset = end
    }

    val binary: String = parts.join("") as String
    return js("btoa(binary)") as String
}

internal fun dynamicIntArray(values: dynamic): IntArray {
    val length = (values.length as Number).toInt()
    return IntArray(length) { index -> (values[index] as Number).toInt() }
}

internal fun dynamicStringArray(values: dynamic): List<String> {
    val length = (values.length as Number).toInt()
    return List(length) { index -> values[index].toString() }
}
