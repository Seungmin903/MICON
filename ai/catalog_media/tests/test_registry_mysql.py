import os
from pathlib import Path
import time
import uuid

import pymysql
from pymysql.constants import CLIENT
import pytest

from ai.catalog_media.registry import CatalogMediaRegistry


pytestmark = pytest.mark.skipif(os.environ.get("MICON_MYSQL_TEST") != "1",
                               reason="Set MICON_MYSQL_TEST=1 for disposable MySQL integration")
ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture
def connection():
    host = os.environ.get("MICON_TEST_DB_HOST", "mysql")
    assert host in ("mysql", "localhost", "127.0.0.1")
    settings = dict(host=host, port=int(os.environ.get("MICON_TEST_DB_PORT", "3306")),
                    user="root", password=os.environ["MICON_TEST_DB_PASSWORD"],
                    autocommit=True, charset="utf8mb4", client_flag=CLIENT.MULTI_STATEMENTS,
                    connect_timeout=2, read_timeout=60)
    deadline = time.monotonic() + 90
    while True:
        try:
            admin = pymysql.connect(**settings)
            break
        except pymysql.OperationalError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)
    name = "micon_media_test_" + uuid.uuid4().hex[:12]
    db = None
    try:
        with admin.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE {name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        db = pymysql.connect(database=name, **settings)
        with db.cursor() as cursor:
            for filename in ("V6__create_song_data_tables.sql", "V10__add_vocal_stem_song_media_kind.sql"):
                sql = (ROOT / "backend/src/main/resources/db/migration" / filename).read_text(encoding="utf-8")
                cursor.execute(sql)
                while cursor.nextset():
                    pass
            cursor.execute("INSERT INTO song(song_id,title) VALUES (1,'one'),(2,'two')")
            cursor.execute("INSERT INTO song_metadata(song_id,catalog_track_id) "
                           "VALUES (1,'0156ac758f1192ee'),(2,'0225119da4bd68d2')")
        yield db
    finally:
        if db:
            db.close()
        with admin.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS {name}")
        admin.close()


def rows():
    return [mapping_row("0156ac758f1192ee", 1), mapping_row("0225119da4bd68d2", 2)]


def mapping_row(track, song_id):
    relative = f"vocals/{track}/vocals.wav"
    return {
        "bucket": "micon-b110-prod-apne2-20260909", "catalog_track_id": track,
        "content_type": "audio/wav", "id_type": "song_metadata.catalog_track_id",
        "media_type": "vocal_stem", "object_key": f"catalog/정영주/section-384-v1/{relative}",
        "relative_path": relative, "schema_version": 1, "sha256": "a" * 64,
        "size_bytes": 4, "song_id": song_id,
    }


def test_register_is_transactional_idempotent_and_refuses_rebinding(connection):
    registry = CatalogMediaRegistry(connection)
    assert registry.register(rows()) == {"registered": 2, "inserted": 2, "already_registered": False}
    assert registry.register(rows()) == {"registered": 2, "inserted": 0, "already_registered": True}
    changed = rows()
    changed[0]["sha256"] = "b" * 64
    with pytest.raises(ValueError, match="refusing to overwrite"):
        registry.register(changed)
    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM song_media WHERE kind='VOCAL_STEM'")
        assert cursor.fetchone()[0] == 2


def test_wrong_database_mapping_rolls_back_all_rows(connection):
    changed = rows()
    changed[1]["song_id"] = 999
    with pytest.raises(ValueError, match="mapping mismatch"):
        CatalogMediaRegistry(connection).register(changed)
    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM song_media")
        assert cursor.fetchone()[0] == 0


def test_documented_importer_grants_are_sufficient_and_limited(connection):
    user = "media_test_" + uuid.uuid4().hex[:12]
    db_name = connection.db.decode() if isinstance(connection.db, bytes) else connection.db
    password = uuid.uuid4().hex
    restricted = None
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE USER '{user}'@'%%' IDENTIFIED BY %s", (password,))
            grants = (ROOT / "infra/mysql/catalog-media-importer-grants.sql").read_text(encoding="utf-8")
            grants = grants.replace("micon.", db_name + ".").replace(
                "'catalog_media_importer'", "'" + user + "'").replace("<IMPORTER_HOST>", "%")
            cursor.execute(grants)
            while cursor.nextset():
                pass
        restricted = pymysql.connect(host=os.environ.get("MICON_TEST_DB_HOST", "mysql"),
            port=int(os.environ.get("MICON_TEST_DB_PORT", "3306")), user=user, password=password,
            database=db_name, charset="utf8mb4", autocommit=True)
        assert CatalogMediaRegistry(restricted).register(rows())["inserted"] == 2
        assert CatalogMediaRegistry(restricted).register(rows())["already_registered"]
        with restricted.cursor() as cursor:
            for sql in ("DELETE FROM song_media", "UPDATE song_media SET file_bytes=1",
                        "INSERT INTO song(title) VALUES ('not allowed')",
                        "CREATE TABLE unauthorized_table(id INT)"):
                with pytest.raises(pymysql.MySQLError) as denied:
                    cursor.execute(sql)
                assert denied.value.args[0] == 1142
    finally:
        if restricted:
            restricted.close()
        with connection.cursor() as cursor:
            cursor.execute(f"DROP USER IF EXISTS '{user}'@'%'")
