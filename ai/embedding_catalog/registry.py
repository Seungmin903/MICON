"""Transactional metadata registration. Never creates schemas or imports songs."""
from __future__ import annotations

import json

from .contract import DIMENSIONS, digest, json_bytes, require
from .validation import validate_remote


class Registry:
    def __init__(self, connection):
        self.db = connection

    def register(self, store, manifest_key: str, expected_sha256: str) -> dict:
        manifest, data = validate_remote(store, manifest_key, expected_sha256)
        batch_id = manifest["batch_id"]
        self.db.begin()
        try:
            with self.db.cursor() as cursor:
                cursor.execute("SELECT manifest_sha256, bucket, region, manifest_key FROM embedding_catalog_batch "
                               "WHERE batch_id=%s FOR SHARE", (batch_id,))
                existing = cursor.fetchone()
                if existing:
                    require(existing == (expected_sha256, store.bucket, store.region, manifest_key),
                            "batch_id already identifies different content")
                    self.db.commit()
                    return {"batch_id": batch_id, "tracks": len(manifest["tracks"]), "already_registered": True}
                # Exact identifiers only. The Python lookup also rejects collation-based approximate matches.
                ids = [track["catalog_track_id"] for track in manifest["tracks"]]
                placeholders = ",".join(["%s"] * len(ids))
                cursor.execute("SELECT catalog_track_id, song_id FROM song_metadata WHERE catalog_track_id IN ("
                               + placeholders + ") FOR SHARE", ids)
                mapping = dict(cursor.fetchall())
                missing = [track_id for track_id in ids if track_id not in mapping]
                require(not missing, f"Unregistered catalog IDs ({len(missing)}): {missing[:10]}")
                require(len(set(mapping.values())) == len(ids), "Multiple catalog IDs map to one song")
                cursor.execute(
                    "INSERT INTO embedding_catalog_batch "
                    "(batch_id,bucket,region,object_prefix,manifest_key,manifest_sha256,manifest_bytes,models_json,track_count) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (batch_id, store.bucket, store.region, manifest["prefix"], manifest_key,
                     digest(data), len(data), json_bytes(manifest["models"]).decode(), len(ids)))
                entries = []
                for track in manifest["tracks"]:
                    for model, record in track["embeddings"].items():
                        entries.append((batch_id, mapping[track["catalog_track_id"]], track["catalog_track_id"],
                                        model, record["key"], record["sha256"], record["bytes"],
                                        record["rows"], DIMENSIONS[model]))
                cursor.executemany(
                    "INSERT INTO song_embedding "
                    "(batch_id,song_id,catalog_track_id,model_key,object_key,sha256,file_bytes,segment_count,dimension) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)", entries)
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise
        return {"batch_id": batch_id, "tracks": len(ids), "already_registered": False}

    def activate(self, store, batch_id: str, expected_revision: int, actor: str) -> dict:
        require(type(expected_revision) is int and expected_revision >= 0, "An expected revision is required")
        require(isinstance(actor, str) and 0 < len(actor.strip()) <= 100, "An actor is required")
        with self.db.cursor() as cursor:
            cursor.execute("SELECT bucket,region,manifest_key,manifest_sha256,track_count "
                           "FROM embedding_catalog_batch WHERE batch_id=%s", (batch_id,))
            batch = cursor.fetchone()
        require(batch is not None, "Register the batch before activation")
        require(batch[:2] == (store.bucket, store.region), "Store does not match the registered batch")
        manifest, _ = validate_remote(store, batch[2], batch[3])
        require(len(manifest["tracks"]) == batch[4], "Registered track count changed")
        self.db.begin()
        try:
            with self.db.cursor() as cursor:
                cursor.execute("SELECT batch_id,revision FROM embedding_catalog_active "
                               "WHERE channel='default' FOR UPDATE")
                active = cursor.fetchone()
                require(active is not None, "Active catalog row is missing; apply Flyway migrations")
                previous, revision = active
                require(revision == expected_revision, f"Revision conflict: expected={expected_revision}, actual={revision}")
                cursor.execute("SELECT catalog_track_id,model_key,object_key,sha256,file_bytes,segment_count,dimension "
                               "FROM song_embedding WHERE batch_id=%s", (batch_id,))
                actual = set(cursor.fetchall())
                expected = {(track["catalog_track_id"], model, record["key"], record["sha256"],
                             record["bytes"], record["rows"], DIMENSIONS[model])
                            for track in manifest["tracks"] for model, record in track["embeddings"].items()}
                require(actual == expected, "Registered embeddings no longer match the manifest")
                if previous != batch_id:
                    revision += 1
                    cursor.execute("UPDATE embedding_catalog_active SET batch_id=%s,revision=%s,"
                                   "updated_at=CURRENT_TIMESTAMP(6) WHERE channel='default'", (batch_id, revision))
                    cursor.execute("INSERT INTO embedding_catalog_event "
                                   "(channel,previous_batch_id,batch_id,revision,actor) VALUES ('default',%s,%s,%s,%s)",
                                   (previous, batch_id, revision, actor))
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise
        return {"batch_id": batch_id, "previous_batch_id": previous, "revision": revision}

    def snapshot(self) -> dict:
        self.db.begin()
        try:
            with self.db.cursor() as cursor:
                cursor.execute(
                    "SELECT a.revision,b.batch_id,b.bucket,b.region,b.manifest_key,b.manifest_sha256,"
                    "b.manifest_bytes,b.track_count,b.models_json FROM embedding_catalog_active a "
                    "JOIN embedding_catalog_batch b ON b.batch_id=a.batch_id WHERE a.channel='default'")
                batch = cursor.fetchone()
                require(batch is not None, "No active embedding catalog")
                names = ("revision", "batch_id", "bucket", "region", "manifest_key", "manifest_sha256",
                         "manifest_bytes", "track_count", "models")
                result = dict(zip(names, batch))
                result.update(schema_version=1, channel="default")
                result["models"] = json.loads(result["models"])
                cursor.execute("SELECT song_id,catalog_track_id,model_key,object_key,sha256,file_bytes,segment_count,dimension "
                               "FROM song_embedding WHERE batch_id=%s ORDER BY catalog_track_id,model_key",
                               (result["batch_id"],))
                tracks = {}
                for song_id, track_id, model, key, sha256, size, count, dimension in cursor.fetchall():
                    track = tracks.setdefault(track_id, {"song_id": song_id, "catalog_track_id": track_id, "embeddings": {}})
                    require(track["song_id"] == song_id, "Inconsistent song mapping")
                    track["embeddings"][model] = {"key": key, "sha256": sha256, "bytes": size,
                                                  "rows": count, "dimension": dimension}
                require(len(tracks) == result["track_count"]
                        and all(set(t["embeddings"]) == set(DIMENSIONS) for t in tracks.values()),
                        "Active catalog is incomplete")
                result["tracks"] = list(tracks.values())
            self.db.commit()
            return result
        except Exception:
            self.db.rollback()
            raise
