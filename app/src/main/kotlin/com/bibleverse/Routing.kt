package com.bibleverse

import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Application.configureRouting() {
    val voiceStudio = VoiceStudioClient(VoiceStudioConfig.fromEnvironment())

    routing {
        get("/") {
            call.respondText(
                landingPage(voiceStudio.config),
                ContentType.Text.Html.withCharset(Charsets.UTF_8),
            )
        }

        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "ok",
                    "service" to "bible-verse-web",
                ),
            )
        }

        get("/api/voice/status") {
            val probe = withContext(Dispatchers.IO) { voiceStudio.probe() }
            val payload = VoiceStatusResponse(
                available = probe.available,
                model = voiceStudio.config.defaultModel,
                voice = voiceStudio.config.defaultVoice,
                apiKeyConfigured = !voiceStudio.config.apiKey.isNullOrBlank(),
                detail = probe.detail,
            )
            call.respond(
                if (probe.available) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                payload,
            )
        }

        get("/api/voice/voices") {
            try {
                call.respondText(
                    withContext(Dispatchers.IO) { voiceStudio.voicesJson() },
                    ContentType.Application.Json.withCharset(Charsets.UTF_8),
                )
            } catch (e: VoiceStudioException) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(e.message ?: "VoiceStudio voice list failed"),
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(e.message ?: "VoiceStudio is unavailable"),
                )
            }
        }

        post("/api/voice/speech") {
            try {
                val request = call.receive<SpeechRequest>()
                val audio = withContext(Dispatchers.IO) { voiceStudio.synthesize(request) }

                call.response.header("X-VoiceStudio-Cache", if (audio.cacheHit) "HIT" else "MISS")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "inline; filename=\"bible-friend.${audio.format}\"",
                )

                call.respondBytes(
                    bytes = audio.bytes,
                    contentType = ContentType.parse(audio.contentType),
                    status = HttpStatusCode.OK,
                )
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(e.message ?: "Invalid speech request"),
                )
            } catch (e: VoiceStudioException) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(e.message ?: "VoiceStudio TTS failed"),
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(e.message ?: "VoiceStudio is unavailable"),
                )
            }
        }
    }
}

