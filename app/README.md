# :app — 조립 · 화면 · 카메라 (P1)

모든 모듈을 하나로 끼워서 실제로 돌아가는 앱을 만드는 곳.

| 위치 | 하는 일 |
|---|---|
| `di/` | 포트에 어떤 구현을 끼울지 정한다. **로직 금지** (if/when 없이 끼우기만) |
| `camera/` | CameraX 프레임(1280×720)을 `ImageProxyFrame : CameraFrame`으로 감싸 `FrameSink.submit()`에 넘긴다 |
| `ui/` | Compose 화면: 큰 "말하기" 버튼 + 상태 문구 + 종료 버튼 |

## 빌드 변형 (플레이버)

| 플레이버 | 포트에 끼우는 것 | 쓰임 |
|---|---|---|
| `mock` | 모든 포트에 **Fake** | 다른 파트 없이 전체 흐름 확인 · UI 개발 |
| `real` | 실구현 | 실제 시연 |
| (mixed) | 내 파트만 실구현, 나머지 Fake | 각자 실구현을 앱 안에서 확인할 때 (2일차) |

```bash
./gradlew :app:installMockDebug
```

## 각 파트가 해 줄 것

1. 자기 모듈에 `Fake*` 클래스를 만든다 (예: `:voice`의 `FakeSpeechInput`). **생성자 주입**만 쓰고, 정적 싱글톤 · `object` 전역 상태는 금지.
2. 실구현이 Android `Context`가 필요하면 생성자로 받는다. 조립은 P1이 `:app/di`에서 한다.
3. 권한(카메라 · 마이크)은 각 모듈의 `AndroidManifest.xml`에 선언하면 합쳐진다. 런타임 권한 요청 화면은 P1이 만든다.

## UX · 접근성 기준 (설계서 10장)

- 본문 글자 20sp 이상 · 핵심 버튼 높이 72dp 이상 · 명도 대비 7:1 이상
- 모든 상태 변화는 문구 + 음성으로 함께 · 오류 문구에 비난 · 전문용어 금지
- TalkBack 콘텐츠 설명 지정 · 시스템 글자 크기 설정 존중

## 진행 상황

- [x] 1일차: 모듈 · 플레이버 뼈대
- [ ] 1일차: mock 플레이버 조립 (각 파트 Fake가 병합되면)
- [ ] 2일차: Compose 화면 · CameraX → FrameSink 연결 (P5 PoC 재사용) · DI

## 구현 현황 (이 PR)

- [x] `MainActivity`(큰 글씨 · 큰 버튼 · TalkBack 라이브 영역 · 화면 켜짐 유지 · 권한 거절 시 설정 열기) — **Compose 대신 일반 View**
- [x] `camera/CameraController`(Preview + ImageAnalysis 4:3, 최신 한 장만 유지) · `ImageProxyFrame`
- [x] `di/`: 플레이버별 `GraphFactory`(real: 실구현 / mock: 전부 Fake + 샘플 카페 화면 + "다음 화면" 버튼)
- [x] 서버 주소 · 앱 키는 `local.properties`의 `kl.serverUrl` · `kl.appKey` → `BuildConfig` (저장소에 올라가지 않음)

### 서버 연결

```properties
# local.properties (git에 안 올라감)
sdk.dir=...
kl.serverUrl=https://내-서버.example.com     # 개발 중에는 http://192.168.x.x:8000 도 가능(debug 빌드만)
kl.appKey=서버의 APP_KEY 와 같은 값
```

서버를 무료로 돌리려면 `server/.env`에 `LLM_PROVIDER=gemini`, `GEMINI_API_KEY=...` (https://aistudio.google.com/apikey).
