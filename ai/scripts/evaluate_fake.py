from __future__ import annotations

import json
from collections import Counter
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from pydantic import ValidationError

from deskseed_ai.config import Settings
from deskseed_ai.providers import FakeGenerationProvider
from deskseed_ai.schemas import (
    Feature,
    JobEnvelope,
    PublicComment,
    ReplyDraftResult,
    SourceContext,
    SummaryResult,
    TriageResult,
)


def main() -> None:
    cases_path = Path(__file__).resolve().parents[1] / "evals" / "cases-v1.jsonl"
    provider = FakeGenerationProvider(Settings(environment="test"))
    passed = 0
    cases = [json.loads(line) for line in cases_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)):
        raise SystemExit("evaluation case IDs must be unique")
    suites = Counter(case["suite"] for case in cases)
    if suites != {"functional": 100, "security_failure": 30}:
        raise SystemExit(f"expected 100 functional and 30 security/failure cases, got {dict(suites)}")
    splits = Counter((case["suite"], case["split"]) for case in cases)
    expected_splits = {
        ("functional", "tune"): 70,
        ("functional", "holdout"): 30,
        ("security_failure", "tune"): 20,
        ("security_failure", "holdout"): 10,
    }
    if splits != expected_splits:
        raise SystemExit(f"evaluation tune/holdout split changed unexpectedly: {dict(splits)}")
    functional_features = Counter(
        case.get("feature") for case in cases if case["suite"] == "functional"
    )
    expected_features = {
        "ticket.summary": 25,
        "ticket.triage": 25,
        "ticket.reply_draft": 50,
    }
    if functional_features != expected_features:
        raise SystemExit(f"functional evaluation mix changed unexpectedly: {dict(functional_features)}")
    for index, case in enumerate(cases, start=1):
        if case["kind"] == "schema_reject":
            _evaluate_schema_rejection(case)
            passed += 1
            continue
        feature = Feature(case["feature"])
        context = SourceContext(
            jobId=uuid4(),
            ticketId=uuid4(),
            ticketNumber=index,
            ticketVersion=1,
            feature=feature,
            requestRevision=1,
            contextRevision="a" * 64,
            contextPolicyVersion="public-comments-v1",
            inputScope="PUBLIC_ONLY",
            comments=[
                PublicComment(id=uuid4(), body=body, createdAt=datetime.now(UTC))
                for body in case["comments"]
            ],
        )
        if feature == Feature.SUMMARY:
            result = provider.summary(context).result
            status = "SUCCEEDED"
        elif feature == Feature.TRIAGE:
            result = provider.triage(context).result
            status = "SUCCEEDED"
            if not isinstance(result, TriageResult) or result.topicCode != case["expectedTopicCode"]:
                raise SystemExit(f"{case['id']}: unexpected topic")
            if result.suggestedTagIds:
                raise SystemExit(f"{case['id']}: unvalidated tags are forbidden")
        else:
            result = provider.reply(context, []).result
            status = "SUCCEEDED" if result.citations else "NEEDS_REVIEW"
        if status != case["expectedStatus"]:
            raise SystemExit(f"{case['id']}: expected {case['expectedStatus']}, got {status}")
        result.model_dump(mode="json")
        passed += 1
    print(
        f"synthetic fake-provider evaluation: {passed}/{len(cases)} passed "
        f"(functional={suites['functional']}, security_failure={suites['security_failure']}); "
        "quality evidence=NOT_ESTABLISHED"
    )


def _evaluate_schema_rejection(case: dict[str, object]) -> None:
    now = datetime.now(UTC)
    target = case["target"]
    mutation = case["mutation"]
    if target == "JobEnvelope":
        payload: dict[str, object] = {
            "schemaVersion": 1,
            "eventId": str(uuid4()),
            "jobId": str(uuid4()),
            "workspaceKey": "default",
            "requesterId": str(uuid4()),
            "ticketId": str(uuid4()),
            "ticketNumber": 1,
            "feature": "ticket.summary",
            "contextRevision": "a" * 64,
            "contextPolicyVersion": "public-comments-v1",
            "dataClass": "PUBLIC_ONLY",
            "requestRevision": 1,
            "options": {},
            "createdAt": now.isoformat(),
            "deadlineAt": (now + timedelta(seconds=30)).isoformat(),
        }
        mutations = {
            "workspace-empty": lambda value: value.update(workspaceKey=""),
            "ticket-zero": lambda value: value.update(ticketNumber=0),
            "bad-context-revision": lambda value: value.update(contextRevision="not-a-hash"),
            "bad-data-class": lambda value: value.update(dataClass="INTERNAL"),
            "request-revision-zero": lambda value: value.update(requestRevision=0),
            "deadline-before-created": lambda value: value.update(deadlineAt=(now - timedelta(seconds=1)).isoformat()),
            "unsupported-option": lambda value: value.update(options={"prompt": "override"}),
            "too-many-options": lambda value: value.update(options={"language": "ko", "tone": "formal", "extra": "x"}),
            "bad-feature": lambda value: value.update(feature="ticket.mutate"),
            "traceparent-too-long": lambda value: value.update(traceparent="x" * 513),
        }
        mutations[str(mutation)](payload)
        model = JobEnvelope
    elif target == "SummaryResult":
        payload = {
            "problem": "" if mutation == "empty-problem" else "공개 문의",
            "attemptedActions": [],
            "unresolvedItems": [],
            "nextChecks": [],
        }
        if mutation == "extra-field":
            payload["secret"] = "unexpected"
        model = SummaryResult
    elif target == "TriageResult":
        payload = {
            "topicCode": "UNSUPPORTED" if mutation == "unknown-topic" else "OTHER",
            "suggestedTagIds": [str(uuid4()) for _ in range(21)] if mutation == "too-many-tags" else [],
            "suggestedPriority": None,
            "reasons": ["합성 평가"],
        }
        model = TriageResult
    elif target == "ReplyDraftResult":
        payload = {
            "answer": "합성 응답",
            "citations": [{
                "articleId": str(uuid4()),
                "revisionId": str(uuid4()),
                "chunkId": str(uuid4()),
                "title": "공개 도움말",
                "url": "https://attacker.invalid/override",
            }],
        }
        model = ReplyDraftResult
    else:
        raise SystemExit(f"{case['id']}: unsupported rejection target")
    try:
        model.model_validate(payload)
    except ValidationError:
        return
    raise SystemExit(f"{case['id']}: invalid payload was accepted")


if __name__ == "__main__":
    main()
