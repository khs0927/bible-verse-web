package com.bibleverse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoiceStudioConfigTest {
    @Test
    fun stripsOpenAiV1Suffix() {
        assertEquals(
            "http://127.0.0.1:3900",
            VoiceStudioConfig.normalizeServiceRoot(
                raw = "http://127.0.0.1:3900/v1/",
                allowInsecureHttp = false,
            ),
        )
    }

    @Test
    fun rejectsRemotePlainHttpByDefault() {
        assertFailsWith<IllegalArgumentException> {
            VoiceStudioConfig.normalizeServiceRoot(
                raw = "http://voice.example.com:3900",
                allowInsecureHttp = false,
            )
        }
    }

    @Test
    fun allowsExplicitPrivateHttpOverride() {
        assertEquals(
            "http://voicestudio:3900",
            VoiceStudioConfig.normalizeServiceRoot(
                raw = "http://voicestudio:3900",
                allowInsecureHttp = true,
            ),
        )
    }

    @Test
    fun readsSafeDefaults() {
        val config = VoiceStudioConfig.fromEnvironment(emptyMap())

        assertEquals("http://127.0.0.1:3900", config.serviceRoot)
        assertEquals("tts-1", config.defaultModel)
        assertEquals("default", config.defaultVoice)
        assertEquals(5_000, config.maxInputChars)
        assertTrue(config.apiKey == null)
    }

    @Test
    fun mapsAudioContentTypes() {
        assertEquals("audio/mpeg", VoiceStudioClient.contentTypeFor("mp3"))
        assertEquals("audio/wav", VoiceStudioClient.contentTypeFor("wav"))
        assertEquals("audio/ogg", VoiceStudioClient.contentTypeFor("opus"))
    }
}
