"""계약 v1 예시 JSON(저장소 루트 testdata/contract/)이 서버 모델과 정확히 맞는지 확인합니다.

같은 파일을 앱(:agent)의 DTO 테스트에서도 읽으면, 앱과 서버가 같은 정답지를 보게 됩니다.
예시를 바꾸면 docs/contract.md도 같이 고치세요.
"""
import json
import pathlib

import httpx
import pytest

from app.agent import FakeGuideAgent
from app.errors import body
from app.main import create_app
from app.models import ActionType, GuideRequest, GuideResponse
from app.settings import Settings

CONTRACT = pathlib.Path(__file__).resolve().parents[2] / "testdata" / "contract"
pytestmark = pytest.mark.skipif(not CONTRACT.is_dir(),
                                reason="저장소 루트의 testdata/contract 가 없음 (server/만 따로 복사한 경우)")


def load(name):
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def names(prefix):
    return sorted(p.name for p in CONTRACT.glob(f"{prefix}_*.json")) if CONTRACT.is_dir() else []


@pytest.mark.parametrize("name", names("request"))
def test_request_examples_round_trip(name):
    d = load(name)
    assert GuideRequest.model_validate(d).model_dump(mode="json") == d


@pytest.mark.parametrize("name", names("response"))
def test_response_examples_round_trip(name):
    d = load(name)
    assert GuideResponse.model_validate(d).model_dump(mode="json", exclude_none=True) == d


def test_every_action_type_has_an_example():
    seen = {load(n)["action"]["type"] for n in names("response")}
    assert seen == {t.value for t in ActionType}


def test_error_example_matches_server_format():
    d = load("error_401.json")
    assert d == body(d["error"]["code"], d["error"]["message"], d["error"]["requestId"])


@pytest.mark.parametrize("name", names("request"))
async def test_server_answers_examples_within_contract(name):
    req = load(name)
    app = create_app(Settings(app_key="k", llm_provider="fake"), FakeGuideAgent())
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        r = await c.post("/v1/guide/next", json=req, headers={"X-App-Key": "k"})
    assert r.status_code == 200
    resp = GuideResponse.model_validate(r.json())
    if resp.action.type == ActionType.TAP:                          # 계약: TAP의 targetId는 요청 스냅샷에 있어야 함
        assert resp.action.targetId in {e["id"] for e in req["snapshot"]["elements"]}
