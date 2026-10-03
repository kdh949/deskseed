# AI V1 evaluation boundary

The committed evaluation is a deterministic synthetic contract smoke, not a model-quality claim. Immutable `ai/evals/cases-v1.jsonl` v1 contains 100 functional cases (25 summary, 25 triage, 50 search/reply) and 30 security/failure cases without production ticket, customer, or knowledge data. Functional cases are fixed at 70 tune/30 holdout; security/failure cases are fixed at 20 tune/10 holdout. `ai/scripts/evaluate_fake.py` refuses a changed count, feature mix, or split and verifies strict input/output rejection, fixed triage taxonomy, prompt-injection strings as untrusted PUBLIC data, and the rule that reply without approved PUBLIC knowledge is `NEEDS_REVIEW` with no usable answer.

Run:

```bash
cd ai
.venv/bin/python scripts/evaluate_fake.py
```

Passing all 130 deterministic cases proves only the fake-provider and schema/security contract. The evaluator does not tune a model, make network calls, or compute production quality metrics.

## Release evidence still required

- Human-reviewed Korean summary faithfulness and omission rate.
- Triage topic/priority precision and recall on an approved de-identified corpus; allowed tag membership from Backend truth.
- PUBLIC KB retrieval Recall@10, ANN-versus-exact recall, citation membership, source withdrawal, and answer groundedness.
- Live provider refusal/invalid-schema/timeout/cost behavior and production-sized latency/load.

Fake embeddings and fake provider outputs must never be reported as retrieval or answer quality. Until an approved corpus, reviewer rubric, acceptance thresholds, and data-handling review exist, these metrics are `NOT ESTABLISHED`.
