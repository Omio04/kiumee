# AI 키오스크 렌즈 · P4 에이전트 서버 (v0.2 · 계약 v1)

`POST /v1/guide/next` — OCR 글자·좌표와 어르신 발화를 받아 "다음에 누를 버튼 하나"를 돌려줍니다. **이미지는 받지 않습니다**(필드가 있으면 400).

## 실행
```
pip install -r requirements-dev.txt
KL_SKIP_APP_INIT=1 pytest -q                       # 테스트 82개 (키·네트워크 불필요)
APP_KEY=dev LLM_PROVIDER=fake uvicorn app.main:app --port 8080     # 키 없이 연동 연습 (Android가 바로 붙일 수 있음)
APP_KEY=... ANTHROPIC_API_KEY=... uvicorn app.main:app             # 실제 LLM
docker build -t kl-agent . && docker run -p 8080:8080 -e APP_KEY=... -e ANTHROPIC_API_KEY=... kl-agent
```
클라우드 1곳(Cloud Run/Fly/Render 등 컨테이너 가능한 곳)에는 이 Docker 이미지를 올리고 환경변수 `APP_KEY`, `ANTHROPIC_API_KEY`만 설정하면 됩니다. `PORT` 환경변수를 따릅니다.

## API (계약 v1)
**정본은 저장소 루트의 [docs/contract.md](../docs/contract.md) 3장**입니다. 예시 JSON은 `testdata/contract/`에 있고, `tests/test_contract.py`가 모델과 정확히 맞는지 검사합니다.

요청 헤더 `X-App-Key`, 본문(JSON):
```json
{"sessionId":"8f14e45f-…","utterance":"아메리카노 주세요","goal":null,
 "snapshot":{"screenId":"s02_menu","frameId":1204,"timestampMs":1762675200000,"stableFrames":3,
   "elements":[{"id":"e4","text":"아메리카노","kind":"BUTTON_LIKE","confidence":0.95,
     "rect":{"left":0.05,"top":0.25,"right":0.3,"bottom":0.4}}]},
 "history":[{"screenId":"s01_dine","action":"TAP","targetText":"포장하기"}]}
```
응답 200: `{"goal":"아메리카노 주세요","action":{"type":"TAP","targetId":"e4","guideText":"아메리카노"},"source":"fastpath"}`
- `action.type`: TAP(`targetId`+`guideText`) · ASK_USER(`question`) · DONE(`guideText`) · UNKNOWN(`reason`) · SCROLL · WAIT(계약에만 있음, 아직 안 보냄)
- 후보 밖 targetId는 절대 나가지 않음(→UNKNOWN). 문장 필드는 60자 이내 쉬운 존댓말.
- **목표는 서버가 기억하지 않습니다.** 앱이 직전 응답의 `goal`을 다음 요청에 돌려주고, 서버는 `goal + utterance`로 새 goal을 만듭니다.
- LLM은 예전처럼 `GUIDE|ASK_USER|DONE|UNKNOWN` + `message`로 답하고, `ActionValidator`가 위 형식으로 바꿔서 내보냅니다.

오류 `{"error":{"code","message","requestId"}}`: 400 invalid_request · 401 unauthorized · 429 rate_limited(+Retry-After) · 502 llm_error · 504 llm_timeout · 500 internal. 400은 입력 값을 되돌려주지 않음.

## 처리 순서
개인정보 마스킹(카드·주민번호) → 로컬 규칙(결제 진행 문구면 LLM 없이 DONE) → 자모 n-gram 후보 K=8 → 빠른 길(거의 일치+격차 크면 LLM 생략) → LLM(4초, JSON) → 형식 오류 시 1회만 재요청 → 화이트리스트 검증.

## 평가
```
python scripts/make_fixtures.py                 # 손으로 쓴 화면 6개 + 케이스 18개 재생성
python scripts/evaluate.py --llm heuristic      # 전체 흐름 점검 (가짜 LLM)
ANTHROPIC_API_KEY=... python scripts/evaluate.py --llm anthropic --repeat 3 --fail-under 0.85
```
**주의**: fake/heuristic 결과(현재 100%)는 파이프라인 점검용이며, 케이스를 보고 동의어를 넣었기 때문에 실제 정확도가 아닙니다. 85%·p95 2초 목표는 실제 키 + **실제 키오스크 OCR 스냅샷**(같은 JSON 형식으로 `testdata/snapshots`에 추가)으로 재야 합니다. 로컬 측정: HTTP 포함 p95 약 1.3ms(가짜 LLM, 서버 자체 비용만).

## 계약 v1 반영 (2026-11-09, P1)
v0.1의 "가정" 표를 설계서 · `:core-model`에 맞춰 확정했습니다. **입출력 모양만 바뀌었고, 판단 로직(마스킹 · 규칙 · 후보 축소 · 빠른 길 · LLM · 검증)은 그대로**입니다. 테스트 60개 → 82개 통과, 평가 정확도 동일(fake 13/13 · heuristic 18/18).

| 항목 | v0.1 (가정) | v0.2 (계약 v1) |
|---|---|---|
| 요청 `sessionId` | 선택 | **필수** |
| 요청 `goal` | 없음 (history의 USER 발화로 목표 추정) | **추가**: 앱이 직전 응답의 goal을 돌려줌 |
| 요청 `utterance` | 빈 문자열 가능 | null 가능 (새 발화가 없으면 null) |
| 스냅샷 | screenId, elements | + `frameId` · `timestampMs` · `stableFrames` (필수, 앱의 ScreenSnapshot 그대로) |
| 이력 | `{role, text, targetId}` | `{screenId, action, targetText}` (앱이 실행한 안내 기록) |
| 응답 | 평평한 구조 `action` 4종 + `message` | `{goal, action: {type, …}}`, type 6종 |
| 오류 · 인증 · 모델 · 결제 범위 | — | 그대로 |

픽스처(`testdata/snapshots`)와 평가 케이스(`testdata/evalset`)는 `scripts/make_fixtures.py`로 새 형식에 맞춰 다시 만들었습니다 (평가 케이스의 `goal` = 이번 발화, `prevGoal` = 앱이 돌려준 직전 목표).

## 미검증
실제 Anthropic 호출, Docker 이미지 빌드(이 환경에 데몬 없음), 실제 OCR 입력, 실제 p95는 **아직 해보지 않았습니다**. Anthropic 호출부는 모의 HTTP(MockTransport)로 상태코드·타임아웃 매핑만 확인했습니다.

## 무료로 쓰기: Gemini

```bash
export LLM_PROVIDER=gemini
export GEMINI_API_KEY=...      # https://aistudio.google.com/apikey 에서 무료 키
export APP_KEY=긴_무작위_문자열
uvicorn app.main:app --port 8000
```

기본 모델은 `gemini-2.5-flash-lite` (`LLM_MODEL`로 변경). 무료 한도를 넘으면 429 → 앱은 잠시 뒤 다시 시도하고, 계속되면 오류 안내를 읽는다. 정확도는 `python scripts/evaluate.py --llm gemini`로 anthropic과 비교해서 정할 것 *(이 PR에서는 키가 없어 실제 호출은 확인하지 못함, MockTransport 테스트만 통과)*.
