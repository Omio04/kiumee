"""겹쳐 보내기 (P1, 2026-11-12): Gemini 무료 한도는 보통 1초인데 가끔 4초를 넘겨 서버 시간 초과 → 앱 오류가 잦았다.
답이 늦거나 혼잡(503)으로 바로 실패하면 같은 요청을 하나 더 보내고 먼저 온 답을 쓴다."""
import asyncio
import time

import pytest

from app.agent import LlmGuideAgent
from app.llm import LlmRateLimited, LlmUpstream, ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings
from .conftest import make_req


def _req(snaps):
    return GuideRequest.model_validate(make_req(snaps, "s02_menu", utterance="따뜻한 거 하나요"))


class _SlowFirst:
    """첫 호출만 느린 가짜 LLM"""

    def __init__(self, slow_s: float):
        self.slow_s, self.calls = slow_s, 0

    async def complete(self, system, user, timeout_s, target_ids=None):
        self.calls += 1
        if self.calls == 1:
            await asyncio.sleep(self.slow_s)
        return '{"action":"UNKNOWN"}'


async def test_slow_answer_is_hedged_with_a_second_request(snaps):
    llm = _SlowFirst(3.0)
    t = time.perf_counter()
    r = await LlmGuideAgent(llm, Settings(app_key="k", fastpath=False, hedge_after_s=0.1)).next(_req(snaps))
    assert llm.calls == 2 and r.meta.get("hedged")
    assert time.perf_counter() - t < 1.0                                    # 느린 첫 요청을 기다리지 않았다
    assert r.meta["llm_calls"] == 1                                         # 판단 한 번으로 센다


async def test_fast_answer_sends_only_one_request(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await LlmGuideAgent(llm, Settings(app_key="k", fastpath=False)).next(_req(snaps))
    assert len(llm.calls) == 1 and not r.meta.get("hedged")


async def test_busy_error_is_resent_at_once_but_rate_limit_is_not(snaps):
    busy = ScriptedFakeLlmClient([LlmUpstream("http_503"), '{"action":"UNKNOWN"}'])
    r = await LlmGuideAgent(busy, Settings(app_key="k", fastpath=False)).next(_req(snaps))
    assert len(busy.calls) == 2 and r.response.action.type.value == "ASK_USER"   # 재전송 성공 (UNKNOWN은 화면 안내로 바뀜)

    limited = ScriptedFakeLlmClient([LlmRateLimited(5), '{"action":"UNKNOWN"}'])
    with pytest.raises(LlmRateLimited):
        await LlmGuideAgent(limited, Settings(app_key="k", fastpath=False)).next(_req(snaps))
    assert len(limited.calls) == 1                                          # 사용량 제한은 더 보내 봐야 소용없다
