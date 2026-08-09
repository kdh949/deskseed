.PHONY: up down db seed-verify demo backend-test frontend-check check

up:
	docker compose up --build

down:
	docker compose down

db:
	docker compose up -d db

seed-verify:
	python3 scripts/verify_seed.py

demo:
	./scripts/demo-request.sh

backend-test:
	cd backend && ./gradlew test

frontend-check:
	cd frontend && npm install --no-audit --no-fund && npm run typecheck && npm run build

check: seed-verify backend-test frontend-check
