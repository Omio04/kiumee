import logging
import httpx
import pytest
from app.agent import FakeGuideAgent, LlmGuideAgent
from app.llm import ScriptedFakeLlmClient, LlmTimeout, LlmUpstream, LlmRateLimited, AnthropicLlmClient
from app.main import create_app
from app.settings import Settings
from tests.conftest import make_req

H = {"X-App-Key": "secret"}


def client(agent=None, **kw):
    s = Settings(app_key="secret", llm_provider="fake", **kw)
    app = create_app(s, agent or FakeGuideAgent())
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")


async def test_200(snaps):
    async with client() as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine", "포장"), headers=H)
    body = r.json()
    assert r.status_code == 200 and body["action"] == {"type": "TAP", "targetId": "e3", "guideText": "포장하기"}
    assert body["goal"] == "포장" and r.headers["x-request-id"]


async def test_200_omits_null_fields_and_has_no_flat_fields(snaps):
    async with client() as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s06_card"), headers=H)
    body = r.json()
    assert set(body) <= {"goal", "action", "source"}                 # 예전 평평한 형식(최상위 targetId·message) 없음
    assert set(body["action"]) == {"type", "guideText"} and body["action"]["type"] == "DONE"   # null 필드는 빠짐


async def test_400_missing_session_id(snaps):
    d = make_req(snaps, "s01_dine", "포장")
    del d["sessionId"]
    async with client() as c:
        r = await c.post("/v1/guide/next", json=d, headers=H)
    assert r.status_code == 400 and "sessionId" in r.json()["error"]["message"]


async def test_healthz():
    async with client() as c:
        assert (await c.get("/healthz")).json()["ok"] is True


@pytest.mark.parametrize("hdr", [{}, {"X-App-Key": "wrong"}])
async def test_401(snaps, hdr):
    async with client() as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine", "포장"), headers=hdr)
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


async def test_empty_server_key_rejects_all(snaps):
    app = create_app(Settings(app_key="", llm_provider="fake"), FakeGuideAgent())
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine"), headers={"X-App-Key": ""})
    assert r.status_code == 401


async def test_400_does_not_echo_input(snaps):
    bad = make_req(snaps, "s01_dine", "비밀발화XYZ")
    bad["snapshot"]["elements"][0]["rect"] = {"left": 0.9, "top": 0, "right": 0.1, "bottom": 1}
    async with client() as c:
        r = await c.post("/v1/guide/next", json=bad, headers=H)
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_request"
    assert "XYZ" not in r.text and "0.9" not in r.text


async def test_400_not_json_and_image_field(snaps):
    async with client() as c:
        assert (await c.post("/v1/guide/next", content=b"nope", headers=H)).status_code == 400
        d = make_req(snaps, "s01_dine"); d["image"] = "not base64 !!"
        assert (await c.post("/v1/guide/next", json=d, headers=H)).status_code == 400       # 사진은 선택이지만 base64가 아니면 거절


async def test_oversize(snaps):
    async with client(max_body_bytes=1000) as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s02_menu"), headers=H)
    assert r.status_code == 400


