from __future__ import annotations

import asyncio
import logging
import threading
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.responses import JSONResponse

from .backend_client import BackendClient
from .config import Settings, get_settings
from .db import Database, apply_migrations
from .embedding_batch import (
    EmbeddingBatchService,
    FakeEmbeddingBatchAdapter,
    OpenAIEmbeddingBatchAdapter,
)
from .feedback import FeedbackExporter
from .indexing import IndexingService
from .observability import TraceAdapter
from .providers import provider_for
from .queue import StreamRuntime
from .repository import ConflictError, NotFoundError, Repository
from .retrieval import FakeEmbeddingProvider, KnowledgeRepository, LiteLlmEmbeddingProvider
from .schemas import (
    Accepted,
    CancellationEnvelope,
    FeedbackRequest,
    IndexEvent,
    JobEnvelope,
    JobReceipt,
    OperationRequest,
    ServiceStatus,
)
from .security import EnvelopeCipher, authenticate_machine

LOGGER = logging.getLogger(__name__)
ROOT = Path(__file__).resolve().parents[2]


class Runtime:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.database = Database(settings)
        self.cipher = EnvelopeCipher(settings)
        self.repository = Repository(self.database, settings, self.cipher)
        embeddings = (
            LiteLlmEmbeddingProvider(
                settings.embedding_model,
                settings.openai_api_key.get_secret_value(),
                settings.job_timeout_seconds,
            )
            if settings.provider_mode == "litellm"
            else FakeEmbeddingProvider(settings.embedding_model)
        )
        self.knowledge = KnowledgeRepository(self.database, embeddings)
        self.traces = TraceAdapter(settings, counter_sink=self.repository.increment_telemetry_counter)
        self.feedback = FeedbackExporter(self.repository, self.traces)
        self.backend = BackendClient(settings)
        pricing_path = ROOT / "config" / "pricing-v2.json"
        batch_pricing_path = ROOT / "config" / "pricing-batch-v1.json"
        batch_adapter = (
            OpenAIEmbeddingBatchAdapter(
                settings.openai_api_key.get_secret_value(), settings.job_timeout_seconds
            )
            if settings.provider_mode == "litellm"
            else FakeEmbeddingBatchAdapter()
        )
        self.embedding_batches = EmbeddingBatchService(
            self.backend,
            self.knowledge,
            self.repository,
            settings,
            batch_pricing_path,
            batch_adapter,
        )
        self.indexing = IndexingService(
            self.backend,
            self.knowledge,
            self.repository,
            settings,
            pricing_path,
            self.traces,
            self.embedding_batches,
        )
        self.stream = StreamRuntime(
            settings,
            self.repository,
            self.backend,
            provider_for(settings, pricing_path),
            self.knowledge,
            self.traces,
            pricing_path,
        )
        self.stop_event = threading.Event()
        self.tasks: list[asyncio.Task[None]] = []

    def open(self) -> None:
        self.database.open()
        if self.settings.migrate_on_start:
            apply_migrations(self.database, ROOT / "migrations")
        self.stream.ensure_group()

    async def start_workers(self) -> None:
        if not self.settings.run_background_workers:
            return
        self.tasks = [
            asyncio.create_task(self._loop(self.stream.dispatch_once, 0.2), name="ai-dispatcher"),
            asyncio.create_task(self._loop(self.stream.consume_once, 0.05), name="ai-worker"),
            asyncio.create_task(self._loop(self.stream.recover_once, 5.0), name="ai-recovery"),
            asyncio.create_task(self._loop(self.feedback.export_once, 5.0), name="ai-feedback-export"),
            asyncio.create_task(self._loop(self.indexing.cycle_once, 0.5), name="ai-indexer"),
        ]

    async def _loop(self, operation, interval: float) -> None:
        while not self.stop_event.is_set():
            try:
                await asyncio.to_thread(operation)
            except Exception:
                LOGGER.exception("AI background operation failed", extra={"operation": operation.__name__})
            await asyncio.sleep(interval)

    async def close(self) -> None:
        self.stop_event.set()
        if self.tasks:
            await asyncio.gather(*self.tasks, return_exceptions=True)
        self.traces.flush()
        self.database.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    runtime = Runtime(get_settings())
    runtime.open()
    app.state.runtime = runtime
    await runtime.start_workers()
    try:
        yield
    finally:
        await runtime.close()


app = FastAPI(
    title="Deskseed AI Internal API",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
    openapi_url="/internal/v1/openapi.json",
    lifespan=lifespan,
)


def runtime(request: Request) -> Runtime:
    return request.app.state.runtime


