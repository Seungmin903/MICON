#!/usr/bin/env python3
"""Build a direct MySQL import for songs and every populated related table."""

from __future__ import annotations

import json
import sys
import unicodedata
from datetime import date
from pathlib import Path


SECTION_TYPES = {"INTRO", "VOCAL", "INTERLUDE", "OUTRO"}
SECTION_NAMES = {"INTRO": "인트로", "VOCAL": "보컬 구간", "INTERLUDE": "간주", "OUTRO": "아웃트로"}
IMAGE_TYPES = {"album_cover": "ALBUM_COVER", "video_thumbnail": "VIDEO_THUMBNAIL", None: None}

# External catalog genres -> normalized_name values from genre_seed.sql.
# Tuple order: Korean-language track, other-language track.
GENRE_MAP = {
    "Ballad": ("kpop-ballad", "kpop-ballad"),
    "Dance": ("kpop-dance-pop", "kpop-dance-pop"),
    "Folk": ("kpop-folk-acoustic", "etc-world-music"),
    "Singer-Songwriter": ("kpop-folk-acoustic", "etc-world-music"),
    "K-Pop": ("kpop-dance-pop", "kpop-dance-pop"),
    "Pop": ("kpop-dance-pop", "global-pop"),
    "Hip-Hop": ("kpop-rap-hiphop", "global-rap-hiphop"),
    "R&B": ("kpop-rnb-soul", "global-rnb-soul"),
    "Rock": ("kpop-rock-metal", "global-rock"),
    "Alternative": ("kpop-rock-metal", "global-rock"),
    "Country": ("etc-world-music", "etc-world-music"),
    "Jazz": ("kpop-jazz", "global-jazz"),
    "Trot": ("kpop-adult", "kpop-adult"),
    "Soundtrack": ("etc-ost", "etc-ost"),
    "J-Pop": ("etc-jpop", "etc-jpop"),
    "Holiday": ("etc-carol", "etc-carol"),
}


def sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def sql_nullable_string(value: object) -> str:
    return "NULL" if value is None else sql_string(str(value))


