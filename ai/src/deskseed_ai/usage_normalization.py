from __future__ import annotations

from collections.abc import Mapping

from .call_receipts import UsageStatus
from .pricing import Usage


def normalize_litellm_usage(
    usage_object: object | None,
    *,
    output_optional: bool = False,
) -> tuple[UsageStatus, Usage | None, str | None]:
    if usage_object is None:
        return UsageStatus.UNAVAILABLE, None, "USAGE_MISSING"
    input_names = (
        ("prompt_tokens", "input_tokens", "total_tokens")
        if output_optional
        else ("prompt_tokens", "input_tokens")
    )
    input_total, issue = _one_nonnegative_value(usage_object, input_names, required=True)
    if issue:
        return _missing_or_inconsistent(issue)
    output_total, issue = _one_nonnegative_value(
        usage_object,
        ("completion_tokens", "output_tokens"),
        required=not output_optional,
    )
    if issue:
        return _missing_or_inconsistent(issue)
    cache_read, issue = _usage_detail_value(
        usage_object,
        top_level_names=("cache_read_input_tokens",),
        nested_names=("cached_tokens",),
    )
    if issue:
        return UsageStatus.INCONSISTENT, None, issue
    cache_write, issue = _usage_detail_value(
        usage_object,
        top_level_names=("cache_creation_input_tokens",),
        nested_names=("cache_creation_tokens", "cache_write_tokens"),
    )
    if issue:
        return UsageStatus.INCONSISTENT, None, issue
    reasoning, issue = _usage_detail_value(
        usage_object,
        top_level_names=(),
        nested_names=("reasoning_tokens",),
        detail_containers=("completion_tokens_details", "output_tokens_details"),
    )
    if issue:
        return UsageStatus.INCONSISTENT, None, issue
    assert input_total is not None
    output_billed = output_total or 0
    if cache_read + cache_write > input_total:
        return UsageStatus.INCONSISTENT, None, "CACHE_EXCEEDS_INPUT"
    if reasoning > output_billed:
        return UsageStatus.INCONSISTENT, None, "REASONING_EXCEEDS_OUTPUT"
    return (
        UsageStatus.KNOWN,
        Usage(input_total - cache_read - cache_write, cache_read, cache_write, output_billed),
        None,
    )


def value(source: object, name: str) -> object | None:
    if isinstance(source, Mapping):
        return source.get(name)
    return getattr(source, name, None)


def bounded_text(candidate: object | None, maximum: int) -> str | None:
    if not isinstance(candidate, str):
        return None
    normalized = candidate.strip()
    if not normalized or len(normalized) > maximum:
        return None
    if any(not character.isprintable() for character in normalized):
        return None
    return normalized


def _missing_or_inconsistent(
    issue: str,
) -> tuple[UsageStatus, Usage | None, str | None]:
    status = UsageStatus.UNAVAILABLE if issue == "USAGE_TOTAL_MISSING" else UsageStatus.INCONSISTENT
    return status, None, issue


def _usage_detail_value(
    usage_object: object,
    *,
    top_level_names: tuple[str, ...],
    nested_names: tuple[str, ...],
    detail_containers: tuple[str, ...] = ("prompt_tokens_details", "input_tokens_details"),
) -> tuple[int, str | None]:
    candidates: list[int] = []
    for name in top_level_names:
        candidate = value(usage_object, name)
        if candidate is not None:
            if not isinstance(candidate, int) or isinstance(candidate, bool) or candidate < 0:
                return 0, "USAGE_VALUE_INVALID"
            candidates.append(candidate)
    for container_name in detail_containers:
        container = value(usage_object, container_name)
        if container is None:
            continue
        for name in nested_names:
            candidate = value(container, name)
            if candidate is not None:
                if not isinstance(candidate, int) or isinstance(candidate, bool) or candidate < 0:
                    return 0, "USAGE_VALUE_INVALID"
                candidates.append(candidate)
    if len(set(candidates)) > 1:
        return 0, "USAGE_DUPLICATE_MISMATCH"
    return (candidates[0] if candidates else 0), None


def _one_nonnegative_value(
    source: object,
    names: tuple[str, ...],
    *,
    required: bool,
) -> tuple[int | None, str | None]:
    values: list[int] = []
    for name in names:
        candidate = value(source, name)
        if candidate is None:
            continue
        if not isinstance(candidate, int) or isinstance(candidate, bool) or candidate < 0:
            return None, "USAGE_VALUE_INVALID"
        values.append(candidate)
    if not values:
        return (None, "USAGE_TOTAL_MISSING") if required else (0, None)
    if len(set(values)) > 1:
        return None, "USAGE_DUPLICATE_MISMATCH"
    return values[0], None
