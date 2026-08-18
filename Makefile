.PHONY: up down db seed-verify bundle-core-openapi goal-wave-ownership-check docs-check compose-smoke demo backend-test frontend-check check

up:
	docker compose up --build

down:
	docker compose down

db:
	docker compose up -d db

seed-verify:
	python3 scripts/verify_seed.py

bundle-core-openapi:
	python3 scripts/bundle_core_openapi.py --check
	python3 scripts/test_core_openapi_bundle.py

goal-wave-ownership-check:
	python3 scripts/validate_goal_wave_ownership.py

docs-check: bundle-core-openapi goal-wave-ownership-check
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_documentation_quality.py
	python3 scripts/validate_documentation.py --write
	git diff --exit-code -- api/core-api-outline-v1.yaml api/customer-identity-api-v1.yaml api/platform-api-outline-v1.yaml VALIDATION-REPORT.md FILE-MANIFEST.txt

compose-smoke:
	bash scripts/test-e2e-compose-ownership.sh
	bash scripts/compose-smoke.sh

demo:
	./scripts/demo-request.sh

backend-test:
	cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon test

frontend-check:
	cd frontend && npm ci --no-audit --no-fund && npm run format:check && npm run lint && npm run typecheck && npm test -- --run && npm run build

check: seed-verify docs-check backend-test frontend-check
