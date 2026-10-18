#!/bin/sh
set -eu

web_url="${PORTAL_PUBLIC_ORIGIN:-http://127.0.0.1:3000}"
model_url="${MODEL_PUBLIC_ORIGIN:-http://127.0.0.1:8000}"

curl --fail --silent --show-error --retry 30 --retry-delay 1 --retry-all-errors \
  "${model_url}/health/ready" >/dev/null
curl --fail --silent --show-error --retry 30 --retry-delay 1 --retry-all-errors \
  "${model_url}/docs" >/dev/null
curl --fail --silent --show-error --retry 30 --retry-delay 1 --retry-all-errors \
  "${web_url}/api/health" >/dev/null
curl --fail --silent --show-error --retry 30 --retry-delay 1 --retry-all-errors \
  "${web_url}/estimator" >/dev/null
curl --fail --silent --show-error --retry 30 --retry-delay 1 --retry-all-errors \
  "${web_url}/market" >/dev/null

echo "Smoke checks passed: ${web_url} and ${model_url}"
