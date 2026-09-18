from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import cast

import tiktoken


@dataclass(frozen=True)
class Usage:
    input_uncached_tokens: int
    input_cache_read_tokens: int
    input_cache_write_tokens: int
    output_billed_tokens: int

    def __post_init__(self) -> None:
        if min(
            self.input_uncached_tokens,
            self.input_cache_read_tokens,
            self.input_cache_write_tokens,
            self.output_billed_tokens,
        ) < 0:
            raise ValueError("usage buckets must be nonnegative")

    @property
    def input_total_tokens(self) -> int:
        return self.input_uncached_tokens + self.input_cache_read_tokens + self.input_cache_write_tokens


class PricingCatalog:
    def __init__(self, path: Path):
        payload = json.loads(path.read_text(encoding="utf-8"))
        self.version: str = payload["version"]
        self.service_tier: str = payload.get("serviceTier", "standard")
        self.context_price_band: str = payload.get("contextPriceBand", "short")
        self.models: dict[str, dict[str, object]] = payload["models"]

    def cost_microusd(
        self,
        requested_alias: str,
        actual_model: str,
        usage: Usage,
        service_tier: str = "standard",
        context_price_band: str = "short",
    ) -> int:
        rates = self._rates(requested_alias, actual_model, service_tier, context_price_band)
        maximum = _positive_int(rates, "maxInputTokensForBand")
        if usage.input_total_tokens > maximum:
            raise ValueError("actual usage exceeds the reviewed context price band")
        numerator = (
            usage.input_uncached_tokens * _rate(rates, "input")
            + usage.input_cache_read_tokens * _rate(rates, "cachedInput", "input")
            + usage.input_cache_write_tokens * _rate(rates, "cacheWrite", "input")
            + usage.output_billed_tokens * _rate(rates, "output")
        )
        return (numerator + 999_999) // 1_000_000

    def upper_bound_microusd(
        self,
        requested_alias: str,
        input_tokens: int,
        output_tokens: int = 0,
        service_tier: str = "standard",
        context_price_band: str = "short",
    ) -> int:
        if input_tokens < 0 or output_tokens < 0:
            raise ValueError("token bounds must be nonnegative")
        rates = self._rates(
            requested_alias,
            requested_alias,
            service_tier,
            context_price_band,
        )
        maximum = rates.get("maxInputTokensForBand")
        if not isinstance(maximum, int) or isinstance(maximum, bool) or maximum <= 0:
            raise ValueError(f"model has no reviewed context bound: {requested_alias}")
        if input_tokens > maximum:
            raise ValueError("input exceeds the reviewed context price band")
        input_rate = max(
            _rate(rates, "input"),
            _rate(rates, "cachedInput", "input"),
            _rate(rates, "cacheWrite", "input"),
        )
        numerator = input_tokens * input_rate + output_tokens * _rate(rates, "output")
        return max(1, (numerator + 999_999) // 1_000_000)

    def count_text_tokens(self, requested_alias: str, value: str) -> int:
        rates = self.models.get(requested_alias)
        if rates is None:
            raise ValueError(f"model has no reviewed price: {requested_alias}")
        tokenizer = rates.get("tokenizer")
        if not isinstance(tokenizer, str) or not tokenizer:
            raise ValueError(f"model has no reviewed tokenizer: {requested_alias}")
        return len(tiktoken.get_encoding(tokenizer).encode(value))

    def count_json_tokens(self, requested_alias: str, value: object) -> int:
        serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return self.count_text_tokens(requested_alias, serialized)

    def _rates(
        self,
        requested_alias: str,
        actual_model: str,
        service_tier: str,
        context_price_band: str,
    ) -> dict[str, object]:
        rates = self.models.get(requested_alias)
        if rates is None:
            raise ValueError(f"model has no reviewed price: {requested_alias}")
        if service_tier != self.service_tier or context_price_band != self.context_price_band:
            raise ValueError("provider price combination is not reviewed")
        for catalog_alias, candidate in self.models.items():
            actual_models_value = candidate.get("actualModels", [catalog_alias])
            if not isinstance(actual_models_value, list) or not all(
                isinstance(item, str) and item for item in actual_models_value
            ):
                raise ValueError(f"model has invalid actual model mapping: {catalog_alias}")
            actual_models = cast(list[str], actual_models_value)
            if actual_model in actual_models:
                return candidate
        raise ValueError(
            f"actual model {actual_model} is not reviewed for requested alias {requested_alias}"
        )


def _rate(rates: dict[str, object], name: str, fallback: str | None = None) -> int:
    candidate = rates.get(name)
    if candidate is None and fallback is not None:
        candidate = rates.get(fallback)
    if not isinstance(candidate, int) or isinstance(candidate, bool) or candidate < 0:
        raise ValueError(f"pricing rate is invalid: {name}")
    return candidate


def _positive_int(values: dict[str, object], name: str) -> int:
    candidate = values.get(name)
    if not isinstance(candidate, int) or isinstance(candidate, bool) or candidate <= 0:
        raise ValueError(f"pricing value is invalid: {name}")
    return candidate
