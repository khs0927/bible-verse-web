# 📖 성경말씀 웹사이트

Kotlin + Ktor로 만든 성경 말씀 웹 애플리케이션입니다.  
백엔드와 프론트엔드 모두 **Kotlin**으로 작성되었습니다. (Ktor HTML DSL)

## 기능

- **홈**: 오늘의 랜덤 말씀 + 인기 말씀
- **전체 말씀**: 30개의 인기 성경 구절
- **랜덤 말씀**: 버튼을 눌러 새로운 말씀 받기
- **주제별**: 사랑, 평안, 용기, 소망 등 주제별 분류
- **검색**: 키워드, 책 이름, 주제로 검색

## 기술 스택

- **언어**: Kotlin 2.1
- **프레임워크**: Ktor 3.0 (Netty)
- **프론트엔드**: Ktor HTML Builder + kotlinx.html (순수 Kotlin SSR)

## 로컬 실행

> 참고: Gradle Wrapper(`gradlew`) 스크립트가 저장소에 없으므로, 아래 둘 중 하나로 실행한다.

```bash
# 방법 A: 시스템 Gradle 8.12+ (JDK 21 필요)
gradle :app:run
# http://localhost:8080

# 방법 B: Docker (권장, 버전 걱정 없음)
docker build -t bible-verse-web .
docker run -p 8080:8080 bible-verse-web
```

## Docker

```bash
docker build -t bible-verse-web .
docker run -p 8080:8080 bible-verse-web
```

## 배포 (Railway / Render)

- Railway: GitHub 연결 후 Dockerfile 자동 감지
- Render: Web Service + Docker runtime

PORT 환경변수를 자동으로 지원합니다.


## Bible Friend VoiceStudio TTS

이 브랜치는 VoiceStudio를 로컬/VPS TTS 공급자로 연결합니다. 브라우저는 VoiceStudio에 직접 접근하지 않고 Ktor 서버의 API만 호출하므로 API 키가 프론트엔드에 노출되지 않습니다.

### 서버 환경변수

    VOICESTUDIO_BASE_URL=http://127.0.0.1:3900
    VOICESTUDIO_API_KEY=replace-with-a-long-random-key
    VOICESTUDIO_MODEL=tts-1
    VOICESTUDIO_VOICE=default

선택 설정:

    VOICESTUDIO_TIMEOUT_SECONDS=180
    VOICESTUDIO_MAX_INPUT_CHARS=5000
    VOICESTUDIO_CACHE_MAX_ENTRIES=64

원격 VoiceStudio는 HTTPS가 기본입니다. 신뢰된 사설 네트워크에서만 VOICESTUDIO_ALLOW_INSECURE_HTTP=true를 명시적으로 사용할 수 있습니다.

### 앱 API

- GET /api/voice/status — VoiceStudio 상태 확인
- GET /api/voice/voices — 사용 가능한 voice profile 조회
- POST /api/voice/speech — OpenAI-compatible VoiceStudio TTS를 서버 측에서 호출
- GET /health — bible-verse-web 자체 health check

POST /api/voice/speech 예시:

    curl http://localhost:8080/api/voice/speech \
      -H "Content-Type: application/json" \
      -d '{"text":"안녕! 오늘도 말씀을 함께 읽어볼까?","voice":"default","model":"tts-1","responseFormat":"mp3"}' \
      --output bible-friend.mp3

동일한 요청은 프로세스 메모리에서 LRU 캐시되어 반복 TTS 생성 비용과 지연을 줄입니다.

### VoiceStudio VPS

운영용 VoiceStudio Docker 예시는 deploy/voicestudio/에 있습니다. 현재 구성은 VoiceStudio 0.5.6을 고정하고 3900 포트를 loopback에만 공개합니다.
