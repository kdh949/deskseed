from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    cached_input_tokens: int
    output_tokens: int
    cache_write_tokens: int = 0


class PricingCatalog:
    def __init__(self, path: Path):
        payload = json.loads(path.read_text(encoding="utf-8"))
        self.version: str = payload["version"]
        self.models: dict[str, dict[str, int]] = payload["models"]

    def cost_microusd(self, model: str, usage: Usage) -> int:
        rates = self.models.get(model)
        if rates is None:
            raise ValueError(f"model has no reviewed price: {model}")
        uncached = max(0, usage.input_tokens - usage.cached_input_tokens - usage.cache_write_tokens)
        numerator = (
            uncached * rates["input"]
            + usage.cached_input_tokens * rates.get("cachedInput", rates["input"])
            + usage.cache_write_tokens * rates.get("cacheWrite", rates["input"])
            + usage.output_tokens * rates["output"]
        )
        return (numerator + 999_999) // 1_000_000

    def upper_bound_microusd(self, model: str, input_tokens: int, output_tokens: int = 0) -> int:
        if input_tokens < 0 or output_tokens < 0:
            raise ValueError("token bounds must be nonnegative")
        rates = self.models.get(model)
        if rates is None:
            raise ValueError(f"model has no reviewed price: {model}")
        input_rate = max(rates["input"], rates.get("cachedInput", 0), rates.get("cacheWrite", 0))
        numerator = input_tokens * input_rate + output_tokens * rates["output"]
        return max(1, (numerator + 999_999) // 1_000_000)
