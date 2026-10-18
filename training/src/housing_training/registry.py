from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from uuid import UUID

import psycopg
from psycopg.rows import dict_row


@dataclass(frozen=True)
class Registry:
    database_url: str

    def start_run(self, run_id: UUID, dataset_version_id: UUID, git_commit_sha: str) -> int:
        with psycopg.connect(self.database_url) as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT ml_registry.start_run(%s, %s, %s)",
                (run_id, dataset_version_id, git_commit_sha),
            )
            return int(cursor.fetchone()[0])

    def advance_run(
        self, run_id: UUID, revision: int, next_stage: str, details: dict[str, Any]
    ) -> int:
        with psycopg.connect(self.database_url) as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT ml_registry.advance_run(%s, %s, %s, %s::jsonb)",
                (run_id, revision, next_stage, psycopg.types.json.Jsonb(details)),
            )
            return int(cursor.fetchone()[0])

    def fail_run(self, run_id: UUID, revision: int, summary: str) -> int:
        with psycopg.connect(self.database_url) as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT ml_registry.fail_run(%s, %s, %s)",
                (run_id, revision, summary[:1000]),
            )
            return int(cursor.fetchone()[0])

    def record_evaluation(
        self,
        run_id: UUID,
        revision: int,
        metrics_path: str,
        metrics_sha256: str,
        values: dict[str, str],
    ) -> tuple[int, bool]:
        with psycopg.connect(self.database_url) as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT state_revision, acceptance_passed
                FROM ml_registry.record_evaluation(
                    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                )
                """,
                (
                    run_id,
                    revision,
                    metrics_path,
                    metrics_sha256,
                    values["mae"],
                    values["rmse"],
                    values["r2"],
                    values["mape"],
                    values["baseline_mae"],
                    values["baseline_rmse"],
                    values["baseline_mape"],
                ),
            )
            row = cursor.fetchone()
            return int(row[0]), bool(row[1])

    def register_candidate(
        self,
        run_id: UUID,
        revision: int,
        model_version: str,
        artifact_path: str,
        artifact_sha256: str,
        metadata_path: str,
        metadata_sha256: str,
    ) -> int:
        with psycopg.connect(self.database_url) as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT ml_registry.register_candidate(
                    %s, %s, %s, 'LinearRegression', %s, %s, %s, %s
                )
                """,
                (
                    run_id,
                    revision,
                    model_version,
                    artifact_path,
                    artifact_sha256,
                    metadata_path,
                    metadata_sha256,
                ),
            )
            return int(cursor.fetchone()[0])

    def promote_model(self, model_version: str) -> None:
        with psycopg.connect(self.database_url) as connection, connection.cursor() as cursor:
            cursor.execute("SELECT ml_registry.promote_model(%s)", (model_version,))

    def run(self, run_id: UUID) -> dict[str, Any]:
        with psycopg.connect(self.database_url, row_factory=dict_row) as connection:
            row = connection.execute(
                "SELECT * FROM ml_registry.training_runs WHERE training_run_id = %s", (run_id,)
            ).fetchone()
        if row is None:
            raise ValueError(f"training run {run_id} was not found")
        return dict(row)

    def dataset(self, dataset_version_id: UUID) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        with psycopg.connect(self.database_url, row_factory=dict_row) as connection:
            connection.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            metadata = connection.execute(
                "SELECT * FROM catalog.dataset_versions WHERE dataset_version_id = %s",
                (dataset_version_id,),
            ).fetchone()
            if metadata is None:
                raise ValueError(f"dataset version {dataset_version_id} was not found")
            rows = connection.execute(
                """
                SELECT source_id, square_footage, bedrooms, bathrooms, year_built,
                       lot_size, distance_to_city_center, school_rating, price
                  FROM catalog.housing_records
                 WHERE dataset_version_id = %s
                 ORDER BY source_id
                """,
                (dataset_version_id,),
            ).fetchall()
            connection.commit()
        return dict(metadata), [dict(row) for row in rows]
