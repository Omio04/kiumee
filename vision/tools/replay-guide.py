"""Run the production guide on the ML Kit/ScreenTracker replay; no paid LLM calls.

python vision/tools/replay-guide.py
Requires server dependencies. First run :vision:testDebugUnitTest.
"""
import asyncio
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings


async def main():
    source = Path(sys.argv[1]) if len(sys.argv)>1 else ROOT / "vision/build/handheld-replay.json"
    frames = json.loads(source.read_text(encoding="utf-8"))["frames"]
    client = ScriptedFakeLlmClient([])
    agent = LlmGuideAgent(client, Settings(app_key="offline-test", help_mode=False))
    goal, history, last_id = None, [], None
    steps = []
    for frame in frames:
        snapshot = frame["snapshot"]
        if not snapshot or snapshot["screenId"] == last_id:
            continue
        last_id = snapshot["screenId"]
        request = GuideRequest.model_validate(dict(sessionId="handheld-order", goal=goal,
            utterance=None if goal else "아이스 아메리카노 한 잔 주세요", snapshot=snapshot, history=history[-5:]))
        response = (await agent.next(request)).response
        action = response.action
        print(frame["stage"], action.type.value, action.guideText or action.reason)
        steps.append(dict(stage=frame["stage"], request=request.model_dump(exclude_none=True),
                          response=response.model_dump(exclude_none=True)))
        goal = response.goal
        history.append(dict(screenId=last_id, action=action.type.value, targetText=action.guideText or action.question))
    assert not client.calls, "Exercise should not need a speculative LLM fallback"
    expected = ["TAP", "WAIT", "TAP", "WAIT", "WAIT", "WAIT", "WAIT", "WAIT", "WAIT", "WAIT", "DONE"]
    assert [s["response"]["action"]["type"] for s in steps] == expected
    destination = Path(sys.argv[2]) if len(sys.argv)>2 else ROOT / "vision/testdata/kiosk/handheld-guidance.json"
    destination.write_text(json.dumps(steps, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Verified {len(steps)} stages, one initial utterance; saved {destination}")


if __name__ == "__main__":
    asyncio.run(main())
