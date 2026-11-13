"""Screen-driven guidance for one iced Americano in the kiosk practice flow."""
import re
from collections.abc import Callable

from .jamo import normalize
from .models import ActionType, ElementKind, GuideAction, GuideRequest

# Observed Korean ML Kit errors in the temperature prefix only. Keep the drink
# name exact, preserve original text/target IDs, and never accept HOT or variants.
_ITEM = re.compile(r"(?:ice|1ce|lce|ce)아메리카노(?:\d+원)?")
_CHECKOUT = re.compile(r"(?:\d+원)?결제하기")
_ADD = {"주문담기", "장바구니담기"}
_RESCAN = "키오스크 화면 전체가 잘 보이게 비춰 주세요."


def iced_americano_goal(goal: str) -> bool:
    # Match the plain drink, leaving named variants and multi-item requests to the general guide.
    return re.fullmatch(
        r"(?:아이스|ice)아메리카노(?:를)?(?:한잔|1잔|하나|한개|1개)?"
        r"(?:마시고싶(?:어(?:요)?|습니다)|먹고싶(?:어(?:요)?|습니다)|"
        r"주문(?:해줘(?:요)?|할래요|하고싶어요)|주세요|줘(?:요)?|부탁해요)?",
        normalize(goal),
    ) is not None


def kiosk_action(req: GuideRequest, goal: str) -> GuideAction | None:
    elements = req.snapshot.elements
    labels = [normalize(e.text) for e in elements]
    buttons = [(normalize(e.text), e) for e in elements if e.kind == ElementKind.BUTTON_LIKE]

    def seen(*parts: str) -> bool:
        return any(part in label for part in parts for label in labels)

    def wait(text: str, choices: Callable[[str, str], bool] | None = None) -> GuideAction:
        targets = [e.id for key, e in buttons if choices and choices(key, e.text)][:12]
        return GuideAction(type=ActionType.WAIT, guideText=text, targetIds=targets or None)

    def tap(keys: set[str], text: str) -> GuideAction:
        target = next((e for key, e in buttons if key in keys), None)
        return GuideAction.tap(target.id, text) if target else GuideAction.unknown(_RESCAN)

    if seen("결제에실패", "결제실패", "승인실패", "승인거절", "승인이거절", "결제가완료되지", "결제가취소되었습니다"):
        return wait("결제가 완료되지 않았어요. 화면의 오류 내용을 확인하고 다시 시도하거나 직원에게 도움을 요청해 주세요.")
    if seen("결제가완료", "결제완료", "주문이완료", "주문완료") and seen("주문번호", "픽업대"):
        return GuideAction.done("번호표를 받고 기다려 주세요. 주문번호가 불리면 픽업대에서 받아 주세요.")
    if seen("영수증을출력", "정상승인"):
        return wait("영수증이 필요하면 출력을, 필요 없으면 미출력을 눌러 주세요.",
                    lambda key, _: key in {"출력", "미출력"})
    if seen("승인중", "결제진행중", "결제중"):
        return wait("결제가 진행 중이에요. 잠시 기다려 주세요.")
    if seen("카드결제간편결제", "카드를투입", "카드를넣", "체크카드를넣", "카드를삽입", "카드를태그", "카드투입") or (
        "카드번호" in labels and "승인요청" in labels
    ):
        text = "카드 단말기에 카드를 넣어 주세요. 삼성페이는 휴대폰을 카드 리더기에 대 주세요."
        if any(key == "승인요청" for key, _ in buttons):
            text = "카드는 단말기에 넣고 삼성페이는 휴대폰을 리더기에 대 주세요. 연습 화면에서는 승인 요청을 눌러 주세요."
        return wait(text, lambda key, _: key == "승인요청")
    # Payment/receipt progress is independent of the drink. Do not terminate the
    # session just because the user amended the original goal (e.g. takeout).
    if not iced_americano_goal(goal):
        return None
    if seen("스탬프적립", "포인트적립"):
        return wait("적립하려면 전화번호를 입력하고 적립을 눌러 주세요. 적립하지 않으시면 오른쪽 위 X를 눌러 주세요.",
                    lambda key, text: key == "적립" or text in {"×", "X", "x"})
    if seen("결제수단선택", "결제수단을선택"):
        text = "카드 결제나 삼성페이 등 원하는 결제 수단을 눌러 주세요."
        if seen("삼성페이애플페이"):
            text = "원하는 결제 수단을 눌러 주세요. 삼성페이는 카드결제를 눌러 주세요."
        return wait(text, lambda key, _: key.startswith(("카드결제", "앱카드", "현금", "모바일상품권",
                    "선불카드", "cj기프트카드", "현대m포인트")) or (
                        ("페이" in key or key.endswith("pay")) and key != "삼성페이애플페이"
                    ))
    if seen("일회용품필요여부", "일회용품선택"):
        return wait("필요한 일회용품을 고른 뒤 선택완료를 눌러 주세요. 필요 없으면 선택안함을 눌러 주세요.",
                    lambda key, _: key in {"선택완료", "선택안함", "빨대스틱필요", "캐리어봉투필요", "직접가져갈게요"})
    if seen("주문세부내역"):
        return wait("주문 내역을 확인하고 먹고가기 또는 포장하기를 눌러 주세요.",
                    lambda key, _: key.startswith(("먹고가기", "포장하기")))
    if seen("옵션상품을모두선택", "선택된옵션"):
        if not any(_ITEM.fullmatch(key) for key in labels):
            if any(key.startswith(("ice", "hot")) for key in labels):
                return tap({"닫기", "취소", "이전", "x"}, "다른 음료가 열렸어요. 닫기나 취소를 눌러 주세요.")
            return GuideAction.unknown(_RESCAN)
        text = "원하는 옵션이 있으면 선택해 주세요. 없거나 다 고르셨으면 주문담기를 눌러 주세요."
        if seen("추가로선택할옵션이없"):
            text = "추가 옵션이 없어요. 주문담기를 눌러 주세요."
        if "장바구니담기" in labels and "주문담기" not in labels:
            text = text.replace("주문담기", "장바구니 담기")
        if seen("추가로선택할옵션이없"):
            return tap(_ADD, text)
        return wait(text, lambda key, _: key in _ADD or key in {"연하게", "텀블러개인컵사용",
                    "아메리카노꿀팁옵션"} or key.endswith("추가"))

    cart = next((e for e in elements if normalize(e.text) == "장바구니조회"), None)
    if cart and any(_ITEM.fullmatch(normalize(e.text)) and e.rect.top >= cart.rect.bottom for e in elements):
        checkout = {key for key, _ in buttons if _CHECKOUT.fullmatch(key)}
        return tap(checkout, "장바구니를 확인하고 결제하기를 눌러 주세요.")
    if cart:
        buttons = [(key, e) for key, e in buttons if e.rect.bottom <= cart.rect.top]
    menu = {key for key, _ in buttons if _ITEM.fullmatch(key)}
    return tap(menu, "아이스 아메리카노를 눌러 주세요.")
