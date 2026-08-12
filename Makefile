.PHONY: up down db seed-verify docs-check compose-smoke demo backend-test frontend-check check

up:
	docker compose up --build

down:
	docker compose down

db:
	docker compose up -d db

seed-verify:
	python3 scripts/verify_seed.py

docs-check:
	python3 scripts/validate_documentation.py --write
	git diff --exit-code -- VALIDATION-REPORT.md FILE-MANIFEST.txt

compose-smoke:
	bash scripts/test-e2e-compose-ownership.sh
	bash scripts/compose-smoke.sh

demo:
	./scripts/demo-request.sh

backend-test:
	cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew --no-daemon test

frontend-check:
	cd frontend && npm ci --no-audit --no-fund && npm run format:check && npm run lint && npm run typecheck && npm test -- --run && npm run build

check: docs-check backend-test frontend-check