async def test_429_with_retry_after(snaps):
    async with client(rate_limit_per_min=2) as c:
        rs = [await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine", "포장"), headers=H) for _ in range(3)]
    assert [r.status_code for r in rs] == [200, 200, 429]
    assert int(rs[2].headers["retry-after"]) >= 1 and rs[2].json()["error"]["code"] == "rate_limited"


@pytest.mark.parametrize("exc,status,code", [(LlmTimeout("x"), 504, "llm_timeout"), (LlmUpstream("auth"), 502, "llm_error"),
                                             (LlmRateLimited(7), 429, "rate_limited")])
async def test_llm_errors_mapped(snaps, exc, status, code):
    agent = LlmGuideAgent(ScriptedFakeLlmClient([exc]), Settings(app_key="secret", fastpath=False))
    async with client(agent) as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine", "뭐요"), headers=H)
    assert r.status_code == status and r.json()["error"]["code"] == code


async def test_unexpected_exception_is_500_without_leak(snaps):
    class Boom:
        async def next(self, req): raise RuntimeError("비밀발화XYZ")
    async with client(Boom()) as c:
        r = await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine", "포장"), headers=H)
    assert r.status_code == 500 and "XYZ" not in r.text


async def test_logs_have_no_request_text(snaps, caplog):
    caplog.set_level(logging.INFO)
    async with client() as c:
        await c.post("/v1/guide/next", json=make_req(snaps, "s01_dine", "비밀발화XYZ 포장"), headers=H)
        bad = make_req(snaps, "s01_dine", "비밀발화XYZ"); bad["x"] = 1
        await c.post("/v1/guide/next", json=bad, headers=H)
    assert "KL_PERF" in caplog.text and "XYZ" not in caplog.text and "포장" not in caplog.text


# ---- AnthropicLlmClient: 네트워크 없이 MockTransport로 ----
def anth(handler):
    return AnthropicLlmClient("sk-test", "m", http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


async def test_anthropic_ok_and_request_shape():
    seen = {}
    def h(req):
        seen["h"] = req.headers; seen["b"] = req.content
        return httpx.Response(200, json={"content": [{"type": "text", "text": '{"action":"DONE"}'}]})
    assert await anth(h).complete("s", "u", 4) == '{"action":"DONE"}'
    assert seen["h"]["x-api-key"] == "sk-test" and b"image" not in seen["b"]


@pytest.mark.parametrize("status,exc", [(401, LlmUpstream), (403, LlmUpstream), (400, LlmUpstream), (500, LlmUpstream),
                                        (429, LlmRateLimited)])
async def test_anthropic_status_mapping(status, exc):
    with pytest.raises(exc):
        await anth(lambda r: httpx.Response(status, json={"error": "x"}, headers={"retry-after": "3"})).complete("s", "u", 4)


async def test_anthropic_timeout_mapped():
    def h(req): raise httpx.ReadTimeout("t")
    with pytest.raises(LlmTimeout):
        await anth(h).complete("s", "u", 4)


async def test_anthropic_slow_response_times_out():
    import asyncio
    async def slow(req):
        await asyncio.sleep(1)
        return httpx.Response(200, json={"content": []})
    with pytest.raises(LlmTimeout):
        await anth(slow).complete("s", "u", 0.1)


# ---- GeminiLlmClient: 네트워크 없이 MockTransport로 ----
def gem(handler):
    from app.llm import GeminiLlmClient
    return GeminiLlmClient("g-test", "gemini-x", http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


async def test_gemini_ok_and_request_shape():
    seen = {}
    def h(req):
        seen["h"] = req.headers; seen["b"] = req.content; seen["u"] = str(req.url)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": '{"action":'}, {"text": '"DONE"}'}]}}]})
    assert await gem(h).complete("s", "u", 4) == '{"action":"DONE"}'
    assert seen["h"]["x-goog-api-key"] == "g-test" and "key=" not in seen["u"] and b"image" not in seen["b"]
    assert b"application/json" in seen["b"] and b"gemini-x" in seen["u"].encode()


@pytest.mark.parametrize("status,exc", [(401, LlmUpstream), (403, LlmUpstream), (400, LlmUpstream), (404, LlmUpstream),
                                        (500, LlmUpstream), (429, LlmRateLimited)])
async def test_gemini_status_mapping(status, exc):
    with pytest.raises(exc):
        await gem(lambda r: httpx.Response(status, json={"error": "x"}, headers={"retry-after": "3"})).complete("s", "u", 4)


async def test_gemini_empty_candidates_returns_empty_text():
    assert await gem(lambda r: httpx.Response(200, json={"candidates": []})).complete("s", "u", 4) == ""


async def test_gemini_timeout_mapped():
    def h(req): raise httpx.ReadTimeout("t")
    with pytest.raises(LlmTimeout):
        await gem(h).complete("s", "u", 4)


def test_gemini_requires_key():
    from app.llm import GeminiLlmClient
    with pytest.raises(ValueError):
        GeminiLlmClient("", "m")
