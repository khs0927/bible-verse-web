package com.bibleverse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.LinkedHashMap

@Serializable
data class SpeechRequest(
    val text: String,
    val voice: String? = null,
    val model: String? = null,
    val responseFormat: String? = null,
    val speed: Double? = null,
    val instructions: String? = null,
)

@Serializable
data class VoiceStatusResponse(
    val provider: String = "VoiceStudio",
    val available: Boolean,
    val model: String,
    val voice: String,
    val apiKeyConfigured: Boolean,
    val detail: String? = null,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

data class VoiceStudioConfig(
    val serviceRoot: String,
    val apiKey: String?,
    val defaultModel: String,
    val defaultVoice: String,
    val requestTimeout: Duration,
    val maxInputChars: Int,
    val cacheMaxEntries: Int,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): VoiceStudioConfig {
            val allowInsecureHttp =
                env["VOICESTUDIO_ALLOW_INSECURE_HTTP"]?.equals("true", ignoreCase = true) == true

            val serviceRoot = normalizeServiceRoot(
                raw = env["VOICESTUDIO_BASE_URL"] ?: "http://127.0.0.1:3900",
                allowInsecureHttp = allowInsecureHttp,
            )

            val timeoutSeconds = env["VOICESTUDIO_TIMEOUT_SECONDS"]?.toLongOrNull()?.coerceIn(5, 600) ?: 180L
            val maxInputChars = env["VOICESTUDIO_MAX_INPUT_CHARS"]?.toIntOrNull()?.coerceIn(100, 20_000) ?: 5_000
            val cacheMaxEntries = env["VOICESTUDIO_CACHE_MAX_ENTRIES"]?.toIntOrNull()?.coerceIn(0, 512) ?: 64

            return VoiceStudioConfig(
                serviceRoot = serviceRoot,
                apiKey = env["VOICESTUDIO_API_KEY"]?.takeIf { it.isNotBlank() }
                    ?: env["OMNIVOICE_API_KEY"]?.takeIf { it.isNotBlank() },
                defaultModel = env["VOICESTUDIO_MODEL"]?.takeIf { it.isNotBlank() } ?: "tts-1",
                defaultVoice = env["VOICESTUDIO_VOICE"]?.takeIf { it.isNotBlank() } ?: "default",
                requestTimeout = Duration.ofSeconds(timeoutSeconds),
                maxInputChars = maxInputChars,
                cacheMaxEntries = cacheMaxEntries,
            )
        }

        internal fun normalizeServiceRoot(raw: String, allowInsecureHttp: Boolean): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "VOICESTUDIO_BASE_URL must not be empty" }

            val withoutV1 = if (trimmed.endsWith("/v1")) trimmed.removeSuffix("/v1") else trimmed
            val uri = URI.create(withoutV1)
            require(uri.scheme == "http" || uri.scheme == "https") {
                "VOICESTUDIO_BASE_URL must use http or https"
            }

            val host = uri.host ?: throw IllegalArgumentException("VOICESTUDIO_BASE_URL must contain a host")
            val loopback = host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"

            require(uri.scheme == "https" || loopback || allowInsecureHttp) {
                "Remote VoiceStudio must use HTTPS. Set VOICESTUDIO_ALLOW_INSECURE_HTTP=true only for a trusted private network."
            }

            return withoutV1
        }
    }
}

data class AudioResult(
    val bytes: ByteArray,
    val contentType: String,
    val format: String,
    val cacheHit: Boolean,
)

data class ProbeResult(
    val available: Boolean,
    val detail: String,
)

class VoiceStudioException(
    message: String,
    val upstreamStatus: Int? = null,
) : RuntimeException(message)

private data class SpeechCommand(
    val text: String,
    val voice: String,
    val model: String,
    val responseFormat: String,
    val speed: Double?,
    val instructions: String?,
)

@Serializable
private data class VoiceStudioSpeechPayload(
    val model: String,
    val voice: String,
    val input: String,
    @SerialName("response_format")
    val responseFormat: String,
    val speed: Double? = null,
    val instructions: String? = null,
)

private data class CacheEntry(
    val bytes: ByteArray,
    val contentType: String,
    val format: String,
)

private class AudioCache(
    private val maxEntries: Int,
) {
    private val entries = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return maxEntries > 0 && size > maxEntries
        }
    }

    @Synchronized
    fun get(key: String): CacheEntry? = if (maxEntries == 0) null else entries[key]

    @Synchronized
    fun put(key: String, value: CacheEntry) {
        if (maxEntries == 0) return
        entries[key] = value
    }
}

