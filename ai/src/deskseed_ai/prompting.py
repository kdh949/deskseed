from __future__ import annotations

import hashlib
from dataclasses import dataclass
from importlib.resources import files

from .schemas import Feature


@dataclass(frozen=True)
class FeaturePrompt:
    name: str
    content: str
    digest: str

    @property
    def version(self) -> str:
        return f"{self.name}:{self.digest[:12]}"


_PROMPT_NAMES = {
    Feature.SUMMARY: "summary-v2",
    Feature.TRIAGE: "triage-v2",
    Feature.REPLY_DRAFT: "reply-v2",
    Feature.REPLY_REWRITE: "rewrite-v1",
}

_CONTEXT_MEMORY_PROMPT_NAME = "context-memory-v1"
_REWRITE_VALIDATION_PROMPT_NAME = "rewrite-validation-v1"


def prompt_for(feature: Feature) -> FeaturePrompt:
    name = _PROMPT_NAMES[feature]
    content = files("deskseed_ai.prompts").joinpath(f"{name}.txt").read_text(encoding="utf-8").strip()
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    return FeaturePrompt(name=name, content=content, digest=digest)


def context_memory_prompt() -> FeaturePrompt:
    content = (
        files("deskseed_ai.prompts")
        .joinpath(f"{_CONTEXT_MEMORY_PROMPT_NAME}.txt")
        .read_text(encoding="utf-8")
        .strip()
    )
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    return FeaturePrompt(name=_CONTEXT_MEMORY_PROMPT_NAME, content=content, digest=digest)


def rewrite_validation_prompt() -> FeaturePrompt:
    content = (
        files("deskseed_ai.prompts")
        .joinpath(f"{_REWRITE_VALIDATION_PROMPT_NAME}.txt")
        .read_text(encoding="utf-8")
        .strip()
    )
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    return FeaturePrompt(name=_REWRITE_VALIDATION_PROMPT_NAME, content=content, digest=digest)
