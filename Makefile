.PHONY: up down db bundle-core-openapi api-contract-check docs-check compose-smoke demo backend-test frontend-check check

up:
	docker compose up --build

down:
	docker compose down

db:
	docker compose up -d db

bundle-core-openapi:
	python3 scripts/bundle_core_openapi.py --check
	python3 scripts/test_core_openapi_bundle.py

api-contract-check: bundle-core-openapi
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_api_contracts.py
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_api_contracts.py

docs-check: api-contract-check

compose-smoke:
	bash scripts/test-e2e-compose-ownership.sh
	bash scripts/compose-smoke.sh

demo:
	./scripts/demo-request.sh

backend-test:
	cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon test

frontend-check:
	cd frontend && npm ci --no-audit --no-fund && npm run format:check && npm run lint && npm run typecheck && npm test -- --run && npm run build

check: api-contract-check backend-test frontend-check
