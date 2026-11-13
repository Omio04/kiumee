# AI 키오스크 렌즈 · P4 에이전트 서버 (v0.2 · 계약 v1)

`POST /v1/guide/next` — OCR 글자·좌표와 어르신 발화를 받아 "다음에 누를 버튼 하나"를 돌려줍니다. **사진(`image`, base64 JPEG)은 선택**이며 켠 경우에만 옵니다(저장하지 않고 LLM에만 보여 줌, 민감한 글자가 읽히면 버림). 그 밖의 모르는 필드는 400.
응답에는 선택 필드 `say`(버튼을 가리킬 때 먼저 읽을 이유 한 문장)와 `box`(글자 목록에 없는 칸을 사진에서 찾았을 때의 위치, targetId는 "photo")가 있을 수 있습니다. 모르는 앱은 무시합니다.
설정은 `server/.env`(KEY=VALUE)에 넣으면 읽습니다. 명령줄 환경변수가 우선. Claude가 일시 오류를 내면 `GEMINI_API_KEY`가 있을 때 Gemini가 대신 답합니다.

## 실행
```
pip install -r requirements-dev.txt
KL_SKIP_APP_INIT=1 pytest -q                       # 테스트 105개 (키·네트워크 불필요)
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
- `action.type`: TAP(`targetId`+`guideText`) · ASK_USER(`question`) · DONE(`guideText`) · UNKNOWN(`reason`) · SCROLL(`direction`+`guideText`=**찾는 대상 이름**, 문장 아님) · WAIT(`guideText`=읽을 문장)
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

## 5일 계획 P4 추가 작업 (feat/p4-agent-server)

| 항목 | 내용 | 왜 |
|---|---|---|
| 구조화 출력 | Anthropic: 도구 `decide`를 `tool_choice`로 강제 / Gemini: `responseSchema` (`llm.py` `ACTION_SCHEMA`) | 프롬프트로 "JSON만" 부탁하는 것보다 형식 오류(= 재요청 = 지연)가 줄어든다. 파서는 그대로 두어 텍스트 응답도 받는다 |
| 총 처리 예산 | `TOTAL_BUDGET_S`(5초) 안에서만 LLM 재요청. 시도마다 `min(LLM_TIMEOUT_S, 남은 시간)` | 전엔 4초×2회=8초까지 늘어나 앱 타임아웃(6초)을 넘길 수 있었다 |
| 응답 캐시 | `cache.py`. 같은 화면·목표·이력이면 LLM 없이 직전 답 (TTL 30초, 성공한 판단만, 메모리에만) | 앱의 1회 재시도가 첫 요청이 끝난 뒤에 오면 즉시 응답 |
| SCROLL · WAIT | LLM이 `SCROLL`(+direction) · `WAIT`를 고를 수 있고 `ActionValidator`가 계약 형식으로 변환 | 계약 v1에 있었지만 서버가 안 보내던 두 행동 (결정 L의 "P4 판단"). 앱은 이미 처리 가능 |
| 동의어 사전 | 확인/취소/이전/큰·작은 등 말투 보강 | 후보 축소 누락 방지 |

**P1 보완 (2026-11-10, [decisions.md](../docs/decisions.md) 7장)**
- SCROLL `guideText`는 **찾는 대상 이름**이다 (앱: "화면을 아래로 밀어서 {guideText} 찾아 주세요"). LLM의 `guideText`를 쓰고, 문장 · 20자 초과 · 개인정보로 보이면 "원하시는 메뉴". 프롬프트 · `ACTION_SCHEMA` 설명도 맞춤
- 짧은 대답("네" · "좋아요" · "다 됐어요" · "끝" · "아니요" · "그만")은 **이번 발화 전체가 대답일 때만** 버튼 글자로 바꾼다(`ranker.ANSWERS`). "들어 있으면 붙이기"로는 "끝까지 시원한 걸로 하나 더요" → 결제하기를 골랐다 (`tests/test_answers.py`)
| 지연 측정 | `scripts/loadtest.py` (HTTP로 p50/p95/p99, `--fail-over-ms 2000`) | 4일차 p95 2초 합격 기준 |
| 배포 | `render.yaml` · Dockerfile `HEALTHCHECK` | 3일차 클라우드 배포 |

배포(Cloud Run 예): `gcloud run deploy kiosk-lens-agent --source server --region asia-northeast3 --set-env-vars LLM_PROVIDER=gemini --set-secrets APP_KEY=app-key:latest,GEMINI_API_KEY=gemini-key:latest`
(키는 Secret Manager에. 요청 하나가 6초 안에 끝나야 하므로 `--min-instances 1`로 콜드 스타트를 피하는 것을 권장.) **실제 배포는 이 환경에서 해보지 않았다.**

### 이 작업에서 확인하지 못한 것
- 실제 Anthropic · Gemini 호출 (키 없음): 요청 모양은 MockTransport 테스트로만 확인했다. 도구 호출/`responseSchema`가 실제 API에서 받아들여지는지, 실제 정확도(85%)와 p95(2초)는 키를 넣고 `evaluate.py` · `loadtest.py`로 재야 한다.
- Docker 빌드, 클라우드 배포.

## 음식 설명 · 새 화면 안내 (2026-11-13, feat/p4-explain-and-screen-guide)

| 어르신이 말한 것 | 서버가 하는 일 | 앱에 나가는 것 |
|---|---|---|
| "라떼가 뭐야?" "이거 어떤 음식이야?" | 로컬 메뉴 사전(`menu_info.py`)으로 즉시 설명(LLM 없음). 사전에 없으면 LLM `EXPLAIN`. 메뉴 이름이 없으면 방금 안내한 버튼을 가리킨다고 봄 | `ASK_USER`(설명 + "이걸로 하실래요?"). **goal은 그대로** — 질문이 목표에 섞이지 않는다 |
| "뭘 눌러야 돼요?" "뭐 할 수 있어요?" | 지금 화면의 선택지를 읽어 줌(`screen_guide.py`, LLM 없음) | `ASK_USER` |
| (말 없이) 화면이 바뀌었는데 고를 버튼이 분명치 않음 | "버튼을 못 찾았어요" 대신 "화면이 바뀌었어요. HOT, ICE 중에서 고를 수 있어요. 어떤 걸로 할까요?" | `ASK_USER` |

- 계약(`docs/contract.md`) 변경 없음: 새 행동 종류를 만들지 않고 기존 `ASK_USER`를 쓴다. 앱은 읽은 뒤 바로 듣는다.
- 사전은 흔한 카페 메뉴·옵션 약 50개. 사전 밖 메뉴만 LLM이 답하며 성분·알레르기·가격은 직원에게 묻게 한다.
- 겹쳐 보내기 대기 2.0 → 1.6초 (`HEDGE_AFTER_S`): 가끔 3초 넘게 걸리는 LLM 호출에 대비.
- 속도 측정(Gemini 3.5 flash-lite, 2026-11-13): 기본 호출 약 1.6~1.9초 · 생각 토큰 없음 → 지연을 줄이려면 LLM을 안 부르는 경로(사전 · 규칙 · 빠른 길)를 늘리는 것이 효과적.

## 어르신 사용 가정 시뮬레이션 · 말투 처리 (2026-11-13)

**자료조사로 확인한 것** (직접 읽은 요약과 출처. 한국 키오스크 · 노인 대상 직접 비교 연구는 찾지 못함):
- 노인 음성비서 한 달 관찰(15명, 66~94세): 상호작용의 약 1/4이 오류, 주 단위로 줄지 않음, 다시 말해도 약 1/4만 해결 — [arXiv 2403.02421](https://arxiv.org/html/2403.02421v3)
- 원인: 느린 말 · 더듬음 · 쉼 · 반복 · 길고 복잡한 말, 낮은 목소리 — [arXiv 2412.07388](https://arxiv.org/pdf/2412.07388)
- 한국 노인 음성: 20~60대로 학습한 모델은 65세 이상에서 쉽게 틀린다 — [VOTE400, arXiv 2101.11469](https://arxiv.org/pdf/2101.11469.pdf)
- 응답 지연: 무음 대기는 불신을 키우고 짧은 안내가 체감 지연을 줄인다는 실무 자료 (2초 기준은 2차 출처, **원문 미확인**)

**그래서 한 일**
| 문제 | 해결 | 위치 |
|---|---|---|
| 군말("어... 그... 음")이 목표에 섞임 | 군말 제거 | `server/app/elder.py` |
| "뭐라고요? 다시 말해줘"가 주문에 붙음 | 방금 안내를 다시(질문 · 버튼), goal 그대로 | 〃 |
| "직원 불러줘" · "그만할래요" | 안내를 끝내고 직원 안내 | 〃 |
| "아메리카노 말고 라떼"에서 아메리카노를 또 고름 | 부정한 것이 이전 목표에 있을 때만 목표 교체 ("샷 추가 말고 시럽"은 목표 유지) | 〃 |
| 화면에 없는 "현금" | "현금은 이 화면에서 고를 수 없어요. 카드로 하실래요, 직원에게 물어볼까요?" | 〃 |
| 서버 답을 기다리는 1~3초 동안 무음 | 1.5초 넘으면 "잠시만요. 찾고 있어요." | `:domain` `TimeoutPolicy.thinkingMs` |
| 음식 · 화면 질문 | 로컬 사전 · 화면 안내 (위 절) | `menu_info.py` `screen_guide.py` |

**시뮬레이터**: `python scripts/simulate.py --llm gemini --pace 3` — 가상 노인 17명(또렷 · 군말 · 오인식 · 사투리 · 장황 · 질문 · 되묻기 · 마음 바꿈 · 도움 요청 · 카드번호 …)이 앱처럼 주문 흐름을 끝까지 진행한다. 질문 주제(매장/HOT/추가/결제)에 맞게 대답하고, 옵션 · 탭 버튼을 누르면 앱처럼 새 화면 번호를 받는다.
⚠️ 서버 판단 로직의 약점을 찾는 도구이지 **실제 노인 정확도가 아니다**. 가짜 LLM(`heuristic`)은 옵션 화면 이후를 못 풀어 흐름 점검용으로만 쓴다.

### 시뮬레이션 결과와 기법 (2026-11-13, Claude Haiku 5.5, 생각 끔)

| 단계 | 통과 | 비고 |
|---|---|---|
| 결함 수정 전 (Gemini 3.5 flash-lite) | 0/17 | **옵션 화면에서 "HOT을 누르세요"를 무한 반복** (빠른 길이 방금 누른 옵션을 또 고름) — 실제 앱에도 있던 결함 |
| 수정 후, 진행 요약 끔 | 15/17 | 응답 165회 |
| 수정 후, 진행 요약 켬 | 15/17 | 응답 158회. 메뉴를 물어본 시나리오가 끝까지 감 |
| 설명에 메뉴 이름 넣기 + 시뮬레이터가 고른 메뉴 반영 | **17/17** | |

- **진행 요약(`app/progress.py`)**: 1인칭 영상 기반 선제 안내 연구(ProAssist, [arXiv 2506.05904](https://arxiv.org/html/2506.05904v1))의 "안내를 과제 계획과 진행 상태에 묶는다"는 설계를 주문 흐름에 맞게 옮긴 것이다. 모델 · 학습 자료를 가져온 것이 아니라 "지금까지 / 다음 단계" 한 줄을 프롬프트에 넣는 방식이다. `PROGRESS_HINT=false`로 끈다.
- **효과는 작다**: 통과 수는 같고 응답 횟수가 약 4% 줄었다. 표본이 작고 LLM 응답에 무작위가 있어 **개선을 단정하지 않는다.**
- **Haiku 5.5 생각 끄기**: 응답 1.5~1.8초 → 약 1.2초, 답 동일 (4회씩 측정). `AnthropicLlmClient`에 `thinking: disabled`.
- ⚠️ 17/17은 **가상 노인 · 가짜 화면 6개** 기준이다. 실제 키오스크 · 실제 음성 인식 오류 · 실제 노인 정확도가 아니다.

## 화면 종류 판별 · 행동 후 검증 (2026-11-13)

**화면 종류** (`app/screen_type.py`): 글자(제목 · 버튼 이름 · 가격 개수)로 지금 화면이 무엇을 하는 화면인지 먼저 정한다. 규칙 기반이라 즉시 끝나고, 모르면 UNKNOWN으로 두어 LLM이 예전처럼 판단한다.
먹고갈지/포장할지 · 메뉴 · 옵션 · 주문 내역 · 일회용품 · 적립 · 결제 수단 · 카드 결제 · 주문 끝 · 시작. 어휘는 P2 `SceneProcessor`(vision/docs/mega-coffee-handoff.md)와 맞췄다.
- LLM에 `[화면 종류] 옵션을 고르는 화면` 한 줄을 준다 (추측 대신 근거)
- 새 화면에서 되물을 때 첫머리에 화면 이름을 말한다: "옵션을 고르는 화면이에요. HOT, ICE 중에서 고를 수 있어요. 어떤 걸로 할까요?"
- **적립 화면은 LLM 없이** 실제로 보이는 닫기(×)를 안내한다 (P2 인수 문서: 적립은 건너뛴다). ×가 안 보이면 지어내지 않고 "닫기(×)가 잘 안 보여요. 화면이 잘 보이게 비춰 주세요". 어르신이 "적립"을 말하면 건너뛰지 않는다.

**행동 후 검증** (`app/expectation.py`): 메뉴를 눌렀다던 안내 뒤 새 화면 어디에도 그 이름이 안 보이면(옵션 창 제목 · 장바구니 · 오타 한두 글자 포함해서 없음) LLM에 `[기대와 다름]`을 알려 다음 단계로 넘어가지 않게 한다. 화면 에이전트 연구(EcoAgent · GUI-Reflection · InfiGUIAgent)의 "행동 뒤 기대한 화면과 비교해 어긋나면 복구"를 규칙 하나로 옮긴 것이다. `EXPECTATION_CHECK=false`로 끈다.

⚠️ 판별은 **글자 규칙**이라 실제 키오스크의 문구가 사전과 다르면 UNKNOWN이 되어 예전처럼 LLM이 판단한다. 실제 메가커피 화면의 글자로 사전을 넓혀야 한다 (`DEBUG_PROMPTS=1`로 실제 화면 글자와 `screen=` 판별 결과를 서버 기록에서 볼 수 있다).
시뮬레이터(가상 노인 18명 · 가짜 화면 7개, Haiku 5.5): **18/18**. 실제 키오스크 · 실제 노인 정확도가 아니다.