private fun landingPage(config: VoiceStudioConfig): String {
    val defaultModel = escapeHtml(config.defaultModel)
    val defaultVoice = escapeHtml(config.defaultVoice)
    val maxChars = config.maxInputChars

    return """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Bible Friend Voice</title>
  <style>
    :root { color-scheme: light; font-family: Inter, Pretendard, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    * { box-sizing: border-box; }
    body { margin: 0; min-height: 100vh; background: #f6f2ea; color: #20201f; }
    main { width: min(760px, calc(100% - 32px)); margin: 0 auto; padding: 48px 0 72px; }
    .eyebrow { font-size: 13px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; color: #7a6540; }
    h1 { margin: 10px 0 8px; font-size: clamp(32px, 8vw, 58px); line-height: 1; letter-spacing: -.04em; }
    .lede { margin: 0 0 28px; color: #615f59; line-height: 1.7; }
    .card { background: rgba(255,255,255,.82); border: 1px solid #ded7ca; border-radius: 24px; padding: 22px; box-shadow: 0 18px 60px rgba(61,49,28,.08); }
    label { display: block; margin: 14px 0 7px; font-weight: 750; font-size: 14px; }
    textarea, input, select { width: 100%; border: 1px solid #cbc3b5; background: white; border-radius: 14px; padding: 13px 14px; font: inherit; color: inherit; }
    textarea { min-height: 180px; resize: vertical; line-height: 1.65; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    button { width: 100%; margin-top: 18px; border: 0; border-radius: 15px; padding: 15px 18px; font: inherit; font-weight: 800; background: #24231f; color: white; cursor: pointer; }
    button:disabled { opacity: .55; cursor: wait; }
    .status { margin: 14px 0 0; font-size: 13px; color: #6f6b63; }
    .status strong { color: #24231f; }
    audio { width: 100%; margin-top: 18px; }
    .note { margin-top: 18px; font-size: 13px; line-height: 1.65; color: #716e67; }
    code { background: #eee8dd; padding: 2px 6px; border-radius: 6px; }
    @media (max-width: 620px) { main { padding-top: 28px; } .grid { grid-template-columns: 1fr; } .card { padding: 18px; border-radius: 20px; } }
  </style>
</head>
<body>
  <main>
    <div class="eyebrow">Bible Friend · Local Voice</div>
    <h1>성경 친구 음성</h1>
    <p class="lede">브라우저에는 VoiceStudio API 키를 노출하지 않고, Ktor 서버가 로컬/VPS VoiceStudio를 대신 호출합니다.</p>

    <section class="card">
      <label for="text">읽어줄 문장</label>
      <textarea id="text" maxlength="$maxChars">안녕! 오늘도 성경 친구와 함께 말씀을 천천히 읽어볼까?</textarea>

      <div class="grid">
        <div>
          <label for="voice">Voice ID</label>
          <input id="voice" value="$defaultVoice" autocomplete="off">
        </div>
        <div>
          <label for="model">Model</label>
          <input id="model" value="$defaultModel" autocomplete="off">
        </div>
      </div>

      <div class="grid">
        <div>
          <label for="format">오디오 형식</label>
          <select id="format">
            <option value="mp3">MP3</option>
            <option value="wav">WAV</option>
            <option value="opus">Opus</option>
            <option value="flac">FLAC</option>
          </select>
        </div>
        <div>
          <label for="speed">속도</label>
          <input id="speed" type="number" min="0.25" max="4" step="0.05" value="1">
        </div>
      </div>

      <label for="instructions">스타일 지시 (선택)</label>
      <input id="instructions" placeholder="예: female, whisper">

      <button id="speak">읽어주기</button>
      <p id="status" class="status">VoiceStudio 연결 확인 중…</p>
      <audio id="player" controls preload="none"></audio>

      <p class="note">
        기본 연결은 <code>127.0.0.1:3900</code>입니다. 원격 VoiceStudio는 HTTPS를 권장하며,
        서버 환경변수로 주소·API 키·기본 보이스를 설정할 수 있습니다.
      </p>
    </section>
  </main>

  <script>
    const statusEl = document.getElementById("status");
    const button = document.getElementById("speak");
    const player = document.getElementById("player");
    let activeUrl = null;

    async function refreshStatus() {
      try {
        const response = await fetch("/api/voice/status", { cache: "no-store" });
        const data = await response.json();
        statusEl.textContent = data.available
          ? "VoiceStudio 연결됨 · " + data.model + " · " + data.voice
          : "VoiceStudio 연결 대기 · " + (data.detail || "unavailable");
      } catch (error) {
        statusEl.textContent = "상태 확인 실패 · " + error.message;
      }
    }

    button.addEventListener("click", async () => {
      button.disabled = true;
      statusEl.textContent = "음성을 생성하고 있습니다…";

      const speedValue = Number(document.getElementById("speed").value);
      const payload = {
        text: document.getElementById("text").value,
        voice: document.getElementById("voice").value || null,
        model: document.getElementById("model").value || null,
        responseFormat: document.getElementById("format").value,
        speed: Number.isFinite(speedValue) ? speedValue : null,
        instructions: document.getElementById("instructions").value || null
      };

      try {
        const response = await fetch("/api/voice/speech", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });

        if (!response.ok) {
          let message = "TTS 요청 실패 (HTTP " + response.status + ")";
          try {
            const data = await response.json();
            if (data.error) message = data.error;
          } catch (_) {}
          throw new Error(message);
        }

        const blob = await response.blob();
        if (activeUrl) URL.revokeObjectURL(activeUrl);
        activeUrl = URL.createObjectURL(blob);
        player.src = activeUrl;
        await player.play();

        const cache = response.headers.get("X-VoiceStudio-Cache") || "MISS";
        statusEl.textContent = "재생 중 · server cache " + cache;
      } catch (error) {
        statusEl.textContent = error.message;
      } finally {
        button.disabled = false;
      }
    });

    refreshStatus();
  </script>
</body>
</html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String {
    return buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
