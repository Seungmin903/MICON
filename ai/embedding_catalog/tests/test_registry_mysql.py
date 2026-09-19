"""Integration against disposable MySQL only; never applies DDL to an existing DB."""
import copy
import json
import os
from pathlib import Path
import time
import uuid

import pymysql
from pymysql.constants import CLIENT
import pytest

from ai.embedding_catalog.contract import digest, json_bytes
from ai.embedding_catalog.prepare import upload
from ai.embedding_catalog.registry import Registry
from ai.embedding_catalog.loader import CatalogCache

pytestmark = pytest.mark.skipif(os.environ.get("MICON_MYSQL_TEST") != "1",
                               reason="Set MICON_MYSQL_TEST=1 for disposable MySQL integration")
ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture
def connection():
    host = os.environ.get("MICON_TEST_DB_HOST", "mysql")
    assert host in ("mysql", "localhost", "127.0.0.1"), "Test DB must be local or isolated service"
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
    name = "micon_embedding_test_" + uuid.uuid4().hex[:12]
    db = None
    try:
        with admin.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE {name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        db = pymysql.connect(database=name, **settings)
        with db.cursor() as cursor:
            for filename in ("V6__create_song_data_tables.sql", "V7__create_embedding_catalog.sql"):
                sql = (ROOT / "backend/src/main/resources/db/migration" / filename).read_text(encoding="utf-8")
                cursor.execute(sql)
                while cursor.nextset():
                    pass
            cursor.execute("INSERT INTO song(song_id,title,status) "
                           "VALUES (1,'test one','ACTIVE'),(2,'test two','ACTIVE')")
            cursor.execute("INSERT INTO song_metadata(song_id,catalog_track_id) VALUES (1,'catalog-0'),(2,'catalog-1')")
        yield db
    finally:
        if db:
            db.close()
        assert name.startswith("micon_embedding_test_") and len(name) == 33
        with admin.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS {name}")
        admin.close()


def count(connection, table):
    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM " + table)
        return cursor.fetchone()[0]


def publish_register(factory, store, registry, name):
    directory, manifest, checksum = factory(name)
    upload(directory, store)
    registry.register(store, manifest["prefix"] + "manifest.json", checksum)
    return manifest, checksum


def test_register_retry_activate_and_snapshot(connection, package_factory, store, models, tmp_path):
    registry = Registry(connection)
    manifest, checksum = publish_register(package_factory, store, registry, "first")
    duplicate = registry.register(store, manifest["prefix"] + "manifest.json", checksum)
    assert duplicate["already_registered"]
    assert count(connection, "embedding_catalog_batch") == 1
    assert count(connection, "song_embedding") == 4
    activation = registry.activate(store, "first", 0, "test-operator")
    assert activation["revision"] == 1
    snapshot = registry.snapshot()
    assert snapshot["batch_id"] == "first"
    assert snapshot["revision"] == 1
    assert [t["song_id"] for t in snapshot["tracks"]] == [1, 2]
    assert snapshot["tracks"][0]["embeddings"]["ecapa"]["dimension"] == 192
    cached = CatalogCache(store, tmp_path / "mysql-snapshot-cache", models).refresh(snapshot)
    assert set(cached.arrays) == {1, 2}
    assert registry.activate(store, "first", 1, "test-operator")["revision"] == 1
    assert count(connection, "embedding_catalog_event") == 1


def test_activation_revision_conflict_and_rollback(connection, package_factory, store):
    registry = Registry(connection)
    publish_register(package_factory, store, registry, "first")
    publish_register(package_factory, store, registry, "second")
    registry.activate(store, "first", 0, "test")
    with pytest.raises(ValueError, match="Revision conflict"):
        registry.activate(store, "second", 0, "stale-operator")
    assert registry.snapshot()["batch_id"] == "first"
    registry.activate(store, "second", 1, "test")
    registry.activate(store, "first", 2, "rollback")
    assert registry.snapshot()["revision"] == 3
    assert registry.snapshot()["batch_id"] == "first"
    assert count(connection, "embedding_catalog_event") == 3


def test_unregistered_song_prevents_all_metadata_writes(connection, package_factory, store):
    directory, manifest, checksum = package_factory(tracks=3)
    upload(directory, store)
    with pytest.raises(ValueError, match="Unregistered"):
        Registry(connection).register(store, manifest["prefix"] + "manifest.json", checksum)
    assert count(connection, "embedding_catalog_batch") == 0
    assert count(connection, "song_embedding") == 0


def test_incomplete_database_batch_cannot_be_activated(connection, package_factory, store):
    registry = Registry(connection)
    publish_register(package_factory, store, registry, "first")
    with connection.cursor() as cursor:
        cursor.execute("DELETE FROM song_embedding WHERE song_id=2 AND model_key='ecapa'")
    with pytest.raises(ValueError, match="no longer match"):
        registry.activate(store, "first", 0, "test")
    assert count(connection, "embedding_catalog_event") == 0
    with pytest.raises(ValueError, match="No active"):
        registry.snapshot()


def test_corruption_before_activation_keeps_previous_batch(connection, package_factory, store):
    registry = Registry(connection)
    publish_register(package_factory, store, registry, "first")
    second, _ = publish_register(package_factory, store, registry, "second")
    registry.activate(store, "first", 0, "test")
    key = second["tracks"][0]["embeddings"]["ecapa"]["key"]
    store.client.put_object(Bucket=store.bucket, Key=key, Body=b"bad")
    with pytest.raises(ValueError):
        registry.activate(store, "second", 1, "test")
    assert registry.snapshot()["batch_id"] == "first"


def test_rebinding_existing_batch_is_rejected(connection, package_factory, store):
    registry = Registry(connection)
    manifest, _ = publish_register(package_factory, store, registry, "first")
    changed = copy.deepcopy(manifest)
    changed["prefix"] = "catalog/another-owner/first/"
    for old, new in zip(manifest["tracks"], changed["tracks"]):
        for kind in ("ecapa", "singer_byol"):
            record = new["embeddings"][kind]
            data = store.get(record["key"], record["bytes"])
            record["key"] = record["key"].replace(manifest["prefix"], changed["prefix"], 1)
            store.put_immutable(record["key"], data)
        record = new["segments"]
        data = store.get(record["key"], record["bytes"])
        record["key"] = record["key"].replace(manifest["prefix"], changed["prefix"], 1)
        store.put_immutable(record["key"], data)
    raw = json_bytes(changed)
    key = changed["prefix"] + "manifest.json"
    store.put_immutable(key, raw)
    with pytest.raises(ValueError, match="different content"):
        registry.register(store, key, digest(raw))
    assert count(connection, "embedding_catalog_batch") == 1


def test_collation_approximate_id_match_is_rejected(connection, package_factory, store):
    directory, manifest, checksum = package_factory()
    upload(directory, store)
    with connection.cursor() as cursor:
        cursor.execute("UPDATE song_metadata SET catalog_track_id='CATALOG-0' WHERE song_id=1")
    with pytest.raises(ValueError, match="Unregistered"):
        Registry(connection).register(store, manifest["prefix"] + "manifest.json", checksum)
    assert count(connection, "embedding_catalog_batch") == 0


def test_documented_importer_grants_are_sufficient_and_limited(connection, package_factory, store):
    user = "catalog_test_" + uuid.uuid4().hex[:12]
    db_name = connection.db.decode() if isinstance(connection.db, bytes) else connection.db
    assert db_name.startswith("micon_embedding_test_")
    password = uuid.uuid4().hex
    restricted = None
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE USER '{user}'@'%%' IDENTIFIED BY %s", (password,))
            grants = (ROOT / "infra/mysql/catalog-importer-grants.sql").read_text(encoding="utf-8")
            grants = grants.replace("micon.", db_name + ".").replace("'catalog_importer'", "'" + user + "'")
            grants = grants.replace("<IMPORTER_HOST>", "%")
            cursor.execute(grants)
            while cursor.nextset():
                pass
        restricted = pymysql.connect(host=os.environ.get("MICON_TEST_DB_HOST", "mysql"),
            port=int(os.environ.get("MICON_TEST_DB_PORT", "3306")), user=user, password=password,
            database=db_name, charset="utf8mb4", autocommit=True)
        registry = Registry(restricted)
        publish_register(package_factory, store, registry, "least-privilege")
        registry.activate(store, "least-privilege", 0, "test")
        assert registry.snapshot()["track_count"] == 2
        with restricted.cursor() as cursor:
            for sql in ("DELETE FROM song_embedding",
                        "UPDATE song_embedding SET file_bytes=1",
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
