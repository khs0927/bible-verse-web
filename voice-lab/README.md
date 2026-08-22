# Bible Friend Kotlin Voice Lab

웹 우선으로 **무료 온디바이스 한국어 TTS**를 검증하는 격리된 실험 모듈입니다. 기존 성경 웹 앱과 Production 코드는 건드리지 않습니다.

## 고정 기술 스택

- Kotlin 2.4.10
- Kotlin Multiplatform / Kotlin/JS (browser)
- JDK 21
- ONNX Runtime Web 1.25.0
- MOSS-TTS-Nano 100M ONNX (Apache-2.0)
- 기존 서버는 Kotlin/JVM + Ktor 3.5.2 유지

## 왜 이 구조인가

Kotlin/JS는 npm JavaScript 패키지와 상호운용할 수 있으므로 `onnxruntime-web`을 브라우저에서 직접 사용합니다. 음성 추론은 서버가 아니라 사용자 기기에서 실행하는 것을 목표로 합니다. 나중에 Android/iOS로 갈 때 공통 텍스트 정규화, 성경 발음 규칙, 음성 정책은 Kotlin Multiplatform 공통 코드로 이동하고, ONNX 실행부만 각 플랫폼 구현으로 교체합니다.

## 단계별 Gate

### Gate 1 — 브라우저 사전검증 (현재)

- Kotlin/JS production webpack 빌드
- ONNX Runtime Web npm 번들 로드
- WASM 존재 확인
- WASM `numThreads = 1` 강제 (iPhone에서 가장 보수적인 초기 경로)
- MOSS-TTS-Nano 공식 `tts_browser_onnx_meta.json` 접근 확인
- 필수 graph 이름 확인

실패하면 음성 합성 코드를 추가하지 않습니다.

### Gate 2 — 실제 ONNX session load

- TTS prefill/decode graph 로드
- external `.data` 자산 매핑
- MOSS Audio Tokenizer decoder 로드
- 메모리 사용량 / 초기 로드 시간 기록

### Gate 3 — 한국어 한 문장

고정 문장:

> 안녕! 오늘은 어떤 이야기를 나누어 볼까?

- 최초 합성 시간
- 첫 오디오 시간
- 전체 합성 시간
- 오류/크래시 여부

### Gate 4 — Sweet Voice

합법적이고 사용 동의가 명확한 한국어 여성 reference voice 하나만 사용합니다. Leda 음성을 복제하지 않습니다.

고정 평가 문장:

1. 하나님은 너를 정말 사랑하신단다. 오늘도 네 마음을 알고 계셔.
2. 괜찮아. 천천히 이야기해 줘. 성경 친구가 함께 들어줄게.
3. 우와, 정말 잘했어! 오늘도 하나님의 말씀을 함께 알아보자.

### Gate 5 — 모바일 반복 안정성

- iPhone Safari 20회 연속
- Android Chrome 20회 연속
- 페이지 재진입/백그라운드 복귀
- 두 번째 실행부터 캐시 활용

하나라도 안정성 기준을 통과하지 못하면 Production에는 넣지 않습니다.

## 비용 원칙

이 실험은 TTS API를 호출하지 않습니다. 목표 Production 구조에서도 사용자 기기에서 ONNX 추론을 실행해 사용자 수 증가에 따른 TTS 서버 비용을 0원으로 유지합니다.
