from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DESKSEED_AI_", extra="ignore")

    environment: Literal["local", "test", "production"] = "local"
    process_role: Literal[
        "all", "api", "migration", "dispatcher", "worker", "recovery", "indexer", "feedback", "retention"
    ] = "all"
    database_url: SecretStr = SecretStr("postgresql://deskseed_ai:deskseed-ai-local-only@ai-db:5432/deskseed_ai")
    redis_url: SecretStr = SecretStr("redis://ai-redis:6379/0")
    backend_base_url: str = "http://backend:8080"

    inbound_auth_enabled: bool = False
    inbound_key_id: str = ""
    inbound_secret_sha256: SecretStr = SecretStr("")
    backend_source_key_id: str = ""
    backend_source_secret: SecretStr = SecretStr("")
    backend_index_key_id: str = ""
    backend_index_secret: SecretStr = SecretStr("")

    result_encryption_key: SecretStr = SecretStr("")
    provider_mode: Literal["fake", "litellm"] = "fake"
    live_provider_enabled: bool = False
    openai_api_key: SecretStr = SecretStr("")
    model_fast: str = "openai/gpt-5.6-luna"
    model_standard: str = "openai/gpt-5.6-terra"
    embedding_model: str = "openai/text-embedding-3-small"
    prompt_version: str = "ai-v1.2-p1"
    graph_version: str = "reply-v1"
    config_version: str = "2026-09-16"

    workspace_daily_budget_microusd: int = 3_000_000
    actor_daily_budget_microusd: int = 500_000
    job_budget_microusd: int = 200_000
    job_timeout_seconds: int = Field(90, ge=5, le=600)
    lease_seconds: int = Field(45, ge=5, le=300)
    max_attempts: int = Field(3, ge=1, le=10)
    reconciliation_page_size: int = Field(200, ge=1, le=1000)
    reconciliation_interval_seconds: int = Field(86_400, ge=300, le=604_800)
    reconciliation_retry_seconds: int = Field(300, ge=30, le=86_400)
    migrate_on_start: bool = True
    run_background_workers: bool = True
    stream_name: str = "deskseed:ai:jobs:v1"
    stream_group: str = "deskseed-ai-workers-v1"
    consumer_name: str = "worker-1"

    langfuse_enabled: bool = False
    langfuse_public_key: str = ""
    langfuse_secret_key: SecretStr = SecretStr("")
    langfuse_host: str = "https://cloud.langfuse.com"

    @model_validator(mode="after")
    def validate_fail_closed_boundaries(self) -> "Settings":
        requires_inbound_auth = self.process_role in {"all", "api"}
        requires_source_credential = self.process_role in {"all", "worker"}
        requires_index_credential = self.process_role in {"all", "indexer"}
        uses_provider = self.process_role in {"all", "worker", "indexer"}
        if self.environment == "production" and requires_inbound_auth and not self.inbound_auth_enabled:
            raise ValueError("production requires inbound machine authentication")
        if self.environment == "production" and requires_source_credential and (
            not self.backend_source_key_id or not self.backend_source_secret.get_secret_value()
        ):
            raise ValueError("production worker requires its backend source credential")
        if self.environment == "production" and requires_index_credential and (
            not self.backend_index_key_id or not self.backend_index_secret.get_secret_value()
        ):
            raise ValueError("production indexer requires its backend index credential")
        if (
            self.backend_source_key_id
            and self.backend_index_key_id
            and self.backend_source_key_id == self.backend_index_key_id
        ):
            raise ValueError("backend source and index key IDs must be distinct")
        if self.inbound_auth_enabled:
            if not self.inbound_key_id or len(self.inbound_key_id) > 80:
                raise ValueError("inbound key ID is invalid")
            digest = self.inbound_secret_sha256.get_secret_value()
            if len(digest) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in digest):
                raise ValueError("inbound secret SHA-256 is invalid")
        if uses_provider and self.provider_mode == "litellm" and (
            not self.live_provider_enabled or not self.openai_api_key.get_secret_value()
        ):
            raise ValueError("LiteLLM mode requires explicit live-provider opt-in and a provider key")
        if self.langfuse_enabled and (
            not self.langfuse_public_key or not self.langfuse_secret_key.get_secret_value()
        ):
            raise ValueError("Langfuse export requires both keys")
        if self.workspace_daily_budget_microusd <= 0 or self.actor_daily_budget_microusd <= 0:
            raise ValueError("daily budgets must be positive")
        if self.job_budget_microusd <= 0:
            raise ValueError("job budget must be positive")
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
