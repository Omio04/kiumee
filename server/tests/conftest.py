import copy, json, pathlib
import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent


@pytest.fixture(scope="session")
def snaps():
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in (ROOT / "testdata/snapshots").glob("*.json")}


def make_req(snaps, screen, utterance=None, history=None, goal=None, session="test-session"):
    """계약 v1 요청 본문. utterance=이번 발화, goal=앱이 돌려준 직전 목표."""
    return {"sessionId": session, "utterance": utterance, "goal": goal,
            "snapshot": copy.deepcopy(snaps[screen]), "history": history or []}
