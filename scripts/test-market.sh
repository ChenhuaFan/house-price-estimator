#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
project_root=$(CDPATH= cd -- "${script_dir}/.." && pwd)
run_id="$$"
database_container="hsbc-market-test-db-${run_id}"
test_network="hsbc-market-test-net-${run_id}"

cleanup() {
  case "${database_container}:${test_network}" in
    hsbc-market-test-db-*:hsbc-market-test-net-*)
      docker rm --force "${database_container}" >/dev/null 2>&1 || true
      docker network rm "${test_network}" >/dev/null 2>&1 || true
      ;;
    *)
      echo "Refusing to clean unexpected Docker resources" >&2
      ;;
  esac
}
trap cleanup EXIT INT TERM

docker network create "${test_network}" >/dev/null
docker run --detach --name "${database_container}" --network "${test_network}" \
  --env POSTGRES_DB=housing \
  --env POSTGRES_USER=market_test \
  --env POSTGRES_PASSWORD=market_test \
  postgres:17.4-alpine >/dev/null

attempt=0
until docker exec "${database_container}" pg_isready --username market_test --dbname housing >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 30 ]; then
    echo "Disposable PostgreSQL did not become ready" >&2
    exit 1
  fi
  sleep 1
done

docker run --rm --network "${test_network}" \
  --volume "${project_root}/database/migrations:/flyway/sql:ro" \
  flyway/flyway:11-alpine \
  -url="jdbc:postgresql://${database_container}:5432/housing" \
  -user=market_test \
  -password=market_test \
  -placeholders.database_name=housing \
  -placeholders.training_password=training_test \
  -placeholders.model_password=model_test \
  -placeholders.estimator_password=estimator_test \
  -placeholders.market_password=market_test \
  migrate

docker run --rm --network "${test_network}" \
  --env MARKET_DATABASE_URL="jdbc:postgresql://${database_container}:5432/housing" \
  --env MARKET_DATABASE_USER=market_runtime \
  --env MARKET_DATABASE_PASSWORD=market_test \
  --env MARKET_CURSOR_HMAC_SECRET="test-cursor-secret-at-least-32-characters" \
  --env MARKET_EXPORT_ROOT=/tmp/market-service-test-exports \
  --volume hsbc-maven-cache:/root/.m2 \
  --volume "${project_root}:/workspace" \
  --workdir /workspace/market-service \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -ntp clean verify
