from __future__ import annotations

from .contract import require


class CatalogMediaRegistry:
    def __init__(self, connection):
        self.db = connection

    def register(self, rows: list[dict]) -> dict:
        expected_mapping = {(row["catalog_track_id"], row["song_id"]) for row in rows}
        expected_media = {self._media_tuple(row) for row in rows}
        self.db.begin()
        try:
            with self.db.cursor() as cursor:
                ids = [row["catalog_track_id"] for row in rows]
                placeholders = ",".join(["%s"] * len(ids))
                cursor.execute(
                    "SELECT catalog_track_id,song_id FROM song_metadata WHERE catalog_track_id IN ("
                    + placeholders + ") FOR SHARE", ids)
                actual_mapping = {(str(track), int(song_id)) for track, song_id in cursor.fetchall()}
                require(actual_mapping == expected_mapping, "DB catalog_track_id/song_id mapping mismatch")

                song_ids = [row["song_id"] for row in rows]
                placeholders = ",".join(["%s"] * len(song_ids))
                cursor.execute(
                    "SELECT song_id,file_path,mime_type,file_bytes,sha256,generation_status "
                    "FROM song_media WHERE kind='VOCAL_STEM' AND semitones=0 AND song_id IN ("
                    + placeholders + ")", song_ids)
                existing = {tuple(item) for item in cursor.fetchall()}
                require(existing.issubset(expected_media),
                        "Existing VOCAL_STEM rows differ; refusing to overwrite")
                missing = expected_media - existing
                if missing:
                    cursor.executemany(
                        "INSERT INTO song_media "
                        "(song_id,kind,semitones,file_path,mime_type,file_bytes,sha256,generation_status) "
                        "VALUES (%s,'VOCAL_STEM',0,%s,%s,%s,%s,%s)", sorted(missing))
                cursor.execute(
                    "SELECT song_id,file_path,mime_type,file_bytes,sha256,generation_status "
                    "FROM song_media WHERE kind='VOCAL_STEM' AND semitones=0 AND song_id IN ("
                    + placeholders + ")", song_ids)
                registered = {tuple(item) for item in cursor.fetchall()}
                require(registered == expected_media, "Registered VOCAL_STEM rows failed final verification")
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise
        return {"registered": len(expected_media), "inserted": len(missing),
                "already_registered": len(missing) == 0}

    @staticmethod
    def _media_tuple(row: dict) -> tuple:
        return (row["song_id"], row["object_key"], row["content_type"],
                row["size_bytes"], row["sha256"], "READY")