def sql_json(value: object) -> str:
    if value is None:
        return "NULL"
    return sql_string(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def normalized_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def normalized_release_date(value: object) -> str | None:
    """Return a MySQL DATE only for complete ISO dates; preserve partial text separately."""
    if value is None:
        return None
    candidate = str(value).strip()
    if not candidate:
        return None
    try:
        date.fromisoformat(candidate)
    except ValueError:
        return None
    return candidate


def split_source_folder(source_folder: str) -> tuple[str, str]:
    artist, separator, title = source_folder.partition("__")
    if not separator or not artist.strip() or not title.strip():
        raise ValueError(f"cannot derive artist/title from sourceFolder: {source_folder!r}")
    return artist.strip(), title.strip()


def canonical_genres(source_genres: list[str], language: str | None) -> list[str]:
    is_korean = language == "kor"
    mapped: list[str] = []
    # K-Pop is too broad when a more specific source genre is also present.
    effective = [genre for genre in source_genres if genre != "K-Pop"] or source_genres
    for source_genre in effective:
        if source_genre not in GENRE_MAP:
            raise ValueError(f"unmapped source genre: {source_genre}")
        korean_key, international_key = GENRE_MAP[source_genre]
        key = korean_key if is_korean else international_key
        if key not in mapped:
            mapped.append(key)
    return mapped


def prepare_record(raw: dict[str, object], line_number: int) -> dict[str, object]:
    track_id = raw.get("trackId")
    source_folder = raw.get("sourceFolder")
    analysis = raw.get("sectionAnalysis")
    extraction = raw.get("sectionExtraction") or {}
    if not isinstance(track_id, str) or not track_id:
        raise ValueError(f"line {line_number}: trackId is missing")
    if not isinstance(source_folder, str):
        raise ValueError(f"line {line_number}: sourceFolder is missing")
    if not isinstance(analysis, dict) or not isinstance(analysis.get("segments"), list):
        raise ValueError(f"line {line_number}: sectionAnalysis.segments is missing")

    derived_artist, derived_title = split_source_folder(source_folder)
    has_catalog_metadata = isinstance(raw.get("title"), str) and bool(raw.get("title"))
    title = str(raw.get("title")) if has_catalog_metadata else derived_title
    raw_artists = raw.get("artists") if has_catalog_metadata else None
    artists = [
        {"name": str(artist["name"]).strip(), "role": str(artist.get("role", "primary"))}
        for artist in (raw_artists or [])
        if isinstance(artist, dict) and artist.get("name")
    ]
    if not artists:
        artists = [{"name": derived_artist, "role": "primary"}]
    if len(title) > 255:
        raise ValueError(f"line {line_number}: title exceeds SONG.title")
    if any(len(artist["name"]) > 100 for artist in artists):
        raise ValueError(f"line {line_number}: artist exceeds SINGER.name")

    language = raw.get("languages") if isinstance(raw.get("languages"), str) else None
    source_genres = [str(value) for value in (raw.get("normalized_genres") or [])]
    genres = canonical_genres(source_genres, language)
    if raw.get("image_type") not in IMAGE_TYPES:
        raise ValueError(f"line {line_number}: unsupported image_type {raw.get('image_type')!r}")

    catalog_match = raw.get("catalogMatch") or {}
    catalog_status = str(catalog_match.get("status", "unmatched"))
    lyrics = raw.get("lyrics") or {}
    segments: list[dict[str, object]] = []
    for segment_index, segment in enumerate(analysis["segments"]):
        section_type = str(segment.get("type", "CUSTOM")).upper()
        if section_type not in SECTION_TYPES:
            section_type = "CUSTOM"
        start_ms = round(float(segment["start"]) * 1000)
        end_ms = round(float(segment["end"]) * 1000)
        if end_ms <= start_ms:
            raise ValueError(f"line {line_number}, segment {segment_index}: invalid time range")
        verse = segment.get("verseCandidate")
        section_name = (
            f"{verse}절 보컬"
            if section_type == "VOCAL" and verse is not None
            else SECTION_NAMES.get(section_type, "사용자 지정 구간")
        )
        segments.append(
            {
                "sequence_no": segment_index + 1,
                "section_type": section_type,
                "section_name": section_name,
                "start_ms": start_ms,
                "end_ms": end_ms,
                "needs_review": bool(segment.get("needsReview", False)),
                "review_reasons": segment.get("reviewReasons") or [],
                "source_metadata": {
                    "sourceFolder": source_folder,
                    "segmentIndex": segment_index,
                    "verseCandidate": verse,
                    "isMainInterludeCandidate": segment.get("isMainInterludeCandidate", False),
                    "verseLabelsAreEstimates": analysis.get("verseLabelsAreEstimates", False),
                    "catalogMatchStatus": catalog_status,
                },
            }
        )

    vocal_segments = [segment for segment in analysis["segments"] if segment.get("type") == "vocal"]
    config = ((extraction.get("signature") or {}).get("config") or {})
    version = str(config.get("version") or "unknown")
    return {
        "track_id": track_id,
        "title": title,
        "title_normalized": normalized_text(title),
        "duration_ms": round(float(analysis["duration"]) * 1000),
        "artists": artists,
        "album_name": raw.get("album_name"),
        "release_date": normalized_release_date(raw.get("release_date")),
        "release_date_raw": raw.get("release_date"),
        "display_image_url": raw.get("display_image_url"),
        "image_type": IMAGE_TYPES[raw.get("image_type")],
        "language": language,
        "languages": [language] if language else None,
        "lyrics_plain": lyrics.get("plain"),
        "lyrics_synced": lyrics.get("synced"),
        "credits": raw.get("credit_relations"),
        "source_genres": source_genres or None,
        "genres": genres,
        "metadata_status": "PENDING" if has_catalog_metadata else "NEEDS_REVIEW",
        "external_ids": {
            "sectionCatalogRows": catalog_match.get("catalogRows") or [],
            "sectionCatalogMatchStatus": catalog_status,
        },
        "analysis_version": f"section-{version}"[:50],
        "vocal_start_sec": min((float(s["start"]) for s in vocal_segments), default=None),
        "vocal_end_sec": max((float(s["end"]) for s in vocal_segments), default=None),
        "timings": {"sectionAnalysis": analysis},
        "provenance": {
            "trackId": track_id,
            "sourceFolder": source_folder,
            "catalogMatch": catalog_match,
            "sectionExtraction": extraction,
        },
        "analyzed_at": extraction.get("analyzedAt"),
        "segments": segments,
        "has_catalog_metadata": has_catalog_metadata,
    }


def load_records(input_path: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    seen_track_ids: set[str] = set()
    with input_path.open("r", encoding="utf-8") as source:
        for line_number, raw_line in enumerate(source, start=1):
            if not raw_line.strip():
                continue
            record = prepare_record(json.loads(raw_line), line_number)
            track_id = str(record["track_id"])
            if track_id in seen_track_ids:
                raise ValueError(f"line {line_number}: duplicate trackId {track_id}")
            seen_track_ids.add(track_id)
            records.append(record)
    return records


def render_record(record: dict[str, object]) -> list[str]:
    track = sql_string(str(record["track_id"]))
    title = sql_string(str(record["title"]))
    normalized_title = sql_string(str(record["title_normalized"]))
    lines = [
        f"-- catalog track: {record['track_id']}",
        "SET @song_id = (",
        "    SELECT `sm`.`song_id` FROM `song_metadata` AS `sm`",
        f"    WHERE `sm`.`catalog_track_id` = {track}",
        "       OR (`sm`.`catalog_track_id` IS NULL",
        f"           AND `sm`.`track_id` = {track}",
        "           AND NOT EXISTS (",
        "               SELECT 1 FROM `song_metadata` AS `preferred`",
        f"               WHERE `preferred`.`catalog_track_id` = {track}",
        "           ))",
        f"    ORDER BY (`sm`.`catalog_track_id` = {track}) DESC LIMIT 1",
        ");",
        "INSERT INTO `song` (`title`, `title_normalized`, `duration_ms`, `status`, `practice_ready`)",
        f"SELECT {title}, {normalized_title}, {record['duration_ms']}, 'ACTIVE', FALSE",
        "WHERE @song_id IS NULL;",
        "SET @song_id = COALESCE(@song_id, LAST_INSERT_ID());",
        "UPDATE `song`",
        f"SET `title` = {title}, `title_normalized` = {normalized_title},",
        f"    `duration_ms` = {record['duration_ms']}, `status` = 'ACTIVE'",
        "WHERE `song_id` = @song_id;",
        "",
        "INSERT INTO `song_metadata` (",
        "    `song_id`, `catalog_track_id`, `external_ids`, `credits`, `album_name`,",
        "    `release_date`, `release_date_raw`, `display_image_url`, `primary_language`, `languages`,",
        "    `source_genres`, `lyrics_plain`, `lyrics_synced`, `image_type`, `metadata_review_status`",
        ") VALUES (",
        "    " + ", ".join(
            [
                "@song_id", track, sql_json(record["external_ids"]), sql_json(record["credits"]),
                sql_nullable_string(record["album_name"]), sql_nullable_string(record["release_date"]),
                sql_nullable_string(record["release_date_raw"]),
                sql_nullable_string(record["display_image_url"]), sql_nullable_string(record["language"]),
                sql_json(record["languages"]), sql_json(record["source_genres"]),
                sql_nullable_string(record["lyrics_plain"]), sql_json(record["lyrics_synced"]),
                sql_nullable_string(record["image_type"]), sql_string(str(record["metadata_status"])),
            ]
        ),
        ") ON DUPLICATE KEY UPDATE",
        "    `catalog_track_id` = VALUES(`catalog_track_id`), `external_ids` = VALUES(`external_ids`),",
                    "    `credits` = VALUES(`credits`), `album_name` = VALUES(`album_name`),",
        "    `release_date` = VALUES(`release_date`), `release_date_raw` = VALUES(`release_date_raw`),",
        "    `display_image_url` = VALUES(`display_image_url`),",
        "    `primary_language` = VALUES(`primary_language`), `languages` = VALUES(`languages`),",
        "    `source_genres` = VALUES(`source_genres`), `lyrics_plain` = VALUES(`lyrics_plain`),",
        "    `lyrics_synced` = VALUES(`lyrics_synced`), `image_type` = VALUES(`image_type`),",
        "    `metadata_review_status` = VALUES(`metadata_review_status`),",
        "    `updated_at` = CURRENT_TIMESTAMP(6);",
        "",
    ]

    for display_order, artist in enumerate(record["artists"], start=1):
        name = str(artist["name"])
        role = "FEATURED" if str(artist.get("role", "primary")).lower() == "featured" else "MAIN"
        lines.extend([
            "INSERT INTO `singer` (`name`, `normalized_name`)",
            f"VALUES ({sql_string(name)}, {sql_string(normalized_text(name))})",
            "ON DUPLICATE KEY UPDATE `singer_id` = LAST_INSERT_ID(`singer_id`), `name` = VALUES(`name`);",
            "SET @singer_id = LAST_INSERT_ID();",
            "INSERT INTO `song_singer` (`song_id`, `singer_id`, `role`, `display_order`)",
            f"VALUES (@song_id, @singer_id, {sql_string(role)}, {display_order})",
            "ON DUPLICATE KEY UPDATE `role` = VALUES(`role`), `display_order` = VALUES(`display_order`);",
            "",
        ])

    for genre_order, genre_key in enumerate(record["genres"], start=1):
        lines.extend([
            "INSERT INTO `song_genre` (`song_id`, `genre_id`, `is_primary`)",
            f"SELECT @song_id, `genre_id`, {'TRUE' if genre_order == 1 else 'FALSE'} FROM `genre`",
            f"WHERE `normalized_name` = {sql_string(str(genre_key))}",
            "ON DUPLICATE KEY UPDATE `is_primary` = VALUES(`is_primary`);",
            "",
        ])

    analyzed_at = f"FROM_UNIXTIME({float(record['analyzed_at']):.6f})" if record["analyzed_at"] is not None else "NULL"
    lines.extend([
        "INSERT INTO `song_analysis` (",
        "    `song_id`, `analysis_version`, `status`, `vocal_start_sec`, `vocal_end_sec`,",
        "    `timings`, `provenance`, `analyzed_at`",
        ") VALUES (",
        "    " + ", ".join([
            "@song_id", sql_string(str(record["analysis_version"])), "'PARTIAL'",
            "NULL" if record["vocal_start_sec"] is None else str(record["vocal_start_sec"]),
            "NULL" if record["vocal_end_sec"] is None else str(record["vocal_end_sec"]),
            sql_json(record["timings"]), sql_json(record["provenance"]), analyzed_at,
        ]),
        ") ON DUPLICATE KEY UPDATE",
        "    `analysis_id` = LAST_INSERT_ID(`analysis_id`),",
        "    `vocal_start_sec` = COALESCE(`vocal_start_sec`, VALUES(`vocal_start_sec`)),",
        "    `vocal_end_sec` = COALESCE(`vocal_end_sec`, VALUES(`vocal_end_sec`)),",
        "    `timings` = JSON_MERGE_PATCH(COALESCE(`timings`, JSON_OBJECT()), VALUES(`timings`)),",
        "    `provenance` = JSON_MERGE_PATCH(COALESCE(`provenance`, JSON_OBJECT()), VALUES(`provenance`)),",
        "    `analyzed_at` = COALESCE(`analyzed_at`, VALUES(`analyzed_at`));",
        "SET @analysis_id = LAST_INSERT_ID();",
        "",
        "INSERT INTO `song_section` (",
        "    `song_id`, `analysis_id`, `section_type`, `section_name`, `start_ms`, `end_ms`,",
        "    `sequence_no`, `difficulty_type`, `source_type`, `needs_review`, `review_reasons`, `source_metadata`",
        ") VALUES",
    ])
    section_values = []
    for segment in record["segments"]:
        section_values.append("    (" + ", ".join([
            "@song_id", "@analysis_id", sql_string(str(segment["section_type"])),
            sql_string(str(segment["section_name"])), str(segment["start_ms"]), str(segment["end_ms"]),
            str(segment["sequence_no"]), "'NONE'", "'AI'",
            "TRUE" if segment["needs_review"] else "FALSE",
            sql_json(segment["review_reasons"]), sql_json(segment["source_metadata"]),
        ]) + ")")
    lines.extend([
        ",\n".join(section_values),
        "ON DUPLICATE KEY UPDATE",
        "    `analysis_id` = VALUES(`analysis_id`), `section_type` = VALUES(`section_type`),",
        "    `section_name` = VALUES(`section_name`), `start_ms` = VALUES(`start_ms`),",
        "    `end_ms` = VALUES(`end_ms`), `source_type` = VALUES(`source_type`),",
        "    `needs_review` = VALUES(`needs_review`), `review_reasons` = VALUES(`review_reasons`),",
        "    `source_metadata` = VALUES(`source_metadata`), `updated_at` = CURRENT_TIMESTAMP(6);",
        "",
    ])
    return lines


def render_sql(records: list[dict[str, object]]) -> str:
    segment_count = sum(len(record["segments"]) for record in records)
    metadata_count = sum(bool(record["has_catalog_metadata"]) for record in records)
    genre_relation_count = sum(len(record["genres"]) for record in records)
    track_list = ", ".join(sql_string(str(record["track_id"])) for record in records)
    output = [
        "-- Generated from section_metadata.jsonl",
        f"-- Songs: {len(records)} (catalog metadata: {metadata_count}, sourceFolder-derived: {len(records) - metadata_count})",
        f"-- Sections: {segment_count}",
        f"-- Expected song-genre relations: {genre_relation_count}",
        "-- Run order: MICON_COMPLETE_DDL.sql -> genre_seed.sql -> this file",
        "-- Populates SONG, SONG_METADATA, SINGER, SONG_SINGER, SONG_ANALYSIS, SONG_SECTION and SONG_GENRE.",
        "-- SONG_NOTE and SONG_MEDIA are untouched because this source has no note or S3 object data.",
        "",
        "SET NAMES utf8mb4;", "USE `micon`;", "START TRANSACTION;", "",
    ]
    for record in records:
        output.extend(render_record(record))
    output.extend([
        "COMMIT;", "", "-- Post-import verification",
        "SELECT", f"    {len(records)} AS `expected_song_count`, COUNT(*) AS `actual_song_count`",
        "FROM `song_metadata`", f"WHERE `catalog_track_id` IN ({track_list});", "",
        "SELECT", f"    {segment_count} AS `expected_section_count`, COUNT(*) AS `actual_section_count`",
        "FROM `song_section` AS `ss` JOIN `song_metadata` AS `sm` ON `sm`.`song_id` = `ss`.`song_id`",
        f"WHERE `sm`.`catalog_track_id` IN ({track_list});", "",
        "SELECT", f"    {genre_relation_count} AS `expected_song_genre_count`, COUNT(*) AS `actual_song_genre_count`",
        "FROM `song_genre` AS `sg` JOIN `song_metadata` AS `sm` ON `sm`.`song_id` = `sg`.`song_id`",
        f"WHERE `sm`.`catalog_track_id` IN ({track_list});", "",
    ])
    return "\n".join(output)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: build_section_metadata_insert.py INPUT.jsonl OUTPUT.sql")
    input_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    records = load_records(input_path)
    output_path.write_text(render_sql(records), encoding="utf-8", newline="\n")
    print(f"wrote {len(records)} songs and {sum(len(r['segments']) for r in records)} sections to {output_path}")


if __name__ == "__main__":
    main()
