from __future__ import annotations

import re
import unicodedata
from collections import Counter

_STRUCTURED_VALUE = re.compile(
    r"(?:https?://[^\s]+|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|"
    r"(?:₩|\$|€|¥)?\d[\d,]*(?:\.\d+)?(?:\s?(?:원|달러|유로|엔|%|퍼센트|일|주|개월|년|시|분))?|"
    r"\d{4}[-./년]\d{1,2}(?:[-./월]\d{1,2}일?)?)",
    re.IGNORECASE,
)
_NAME_LIKE = re.compile(r"(?:`[^`]{1,80}`|['\"]{1}[^'\"]{1,80}['\"]{1}|\b[A-Z][A-Za-z0-9_-]{1,39}\b)")
_POLICY_MARKERS = (
    "정책", "약관", "환불", "취소", "보장", "제한", "수수료", "policy", "terms", "refund",
)
_CONDITION_MARKERS = (
    "경우", "조건", "한해", "이내", "이후", "이전", "까지", "해야", "단,", "예외", "if", "unless", "only",
)
_NEGATION_MARKERS = (
    "않", "없", "불가", "못", "금지", "제외", "아니", "no ", "not ", "never", "cannot",
)


def preservation_markers(value: str) -> tuple[tuple[str, str], ...]:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    markers: list[tuple[str, str]] = []
    markers.extend(("STRUCTURED", item.group(0).casefold()) for item in _STRUCTURED_VALUE.finditer(normalized))
    markers.extend(("NAME", item.group(0).casefold()) for item in _NAME_LIKE.finditer(value))
    for category, values in (
        ("POLICY", _POLICY_MARKERS),
        ("CONDITION", _CONDITION_MARKERS),
        ("NEGATION", _NEGATION_MARKERS),
    ):
        for marker in values:
            count = normalized.count(marker.casefold())
            markers.extend((category, marker.casefold()) for _ in range(count))
    return tuple(markers)


def preserves_deterministic_markers(original: str, candidate: str) -> bool:
    return Counter(preservation_markers(original)) == Counter(preservation_markers(candidate))
