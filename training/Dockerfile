FROM python:3.12.10-slim-bookworm AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /build
COPY pyproject.toml ./
COPY src ./src
RUN python -m pip install --upgrade "pip==25.0.1" && \
    python -m pip wheel --wheel-dir /wheels .

FROM python:3.12.10-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    SNAPSHOT_ROOT=/data/snapshots \
    TRAINING_RUN_ROOT=/data/runs \
    MODEL_ARTIFACT_ROOT=/data/artifacts
RUN addgroup --system --gid 10001 app && \
    adduser --system --uid 10001 --ingroup app --home /app app && \
    mkdir -p /data/snapshots /data/runs /data/artifacts && \
    chown -R app:app /data
WORKDIR /app
COPY --from=builder /wheels /wheels
RUN python -m pip install --no-index --find-links=/wheels hsbc-housing-training && \
    rm -r /wheels
COPY --chown=app:app scripts ./scripts
USER app
VOLUME ["/data/snapshots", "/data/runs", "/data/artifacts"]
ENTRYPOINT ["python"]
CMD ["scripts/bootstrap.py", "--help"]
