from __future__ import annotations

import hashlib
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from psycopg import Connection
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .config import Settings


class Database:
    def __init__(self, settings: Settings):
        self._pool = ConnectionPool(
            conninfo=settings.database_url.get_secret_value(),
            min_size=1,
            max_size=10,
            kwargs={"row_factory": dict_row},
            open=False,
        )

    def open(self) -> None:
        self._pool.open(wait=True)

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def connection(self) -> Iterator[Connection]:
        with self._pool.connection() as connection:
            yield connection

    @contextmanager
    def transaction(self) -> Iterator[Connection]:
        with self._pool.connection() as connection, connection.transaction():
            yield connection

    def ping(self) -> bool:
        try:
            with self.connection() as connection:
                return connection.execute("select 1 as ok").fetchone()["ok"] == 1
        except Exception:
            return False


def apply_migrations(database: Database, migration_dir: Path) -> None:
    with database.transaction() as connection:
        connection.execute(
            """
            create table if not exists ai_schema_history (
                version integer primary key,
                description varchar(200) not null,
                checksum char(64) not null,
                applied_at timestamptz not null default clock_timestamp()
            )
            """
        )
    for path in sorted(migration_dir.glob("[0-9][0-9][0-9]_*.sql")):
        version = int(path.name.split("_", 1)[0])
        payload = path.read_bytes()
        checksum = hashlib.sha256(payload).hexdigest()
        with database.transaction() as connection:
            current = connection.execute(
                "select checksum from ai_schema_history where version = %s for update", (version,)
            ).fetchone()
            if current:
                if current["checksum"] != checksum:
                    raise RuntimeError(f"AI migration checksum mismatch for version {version}")
                continue
            connection.execute(payload.decode("utf-8"))
            connection.execute(
                "insert into ai_schema_history (version, description, checksum) values (%s, %s, %s)",
                (version, path.stem, checksum),
            )
