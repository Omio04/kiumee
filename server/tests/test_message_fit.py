"""LLM 문장이 길어도 상황을 알려 주는 앞 문장은 남긴다 (2026-11-13 실기기)."""
from app.validator import MAX_EXPLAIN, MSG_ASK, _clean_message


def test_the_question_that_used_to_be_replaced_is_now_read_in_full():
    q = "아이스 유자차는 이미 담으신 것 같아요. 옵션을 고르신 뒤 결제하기로 넘어갈까요, 아니면 다른 메뉴를 더 담으실래요?"
    assert 60 < len(q) <= MAX_EXPLAIN                      # 예전 60자 제한에 걸려 "무엇을 드시고 싶으세요?"로 바뀌던 길이
    assert _clean_message(q, MSG_ASK, MAX_EXPLAIN) == q


def test_over_80_chars_keeps_the_leading_sentences_instead_of_being_replaced():
    long = "아이스 유자차는 이미 담으신 것 같아요. " + "옵션을 고르신 뒤 결제하기로 넘어갈까요, 아니면 다른 메뉴를 더 담으실래요? 정하시면 천천히 편하게 말씀해 주세요. 기다리고 있을게요."
    assert len(long) > MAX_EXPLAIN
    out = _clean_message(long, MSG_ASK, MAX_EXPLAIN)
    assert out.startswith("아이스 유자차는 이미 담으신 것 같아요.") and out != MSG_ASK and len(out) <= MAX_EXPLAIN


def test_question_up_to_80_chars_is_untouched():
    q = "옵션을 고르는 화면이에요. HOT, ICE 중에서 고를 수 있어요. 어떤 걸로 할까요?"
    assert len(q) <= MAX_EXPLAIN and _clean_message(q, MSG_ASK, MAX_EXPLAIN) == q


def test_default_only_when_even_the_first_sentence_is_too_long_or_empty():
    assert _clean_message("가" * 100, MSG_ASK, MAX_EXPLAIN) == MSG_ASK
    assert _clean_message("   ", MSG_ASK, MAX_EXPLAIN) == MSG_ASK
    assert _clean_message(None, MSG_ASK) == MSG_ASK


def test_other_messages_keep_the_60_char_limit_but_also_cut_by_sentence():
    text = "화면을 찾지 못했어요. " + "가" * 70
    assert _clean_message(text, "기본") == "화면을 찾지 못했어요."