def machine_auth(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
    key_id: Annotated[str | None, Header(alias="X-Deskseed-AI-Key-Id")] = None,
) -> None:
    settings = runtime(request).settings
    if not settings.inbound_auth_enabled and settings.environment in {"local", "test"}:
        return
    secret = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
    if not authenticate_machine(
        key_id or "",
        secret,
        settings.inbound_key_id,
        settings.inbound_secret_sha256.get_secret_value(),
    ):
        raise HTTPException(status_code=401, detail="direction-specific machine authentication failed")


Protected = Annotated[None, Depends(machine_auth)]


@app.exception_handler(ConflictError)
async def conflict_handler(_: Request, exception: ConflictError) -> JSONResponse:
    return problem(409, "/problems/ai-event-conflict", "AI event conflict", str(exception))


@app.exception_handler(NotFoundError)
async def not_found_handler(_: Request, exception: NotFoundError) -> JSONResponse:
    return problem(404, "/problems/ai-job-not-found", "AI job not found", str(exception))


@app.post("/internal/v1/jobs", response_model=Accepted, status_code=status.HTTP_202_ACCEPTED)
def accept_job(envelope: JobEnvelope, _: Protected, service: Annotated[Runtime, Depends(runtime)]) -> Accepted:
    return service.repository.accept_job(envelope)


@app.get("/internal/v1/jobs/{job_id}", response_model=JobReceipt)
def get_job(
    job_id: UUID,
    _: Protected,
    service: Annotated[Runtime, Depends(runtime)],
    include_result: Annotated[bool, Query(alias="includeResult")] = False,
) -> JobReceipt:
    return service.repository.get_job(job_id, include_result=include_result)


@app.post("/internal/v1/jobs/{job_id}/cancel", response_model=Accepted, status_code=status.HTTP_202_ACCEPTED)
def cancel_job(
    job_id: UUID,
    envelope: CancellationEnvelope,
    _: Protected,
    service: Annotated[Runtime, Depends(runtime)],
) -> Accepted:
    if job_id != envelope.jobId:
        raise ConflictError("path and envelope job IDs differ")
    return service.repository.cancel(envelope)


@app.post("/internal/v1/feedback", response_model=Accepted, status_code=status.HTTP_202_ACCEPTED)
def feedback(
    payload: FeedbackRequest,
    _: Protected,
    service: Annotated[Runtime, Depends(runtime)],
) -> Accepted:
    return service.repository.accept_feedback(payload)


@app.post("/internal/v1/index-events", response_model=Accepted, status_code=status.HTTP_202_ACCEPTED)
def index_event(event: IndexEvent, _: Protected, service: Annotated[Runtime, Depends(runtime)]) -> Accepted:
    return service.repository.accept_index_event(event)


@app.post("/internal/v1/operations", response_model=Accepted, status_code=status.HTTP_202_ACCEPTED)
def operation(
    payload: OperationRequest,
    _: Protected,
    service: Annotated[Runtime, Depends(runtime)],
    job_id: Annotated[UUID | None, Query(alias="jobId")] = None,
) -> Accepted:
    if payload.action in {"CANCEL", "RETRY"} and job_id is None:
        raise ConflictError("jobId is required for this operation")
    if payload.action == "RETENTION":
        service.repository.purge_expired_cache_entries()
        service.repository.purge_expired_results()
        service.repository.purge_expired_shared_executions()
        service.repository.purge_expired_embedding_batches()
        service.repository.purge_expired_metadata()
        accepted = service.repository.operate(job_id, payload)
        return accepted
    return service.repository.operate(job_id, payload)


@app.get("/internal/v1/status", response_model=ServiceStatus)
def service_status(_: Protected, service: Annotated[Runtime, Depends(runtime)]) -> ServiceStatus:
    postgres = service.database.ping()
    redis = service.stream.ping()
    return ServiceStatus.model_validate(
        {
            "ready": postgres and redis,
            "dataAsOf": datetime.now(UTC),
            "dependencies": {"postgres": postgres, "redis": redis},
            "providerMode": service.settings.provider_mode,
            "liveProviderEnabled": service.settings.live_provider_enabled,
            "langfuseEnabled": service.settings.langfuse_enabled,
            "telemetry": service.repository.telemetry_status(service.traces.enabled),
            "jobCounts": service.repository.status_counts(),
            "sharedExecutionCounts": service.repository.shared_execution_status_counts(),
        }
        | service.repository.operational_status()
    )


@app.get("/internal/v1/livez")
def livez() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/internal/v1/readyz")
def readyz(request: Request) -> JSONResponse:
    service = runtime(request)
    checks = {"postgres": service.database.ping(), "redis": service.stream.ping()}
    ready = all(checks.values())
    return JSONResponse(status_code=200 if ready else 503, content={"status": "UP" if ready else "DOWN", "checks": checks})


def problem(status_code: int, type_: str, title: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        media_type="application/problem+json",
        headers={"Cache-Control": "no-store"},
        content={"type": type_, "title": title, "status": status_code, "detail": detail},
    )