class VoiceStudioClient(
    val config: VoiceStudioConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val cache = AudioCache(config.cacheMaxEntries)

    fun probe(): ProbeResult {
        return try {
            val request = requestBuilder("${config.serviceRoot}/health")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val body = response.body().take(500)
            ProbeResult(
                available = response.statusCode() in 200..299,
                detail = "HTTP ${response.statusCode()}${if (body.isBlank()) "" else " · $body"}",
            )
        } catch (e: Exception) {
            ProbeResult(
                available = false,
                detail = e.message ?: e::class.simpleName.orEmpty(),
            )
        }
    }

    fun voicesJson(): String {
        val request = requestBuilder("${config.serviceRoot}/v1/audio/voices")
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw VoiceStudioException(
                message = "VoiceStudio voice list failed: ${response.body().take(1_000)}",
                upstreamStatus = response.statusCode(),
            )
        }
        return response.body()
    }

    fun synthesize(request: SpeechRequest): AudioResult {
        val command = normalize(request)
        val cacheKey = cacheKey(command)
        cache.get(cacheKey)?.let {
            return AudioResult(
                bytes = it.bytes,
                contentType = it.contentType,
                format = it.format,
                cacheHit = true,
            )
        }

        val payload = VoiceStudioSpeechPayload(
            model = command.model,
            voice = command.voice,
            input = command.text,
            responseFormat = command.responseFormat,
            speed = command.speed,
            instructions = command.instructions,
        )

        val httpRequest = requestBuilder("${config.serviceRoot}/v1/audio/speech")
            .header("Accept", "audio/*")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload), StandardCharsets.UTF_8))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().toString(StandardCharsets.UTF_8).take(2_000)
            throw VoiceStudioException(
                message = "VoiceStudio TTS failed: $errorBody",
                upstreamStatus = response.statusCode(),
            )
        }

        if (response.body().isEmpty()) {
            throw VoiceStudioException("VoiceStudio returned an empty audio body", response.statusCode())
        }

        val contentType = contentTypeFor(command.responseFormat)
        val entry = CacheEntry(
            bytes = response.body(),
            contentType = contentType,
            format = command.responseFormat,
        )
        cache.put(cacheKey, entry)

        return AudioResult(
            bytes = entry.bytes,
            contentType = entry.contentType,
            format = entry.format,
            cacheHit = false,
        )
    }

    private fun normalize(request: SpeechRequest): SpeechCommand {
        val text = request.text.trim()
        require(text.isNotEmpty()) { "text must not be empty" }
        require(text.length <= config.maxInputChars) {
            "text is too long: ${text.length} chars (max ${config.maxInputChars})"
        }

        val model = request.model?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultModel
        val voice = request.voice?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultVoice
        require(model.length <= 128) { "model is too long" }
        require(voice.length <= 128) { "voice is too long" }

        val responseFormat = (request.responseFormat ?: "mp3").lowercase()
        require(responseFormat in SUPPORTED_FORMATS) {
            "responseFormat must be one of ${SUPPORTED_FORMATS.joinToString()}"
        }

        request.speed?.let {
            require(it in 0.25..4.0) { "speed must be between 0.25 and 4.0" }
        }

        val instructions = request.instructions?.trim()?.takeIf { it.isNotEmpty() }
        require(instructions == null || instructions.length <= 1_000) {
            "instructions must be 1000 characters or fewer"
        }

        return SpeechCommand(
            text = text,
            voice = voice,
            model = model,
            responseFormat = responseFormat,
            speed = request.speed,
            instructions = instructions,
        )
    }

    private fun requestBuilder(url: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(config.requestTimeout)

        config.apiKey?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder
    }

    private fun cacheKey(command: SpeechCommand): String {
        val raw = listOf(
            command.model,
            command.voice,
            command.responseFormat,
            command.speed?.toString().orEmpty(),
            command.instructions.orEmpty(),
            command.text,
        ).joinToString("\u0000")

        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val SUPPORTED_FORMATS = linkedSetOf("mp3", "wav", "opus", "aac", "flac", "pcm")

        fun contentTypeFor(format: String): String = when (format.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "pcm" -> "audio/pcm"
            else -> "application/octet-stream"
        }
    }
}
