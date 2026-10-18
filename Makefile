SHELL := /bin/sh

COMPOSE := docker compose
DATASET_VERSION_ID ?= 00000000-0000-4000-8000-000000000001
GIT_COMMIT_SHA ?= $(shell git rev-parse HEAD)
export GIT_COMMIT_SHA

.PHONY: build db-migrate train publish bootstrap up up-lan up-debug down smoke python-test test verify

build:
	$(COMPOSE) build

db-migrate:
	$(COMPOSE) up -d postgres
	$(COMPOSE) run --rm db-migrate

train: db-migrate
	$(COMPOSE) --profile training build training
	$(COMPOSE) --profile training run --rm training scripts/bootstrap.py \
		--dataset-version-id $(DATASET_VERSION_ID) $(if $(MODEL_VERSION),--model-version $(MODEL_VERSION),)

publish:
	@test -n "$(MODEL_VERSION)" || (echo "MODEL_VERSION is required" >&2; exit 2)
	$(COMPOSE) --profile training build training
	$(COMPOSE) --profile training run --rm training scripts/publish.py \
		--model-version "$(MODEL_VERSION)" --preflight-only
	@set -e; \
	$(COMPOSE) stop model-service; \
	if $(COMPOSE) --profile training run --rm training scripts/publish.py \
		--model-version "$(MODEL_VERSION)" --promote; then \
		$(COMPOSE) up -d model-service; \
	else \
		$(COMPOSE) up -d model-service; \
		exit 1; \
	fi

bootstrap: db-migrate
	@active="$$( $(COMPOSE) exec -T postgres psql -U "$${POSTGRES_ADMIN_USER:-housing_admin}" \
		-d "$${POSTGRES_DB:-housing}" -Atc "select count(*) from ml_registry.model_versions where lifecycle_status = 'ACTIVE'" )"; \
	if [ "$$active" = "0" ]; then \
		$(COMPOSE) --profile training build training; \
		$(COMPOSE) --profile training run --rm training scripts/bootstrap.py \
			--dataset-version-id $(DATASET_VERSION_ID) $(if $(MODEL_VERSION),--model-version $(MODEL_VERSION),); \
	else \
		echo "An active model already exists; bootstrap is reusing it."; \
	fi
	$(COMPOSE) up -d --build model-service estimator-service market-service web-portal
	$(MAKE) smoke

up:
	$(COMPOSE) up -d --build

up-lan:
	@test -f .env.lan || (echo "Copy .env.lan.example to .env.lan and review it first." >&2; exit 2)
	docker compose --env-file .env.lan -f compose.yaml -f compose.lan.yaml up -d --build

up-debug:
	$(COMPOSE) -f compose.yaml -f compose.api-debug.yaml up -d --build

down:
	$(COMPOSE) down

smoke:
	PORTAL_PUBLIC_ORIGIN="$${PORTAL_PUBLIC_ORIGIN:-http://127.0.0.1:$${WEB_PORT:-3000}}" \
	MODEL_PUBLIC_ORIGIN="http://127.0.0.1:$${MODEL_PORT:-8000}" ./scripts/smoke.sh

python-test:
	@set -e; for service in training model-service estimator-service; do \
		echo "Running Python quality gates for $$service"; \
		docker run --rm \
			-v hsbc-test-pip-cache:/root/.cache/pip \
			-v "$(CURDIR)/$$service:/workspace" -w /workspace \
			python:3.12.10-slim-bookworm sh -c \
			'python -m pip install -q -e ".[dev]" && ruff check . && mypy src && pytest'; \
	done

test: python-test
	cd web-portal && pnpm lint && pnpm typecheck && pnpm build
	docker run --rm -v "$(CURDIR)/market-service:/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -B test

verify: test
	$(COMPOSE) config --quiet
	$(COMPOSE) -f compose.yaml -f compose.api-debug.yaml config --quiet
	$(COMPOSE) -f compose.yaml -f compose.lan.yaml config --quiet
	$(COMPOSE) build
