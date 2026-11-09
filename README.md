# kiumee — AI 키오스크 렌즈 (Android MVP)

스마트폰 카메라로 키오스크 화면을 비추고 원하는 것을 말하면, 눌러야 할 버튼을 하이라이트와 음성으로 안내하는 앱.

## 모듈

| 모듈 | 종류 | 담당 | 내용 |
|---|---|---|---|
| `:core-model` | 순수 Kotlin | P1 | 데이터 모델 (`model/`) + 포트 인터페이스 (`port/`) — 변경은 전원 승인 PR |
| `:domain` | 순수 Kotlin | P1 | 상태머신 · 유스케이스 · 정책 · SessionRunner |
| `:vision` | Android 라이브러리 | P2 | OCR · 요소 구조화 · 화면 전환 감지 |
| `:voice` | Android 라이브러리 | P3 | STT · TTS |
| `:agent` | Android 라이브러리 | P4 | 서버 클라이언트 (`server/`도 P4) |
| `:overlay` | Android 라이브러리 | P5 | 좌표 변환 · 하이라이트 |
| `:app` | Android 앱 | P1 | 조립 (DI) · UI · 카메라 연결 |

의존 규칙: `:app` → 전부 / 어댑터 → `:domain` · `:core-model`만 / 어댑터끼리 의존 금지 (위반 시 빌드 실패)

## 빌드

```bash
./gradlew :core-model:test :domain:test   # 순수 모듈 단위 테스트
./gradlew :app:assembleMockDebug          # 모든 포트가 Fake인 mock 플레이버
./gradlew :app:assembleRealDebug          # 실구현 플레이버
```

`local.properties`에 `sdk.dir`이 필요합니다 (Android Studio로 열면 자동 생성).

## 문서

- 결정 기록: [docs/decisions.md](docs/decisions.md)
- 브랜치: `feat/p{번호}-{작업}` · `main` 직접 push 금지 · 병합 13:00 / 19:00
