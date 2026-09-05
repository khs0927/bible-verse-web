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
