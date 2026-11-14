# :app — 조립 · 화면 · 카메라 (P1)

모든 모듈을 하나로 끼워서 실제로 돌아가는 앱을 만드는 곳.

| 위치 | 하는 일 |
|---|---|
| `di/` | 포트에 어떤 구현을 끼울지 정한다. **로직 금지** (if/when 없이 끼우기만) |
| `camera/` | CameraX 프레임(1280×720)을 `ImageProxyFrame : CameraFrame`으로 감싸 `FrameSink.submit()`에 넘긴다 |
| `ui/` | 화면 문구 (`UiTexts`). 화면은 `MainActivity` 하나: 큰 "말하기" 버튼 + 상태 문구 + 종료 버튼 |

## 빌드 (플레이버 없음 — 항상 실구현)

기본은 **도움 모드**(2026-11-14 확정)다. 키오스크 화면이 바뀌어도 앱이 혼자 설명하지 않고, 어르신이 **빨간 구슬을 눌러 물어볼 때만**
그 순간의 화면을 보고 답한다. 안내 음성이 나오는 중(파란 구슬)에 구슬을 누르면 음성이 바로 멈춘다.
화면이 바뀔 때마다 다음 단계를 자동으로 읽어 주던 **주문 흐름 자동 안내**를 쓰려면 `local.properties`에 `kl.helpMode=false`를 넣고
다시 빌드하고, 서버의 `HELP_MODE`도 `false`로 둔다 (**앱과 서버가 같은 모드여야 한다**).

2일차 결정으로 Fake · mock 플레이버를 없앴다 ([decisions.md](../docs/decisions.md) 6장 U3). 앱은 항상 실제 카메라 · 음성 · 서버로 동작한다.

```bash
./gradlew :app:installDebug
```

실행하려면 서버가 필요하다 (아래 "서버 연결"). 키 없이 연습할 때는 서버를 `LLM_PROVIDER=fake`로 띄우면 된다 (서버 쪽 규칙 기반 판단, 앱 코드는 그대로).

## 각 파트가 해 줄 것

1. 포트의 **실구현**을 만든다. **생성자 주입**만 쓰고, 정적 싱글톤 · `object` 전역 상태는 금지.
2. 실구현이 Android `Context`가 필요하면 생성자로 받는다. 조립은 P1이 `:app/di/GraphFactory`에서 한다.
3. 권한(카메라 · 마이크)은 각 모듈의 `AndroidManifest.xml`에 선언하면 합쳐진다. 런타임 권한 요청 화면은 P1이 만든다.

## UX · 접근성 기준 (설계서 10장)

- 본문 글자 20sp 이상 · 핵심 버튼 높이 72dp 이상 · 명도 대비 7:1 이상
- 모든 상태 변화는 문구 + 음성으로 함께 · 오류 문구에 비난 · 전문용어 금지
- TalkBack 콘텐츠 설명 지정 · 시스템 글자 크기 설정 존중

## 진행 상황

- [x] 1일차: 모듈 뼈대
- [x] 2일차: 화면(View) · CameraX → FrameSink 연결 · DI (JJ 패치) · P2 `:vision` 연결 · 실구현만으로 정리 (2026-11-10)
- [ ] 기기 확인: 실제 카메라 · STT · TTS · 서버로 "말하기 → 안내 → 하이라이트 → 완료"

## 구현 현황 (이 PR)

- [x] `MainActivity`(큰 글씨 · 큰 버튼 · TalkBack 라이브 영역 · 화면 켜짐 유지 · 권한 거절 시 설정 열기) — **Compose 대신 일반 View**
- [x] `camera/CameraController`(Preview + ImageAnalysis 4:3, 최신 한 장만 유지) · `ImageProxyFrame`
- [x] `di/`: `GraphFactory` 하나 (실구현만. vision은 P2의 `createVisionScreenAnalyzer`)
- [x] 서버 주소 · 앱 키는 `local.properties`의 `kl.serverUrl` · `kl.appKey` → `BuildConfig` (저장소에 올라가지 않음)

### 서버 연결

```properties
# local.properties (git에 안 올라감)
sdk.dir=...
kl.serverUrl=https://내-서버.example.com     # 개발 중에는 http://192.168.x.x:8000 도 가능(debug 빌드만)
kl.appKey=서버의 APP_KEY 와 같은 값
```

서버를 무료로 돌리려면 `server/.env`에 `LLM_PROVIDER=gemini`, `GEMINI_API_KEY=...` (https://aistudio.google.com/apikey).
