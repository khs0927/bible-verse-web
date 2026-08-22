@file:JsModule("@sctg/sentencepiece-js")
@file:JsNonModule

package com.bibleverse.voice

import kotlin.js.Promise

external class SentencePieceProcessor {
    fun loadFromB64StringModel(model: String): Promise<dynamic>
    fun encodeIds(text: String): dynamic
    fun encodePieces(text: String): dynamic
}
