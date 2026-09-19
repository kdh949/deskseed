from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Literal

from .call_receipts import PromptCacheStatus
from .pricing import PricingCatalog
from .prompting import FeaturePrompt

PROMPT_CACHE_MIN_PREFIX_TOKENS = 1024
PROMPT_CACHE_KEY_VERSION = "ds-pc-v1"


@dataclass(frozen=True)
class PromptCachePlan:
    status: PromptCacheStatus
    prefix_tokens: int | None
    key_version: str | None
    cache_key: str | None
    messages: list[dict[str, Any]]
    transport_options: dict[str, Any]


def prepare_prompt_cache_request(
    *,
    mode: Literal["off", "test", "intent"],
    pricing: PricingCatalog,
    model: str,
    feature: str,
    prompt: FeaturePrompt,
    request: dict[str, Any],
) -> PromptCachePlan:
    if request.get("model") != model:
        raise ValueError("prompt cache model must match the provider request")
    messages = request.get("messages")
    if not isinstance(messages, list) or not messages:
        raise ValueError("prompt cache requires a non-empty message list")
    system_message = messages[0]
    if system_message != {"role": "system", "content": prompt.content}:
        raise ValueError("prompt cache prefix must be the repository-owned feature prompt")

    if mode == "off":
        return PromptCachePlan(
            status=PromptCacheStatus.OFF,
            prefix_tokens=None,
            key_version=None,
            cache_key=None,
            messages=messages,
            transport_options={},
        )

    prefix_tokens = pricing.count_text_tokens(model, prompt.content)
    if prefix_tokens < PROMPT_CACHE_MIN_PREFIX_TOKENS:
        return PromptCachePlan(
            status=PromptCacheStatus.INELIGIBLE,
            prefix_tokens=prefix_tokens,
            key_version=None,
            cache_key=None,
            messages=messages,
            transport_options={},
        )

    schema_digest = hashlib.sha256(
        json.dumps(
            request.get("response_format"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    key_payload = {
        "contractVersion": PROMPT_CACHE_KEY_VERSION,
        "feature": feature,
        "model": model,
        "prefixDigest": prompt.digest,
        "promptVersion": prompt.version,
        "reasoningEffort": request.get("reasoning_effort"),
        "responseSchemaDigest": schema_digest,
        "serviceTier": request.get("service_tier"),
    }
    key_digest = hashlib.sha256(
        json.dumps(key_payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    cache_key = f"{PROMPT_CACHE_KEY_VERSION}-{key_digest[:48]}"
    prepared_messages = [
        {
            "role": "system",
            "content": [
                {
                    "type": "text",
                    "text": prompt.content,
                    "prompt_cache_breakpoint": {"mode": "explicit"},
                }
            ],
        },
        *messages[1:],
    ]
    return PromptCachePlan(
        status=PromptCacheStatus.REQUESTED,
        prefix_tokens=prefix_tokens,
        key_version=PROMPT_CACHE_KEY_VERSION,
        cache_key=cache_key,
        messages=prepared_messages,
        transport_options={
            "prompt_cache_key": cache_key,
            "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
            "allowed_openai_params": ["prompt_cache_options"],
        },
    )
