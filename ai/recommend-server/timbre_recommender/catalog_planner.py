"""Pure, resumable planning for a local vocal-recommendation catalog.

The planner deliberately stops before any model inference.  It turns an audio
directory and the compact track metadata JSONL into a deterministic allow-list
that an expensive embedding/range builder can consume later.  Every source
file remains represented in the plan, including duplicates and excluded
tracks, so filtering decisions are auditable.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from difflib import SequenceMatcher
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
import unicodedata
from typing import Any, Iterable, Mapping, Sequence


PLAN_SCHEMA_VERSION = 1
HASH_CACHE_SCHEMA_VERSION = 1
ARTIST_KINDS = frozenset(
    {"person", "group", "duo", "trot", "virtual", "unknown", "ambiguous"}
)
POLICY_DECISIONS = frozenset({"include", "exclude", "review"})
COMPACT_METADATA_FIELDS = frozenset(
    {
        "title",
        "artists",
        "album_name",
        "release_date",
        "display_image_url",
        "image_type",
        "credit_relations",
        "languages",
        "lyrics",
        "normalized_genres",
    }
)

_BRACKETS = re.compile(
    r"\([^()]*\)|\[[^\[\]]*\]|\{[^{}]*\}|\uff08[^\uff08\uff09]*\uff09|\u3010[^\u3010\u3011]*\u3011"
)
_BRACKET_CONTENTS = re.compile(
    r"\(([^()]*)\)|\[([^\[\]]*)\]|\{([^{}]*)\}|\uff08([^\uff08\uff09]*)\uff09|\u3010([^\u3010\u3011]*)\u3011"
)
_TJ_TAG = re.compile(r"^(TJ(?:\d+)?-\d+)_official$", re.IGNORECASE)
_ANNOTATION = re.compile(
    r"(?i)\b(?:feat(?:uring)?|ft|prod(?:uced)?|narr(?:ation|ated|ator)?)\.?\b"
)
_COLLABORATION_WORD = re.compile(
    r"(?i)\b(?:feat(?:uring)?|ft|with|duet|duo)\.?\b|(?:\ub4c0\uc5e3|\ub4c0\uc624)"
)
_COLLABORATION_SEPARATOR = re.compile(r"\s(?:x|and)\s|[&+,/\u3001\uff0b\uff06\u00d7]")
_ARTIST_SPLIT = re.compile(
    r"(?i)\b(?:feat(?:uring)?|ft|prod(?:uced)?|narr(?:ation|ated|ator)?|with)\.?(?:\s*[:.-])?\s*"
    r"|\s+(?:x|and)\s+|\s*[&+,/\u3001\uff0b\uff06\u00d7]\s*"
)


DEFAULT_TITLE_ALIASES = {
    "takemehomecountryroad": "takemehomecountryroads",
    "takemehomecountryroads": "takemehomecountryroads",
    "\u3046\u305f\u305f\u306d": "utatane",
    "utatane": "utatane",
    "\u30a4\u30f3\u30d5\u30a7\u30eb\u30ce": "inferno",
    "inferno": "inferno",
    "\ub0b4\uc0ac\ub78cpartnerforlife": "partnerforlife",
    "partnerforlife": "partnerforlife",
    "\ubc45\ubc45\ubc45": "bangbangbang",
    "bangbangbang": "bangbangbang",
    "\ub108\ub97c\ubcf4\ub0b4\uace0": "\ub108\ub97c\ubcf4\ub0b4\uace0ii",
    "\ub108\ub97c\ubcf4\ub0b4\uace0ii": "\ub108\ub97c\ubcf4\ub0b4\uace0ii",
    "\ubc84\uc2a4\uc548\uc5d0\uc11c": "\uc790\uc790\ubc84\uc2a4\uc548\uc5d0\uc11c",
    "\uc790\uc790\ubc84\uc2a4\uc548\uc5d0\uc11c": "\uc790\uc790\ubc84\uc2a4\uc548\uc5d0\uc11c",
    "\uace1\uc608\uc0ac": "\uace1\uc608\uc0ac2",
    "\uace1\uc608\uc0ac2": "\uace1\uc608\uc0ac2",
}

# Explicit transliteration/stage-name equivalents observed in the source catalog.
# These are intentionally narrow: a title-only match must not silently turn an
# unrelated artist into an included recommendation candidate.
DEFAULT_ARTIST_EQUIVALENCES = {
    "mcthemax": "mcthemax",
    "\uc5e0\uc528\ub354\ub9e5\uc2a4": "mcthemax",
    "thenuts": "thenuts",
    "\ub354\ub11b\uce20": "thenuts",
    "tim": "tim",
    "\ud300": "tim",
    "yb": "yb",
    "\uc724\ub3c4\ud604\ubc34\ub4dc": "yb",
}


@dataclass(frozen=True)
class ParsedAudioFilename:
    path: Path
    artist: str
    tj_code: str
    title: str


@dataclass(frozen=True)
class ArtistCredit:
    name: str
    role: str

    def to_dict(self) -> dict[str, str]:
        return {"name": self.name, "role": self.role}


@dataclass(frozen=True)
class MetadataTrack:
    source_index: int
    title: str
    artists: tuple[ArtistCredit, ...]
    album_name: str | None
    release_date: str | None
    display_image_url: str | None
    image_type: str | None
    languages: str | None
    normalized_genres: tuple[str, ...]

    @property
    def primary_artist_names(self) -> tuple[str, ...]:
        primary = tuple(item.name for item in self.artists if item.role == "primary")
        return primary or tuple(item.name for item in self.artists)

    @property
    def display_artist(self) -> str:
        return " / ".join(self.primary_artist_names)

    def to_public_dict(self) -> dict[str, Any]:
        """Return only fields useful to the recommender; lyrics never escape."""

        return {
            "source_index": self.source_index,
            "title": self.title,
            "artists": [item.to_dict() for item in self.artists],
            "artist": self.display_artist,
            "album_name": self.album_name,
            "release_date": self.release_date,
            "display_image_url": self.display_image_url,
            "image_type": self.image_type,
            "languages": self.languages,
            "normalized_genres": list(self.normalized_genres),
        }


@dataclass(frozen=True)
class CatalogPlan:
    metadata_source: str
    metadata_sha256: str
    records: tuple[dict[str, Any], ...]
    unmatched_metadata: tuple[dict[str, Any], ...]
    summary: dict[str, int]

    @property
    def selected_records(self) -> tuple[dict[str, Any], ...]:
        return tuple(item for item in self.records if item["decision"] == "include")

    @property
    def excluded_records(self) -> tuple[dict[str, Any], ...]:
        return tuple(item for item in self.records if item["decision"] == "exclude")

    @property
    def review_records(self) -> tuple[dict[str, Any], ...]:
        return tuple(item for item in self.records if item["decision"] == "review")

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": PLAN_SCHEMA_VERSION,
            "metadata_source": self.metadata_source,
            "metadata_sha256": self.metadata_sha256,
            "summary": self.summary,
            "records": list(self.records),
            "unmatched_metadata": list(self.unmatched_metadata),
        }


def normalize_text(value: str) -> str:
    """NFKC/case-fold text and retain only Unicode alphanumeric characters."""

    normalized = unicodedata.normalize("NFKC", value).casefold()
    return "".join(character for character in normalized if character.isalnum())


def strip_brackets(value: str) -> str:
    """Remove nested bracket groups by repeatedly stripping innermost groups."""

    previous = value
    while True:
        current = _BRACKETS.sub("", previous)
        if current == previous:
            return current
        previous = current


def _normalized_title_aliases(
    aliases: Mapping[str, str] | None,
) -> dict[str, str]:
    result = {
        normalize_text(source): normalize_text(destination)
        for source, destination in DEFAULT_TITLE_ALIASES.items()
    }
    if aliases:
        result.update(
            {
                normalize_text(source): normalize_text(destination)
                for source, destination in aliases.items()
            }
        )
    return result


def title_keys(value: str, aliases: Mapping[str, str] | None = None) -> tuple[str, ...]:
    alias_map = _normalized_title_aliases(aliases)
    values = {normalize_text(value), normalize_text(strip_brackets(value))}
    return tuple(sorted({alias_map.get(item, item) for item in values if item}))


def _bracket_contents(value: str) -> Iterable[str]:
    for match in _BRACKET_CONTENTS.finditer(value):
        content = next((item for item in match.groups() if item is not None), "")
        if content and not _ANNOTATION.search(content) and "ost" not in content.casefold():
            yield content


def artist_aliases(value: str) -> tuple[str, ...]:
    """Create aliases used to compare decorated karaoke artist strings."""

    candidates = {value, strip_brackets(value)}
    candidates.update(_bracket_contents(value))
    expanded = set(candidates)
    for candidate in candidates:
        expanded.update(_ARTIST_SPLIT.split(candidate))
    return tuple(sorted({normalize_text(item) for item in expanded if normalize_text(item)}))


def _artist_match_aliases(value: str) -> tuple[str, ...]:
    return tuple(
        sorted(
            {
                DEFAULT_ARTIST_EQUIVALENCES.get(alias, alias)
                for alias in artist_aliases(value)
            }
        )
    )


def artist_similarity(left: str, right: str) -> tuple[float, str, str]:
    """Return the best alias score and the alias pair producing that score."""

    best = (0.0, "", "")
    for left_alias in _artist_match_aliases(left):
        for right_alias in _artist_match_aliases(right):
            if left_alias == right_alias:
                score = 1.0
            elif min(len(left_alias), len(right_alias)) >= 3 and (
                left_alias in right_alias or right_alias in left_alias
            ):
                score = 0.94
            else:
                score = SequenceMatcher(None, left_alias, right_alias).ratio()
            candidate = (score, left_alias, right_alias)
            if candidate > best:
                best = candidate
    return best


def parse_audio_filename(path: Path | str) -> ParsedAudioFilename:
    source = Path(path)
    if source.suffix.casefold() != ".mp3":
        raise ValueError(f"audio filename must end in .mp3: {source.name}")
    parts = source.stem.split("__", 2)
    if len(parts) != 3 or not parts[0] or not parts[2]:
        raise ValueError(
            "audio filename must match artist__TJ..._official__title.mp3: "
            f"{source.name}"
        )
    match = _TJ_TAG.fullmatch(parts[1])
    if match is None:
        raise ValueError(
            "audio filename must match artist__TJ..._official__title.mp3: "
            f"{source.name}"
        )
    return ParsedAudioFilename(
        path=source,
        artist=parts[0].strip(),
        tj_code=match.group(1).upper(),
        title=parts[2].strip(),
    )


def _optional_string(payload: Mapping[str, Any], key: str, *, line: int) -> str | None:
    value = payload[key]
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"metadata line {line}: {key} must be a string or null")
    return value


def load_metadata_jsonl(path: Path | str, *, strict_fields: bool = True) -> list[MetadataTrack]:
    """Load compact metadata while intentionally discarding its lyrics field."""

    source = Path(path)
    records: list[MetadataTrack] = []
    with source.open("r", encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"metadata line {line_number}: invalid JSON") from error
            if not isinstance(payload, dict):
                raise ValueError(f"metadata line {line_number}: record must be an object")
            fields = set(payload)
            missing = COMPACT_METADATA_FIELDS - fields
            extra = fields - COMPACT_METADATA_FIELDS
            if missing or (strict_fields and extra):
                details = []
                if missing:
                    details.append(f"missing={sorted(missing)}")
                if strict_fields and extra:
                    details.append(f"extra={sorted(extra)}")
                raise ValueError(f"metadata line {line_number}: {'; '.join(details)}")
            title = payload["title"]
            artists_payload = payload["artists"]
            genres_payload = payload["normalized_genres"]
            if not isinstance(title, str) or not title.strip():
                raise ValueError(f"metadata line {line_number}: title must be non-empty")
            if not isinstance(artists_payload, list) or not artists_payload:
                raise ValueError(f"metadata line {line_number}: artists must be non-empty")
            artists: list[ArtistCredit] = []
            for artist in artists_payload:
                if not isinstance(artist, dict) or not isinstance(artist.get("name"), str):
                    raise ValueError(f"metadata line {line_number}: invalid artist")
                role = artist.get("role", "primary")
                if not isinstance(role, str):
                    raise ValueError(f"metadata line {line_number}: invalid artist role")
                artists.append(ArtistCredit(name=artist["name"].strip(), role=role.casefold()))
            if not isinstance(genres_payload, list) or not all(
                isinstance(item, str) for item in genres_payload
            ):
                raise ValueError(
                    f"metadata line {line_number}: normalized_genres must be a string list"
                )
            records.append(
                MetadataTrack(
                    source_index=len(records),
                    title=title.strip(),
                    artists=tuple(artists),
                    album_name=_optional_string(payload, "album_name", line=line_number),
                    release_date=_optional_string(payload, "release_date", line=line_number),
                    display_image_url=_optional_string(
                        payload, "display_image_url", line=line_number
                    ),
                    image_type=_optional_string(payload, "image_type", line=line_number),
                    languages=_optional_string(payload, "languages", line=line_number),
                    normalized_genres=tuple(item.strip() for item in genres_payload),
                )
            )
    if not records:
        raise ValueError(f"metadata contains no records: {source}")
    return records


def sha256_file(path: Path | str) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _atomic_text_write(path: Path, writer: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as stream:
            temporary_name = stream.name
            writer(stream)
            stream.flush()
            os.fsync(stream.fileno())
        for attempt in range(8):
            try:
                os.replace(temporary_name, path)
                temporary_name = None
                break
            except PermissionError:
                if attempt == 7:
                    raise
                time.sleep(0.025 * (attempt + 1))
    finally:
        if temporary_name is not None:
            temporary = Path(temporary_name)
            if temporary.exists():
                temporary.unlink()


def atomic_write_json(path: Path | str, payload: Any) -> Path:
    destination = Path(path)

    def write(stream: Any) -> None:
        json.dump(payload, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")

    _atomic_text_write(destination, write)
    return destination


def atomic_write_jsonl(path: Path | str, rows: Iterable[Mapping[str, Any]]) -> Path:
    destination = Path(path)

    def write(stream: Any) -> None:
        for row in rows:
            stream.write(json.dumps(dict(row), ensure_ascii=False, sort_keys=True) + "\n")

    _atomic_text_write(destination, write)
    return destination


def atomic_write_csv(
    path: Path | str,
    rows: Iterable[Mapping[str, Any]],
    fieldnames: Sequence[str],
) -> Path:
    destination = Path(path)

    def write(stream: Any) -> None:
        csv_writer = csv.DictWriter(stream, fieldnames=fieldnames, extrasaction="ignore")
        csv_writer.writeheader()
        csv_writer.writerows(rows)

    _atomic_text_write(destination, write)
    return destination


def _load_hash_cache(path: Path) -> dict[str, dict[str, Any]]:
    if not path.is_file():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schema_version") != HASH_CACHE_SCHEMA_VERSION:
        return {}
    entries = payload.get("files")
    return entries if isinstance(entries, dict) else {}


def fingerprint_audio_files(
    paths: Iterable[Path | str],
    *,
    cache_path: Path | str | None = None,
) -> dict[Path, dict[str, Any]]:
    """Hash files with an atomic size/mtime cache checkpoint after every miss."""

    resolved = sorted({Path(item).resolve() for item in paths}, key=lambda item: str(item).casefold())
    cache_file = Path(cache_path).resolve() if cache_path is not None else None
    cache = _load_hash_cache(cache_file) if cache_file is not None else {}
    fingerprints: dict[Path, dict[str, Any]] = {}
    for source in resolved:
        stat = source.stat()
        key = str(source)
        cached = cache.get(key, {})
        if (
            cached.get("size") == stat.st_size
            and cached.get("mtime_ns") == stat.st_mtime_ns
            and isinstance(cached.get("sha256"), str)
            and len(cached["sha256"]) == 64
        ):
            checksum = cached["sha256"]
        else:
            checksum = sha256_file(source)
            cache[key] = {
                "size": stat.st_size,
                "mtime_ns": stat.st_mtime_ns,
                "sha256": checksum,
            }
            if cache_file is not None:
                atomic_write_json(
                    cache_file,
                    {"schema_version": HASH_CACHE_SCHEMA_VERSION, "files": cache},
                )
        fingerprints[source] = {
            "size": stat.st_size,
            "mtime_ns": stat.st_mtime_ns,
            "sha256": checksum,
        }
    return fingerprints


def _audio_paths(source: Path | str | Iterable[Path | str]) -> list[Path]:
    if isinstance(source, (str, Path)):
        root = Path(source)
        if root.is_dir():
            candidates = root.rglob("*.mp3")
        else:
            candidates = (root,)
    else:
        candidates = (Path(item) for item in source)
    paths = sorted({item.resolve() for item in candidates}, key=lambda item: str(item).casefold())
    if not paths:
        raise ValueError("no MP3 files found")
    for path in paths:
        if not path.is_file():
            raise FileNotFoundError(path)
    return paths


def _policy_sections(
    policy: Mapping[str, Any],
) -> tuple[Mapping[str, Any], Mapping[str, Any]]:
    artists = policy.get("artists")
    if isinstance(artists, Mapping):
        overrides = policy.get("track_overrides")
        return artists, overrides if isinstance(overrides, Mapping) else {}
    return policy, {}


def _normalize_artist_policy(policy: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    artist_rows, _ = _policy_sections(policy)
    normalized: dict[str, dict[str, Any]] = {}
    for artist, raw_entry in artist_rows.items():
        if isinstance(raw_entry, Mapping):
            raw_kind = raw_entry.get("entity_type", raw_entry.get("type"))
            decision = str(raw_entry.get("decision", "")).strip().casefold()
            reasons = [str(value) for value in raw_entry.get("reason_codes", [])]
            confidence = raw_entry.get("confidence")
        else:
            raw_kind = raw_entry
            decision = ""
            reasons = []
            confidence = None
        kind = str(raw_kind).strip().casefold()
        if kind not in ARTIST_KINDS:
            raise ValueError(
                f"invalid artist policy for {artist!r}: {kind!r}; "
                f"expected one of {sorted(ARTIST_KINDS)}"
            )
        key = normalize_text(str(artist))
        if not key:
            raise ValueError("artist policy contains an empty artist")
        if not decision:
            decision = "include" if kind == "person" else "review" if kind in {"ambiguous", "unknown", "virtual"} else "exclude"
        if decision not in POLICY_DECISIONS:
            raise ValueError(f"invalid artist decision for {artist!r}: {decision!r}")
        entry = {
            "entity_type": kind,
            "decision": decision,
            "reason_codes": reasons,
            "confidence": confidence,
        }
        previous = normalized.get(key)
        if previous is not None and previous != entry:
            raise ValueError(f"conflicting artist policy for {artist!r}")
        normalized[key] = entry
    return normalized


def _policy_kinds(
    audio_artist: str,
    metadata: MetadataTrack | None,
    policy: Mapping[str, Mapping[str, Any]],
) -> tuple[str, ...]:
    aliases = set(artist_aliases(audio_artist))
    if metadata is not None:
        for name in metadata.primary_artist_names:
            aliases.update(artist_aliases(name))
    return tuple(
        sorted(
            {
                str(policy[alias]["entity_type"])
                for alias in aliases
                if alias in policy
            }
        )
    )


def _folder_policy_entry(
    audio_artist: str, policy: Mapping[str, Mapping[str, Any]]
) -> dict[str, Any] | None:
    exact = policy.get(normalize_text(audio_artist))
    return dict(exact) if exact is not None else None


def _track_override(
    artist: str, title: str, policy: Mapping[str, Any]
) -> dict[str, Any] | None:
    _, overrides = _policy_sections(policy)
    exact = overrides.get(f"{artist}||{title}")
    if isinstance(exact, Mapping):
        return dict(exact)
    target_artist = normalize_text(artist)
    target_title = normalize_text(title)
    for key, value in overrides.items():
        if not isinstance(value, Mapping) or "||" not in str(key):
            continue
        candidate_artist, candidate_title = str(key).split("||", 1)
        if (
            normalize_text(candidate_artist) == target_artist
            and normalize_text(candidate_title) == target_title
        ):
            return dict(value)
    return None


def _collaboration_signals(
    audio_artist: str,
    metadata: MetadataTrack | None,
) -> tuple[str, ...]:
    signals: set[str] = set()
    if _COLLABORATION_WORD.search(audio_artist):
        signals.add("collaboration:feature_or_duet_word")
    if _COLLABORATION_SEPARATOR.search(audio_artist):
        signals.add("collaboration:artist_separator")
    if metadata is not None:
        primary = [item for item in metadata.artists if item.role == "primary"]
        if len(primary) > 1:
            signals.add("collaboration:multiple_primary_artists")
        if len(metadata.artists) > 1:
            signals.add("collaboration:multiple_artist_credits")
        if any(item.role in {"featured", "feature", "featuring", "guest"} for item in metadata.artists):
            signals.add("collaboration:featured_artist_credit")
    return tuple(sorted(signals))


def _track_id(parsed: ParsedAudioFilename, checksum: str) -> str:
    tj = re.sub(r"[^a-z0-9]+", "-", parsed.tj_code.casefold()).strip("-")
    return f"{tj}-{checksum[:12]}"


def _match_metadata(
    audios: Sequence[dict[str, Any]],
    metadata: Sequence[MetadataTrack],
    *,
    title_aliases: Mapping[str, str] | None,
    minimum_artist_score: float,
) -> tuple[dict[Path, tuple[MetadataTrack, dict[str, Any]]], set[int]]:
    metadata_title_keys = {
        item.source_index: set(title_keys(item.title, title_aliases)) for item in metadata
    }
    candidates: list[tuple[float, str, int, Path, dict[str, Any]]] = []
    for audio in audios:
        parsed: ParsedAudioFilename = audio["parsed"]
        audio_keys = set(title_keys(parsed.title, title_aliases))
        title_candidates = [
            item
            for item in metadata
            if audio_keys & metadata_title_keys[item.source_index]
        ]
        one_metadata_artist = len(
            {normalize_text(item.display_artist) for item in title_candidates}
        ) == 1
        for item in title_candidates:
            shared_keys = audio_keys & metadata_title_keys[item.source_index]
            score, audio_alias, metadata_alias = artist_similarity(
                parsed.artist, item.display_artist
            )
            if score < minimum_artist_score and not one_metadata_artist:
                continue
            detail = {
                "title_key": sorted(shared_keys)[0],
                "artist_score": round(score, 6),
                "audio_artist_alias": audio_alias,
                "metadata_artist_alias": metadata_alias,
                "match_method": (
                    "title_artist" if score >= minimum_artist_score else "unique_title_fallback"
                ),
            }
            candidates.append(
                (-score, str(parsed.path).casefold(), item.source_index, parsed.path, detail)
            )
    assigned_audio: set[Path] = set()
    assigned_metadata: set[int] = set()
    matches: dict[Path, tuple[MetadataTrack, dict[str, Any]]] = {}
    metadata_by_index = {item.source_index: item for item in metadata}
    for _, _, metadata_index, audio_path, detail in sorted(candidates):
        if audio_path in assigned_audio or metadata_index in assigned_metadata:
            continue
        assigned_audio.add(audio_path)
        assigned_metadata.add(metadata_index)
        matches[audio_path] = (metadata_by_index[metadata_index], detail)
    return matches, assigned_metadata


def build_catalog_plan(
    audio_source: Path | str | Iterable[Path | str],
    metadata_jsonl: Path | str,
    artist_policy: Mapping[str, Any],
    *,
    title_aliases: Mapping[str, str] | None = None,
    hash_cache_path: Path | str | None = None,
    minimum_artist_score: float = 0.35,
) -> CatalogPlan:
    """Create a deterministic, conservative processing plan.

    Only artists explicitly classified as ``person`` can be included.  Missing
    or ambiguous policy, groups/duos, Trot, collaborations, duplicate audio,
    and metadata mismatches are all retained as excluded audit records.
    """

    if not 0.0 <= minimum_artist_score <= 1.0:
        raise ValueError("minimum_artist_score must be between zero and one")
    audio_paths = _audio_paths(audio_source)
    metadata_path = Path(metadata_jsonl).resolve()
    metadata_sha256 = sha256_file(metadata_path)
    policy_source = artist_policy.get("source")
    if isinstance(policy_source, Mapping):
        expected_metadata_sha256 = str(
            policy_source.get("metadata_sha256", "")
        ).strip().casefold()
        if expected_metadata_sha256 and metadata_sha256 != expected_metadata_sha256:
            raise RuntimeError(
                "metadata SHA-256 does not match artist policy: "
                f"expected {expected_metadata_sha256}, got {metadata_sha256}"
            )
    metadata = load_metadata_jsonl(metadata_path)
    normalized_policy = _normalize_artist_policy(artist_policy)
    fingerprints = fingerprint_audio_files(audio_paths, cache_path=hash_cache_path)

    audio_items: list[dict[str, Any]] = []
    for path in audio_paths:
        parsed = parse_audio_filename(path)
        fingerprint = fingerprints[path]
        audio_items.append(
            {
                "parsed": parsed,
                "fingerprint": fingerprint,
            }
        )
    by_checksum: dict[str, list[dict[str, Any]]] = {}
    for item in audio_items:
        by_checksum.setdefault(item["fingerprint"]["sha256"], []).append(item)
    for group in by_checksum.values():
        canonical_item = sorted(
            group,
            key=lambda item: (
                tuple(int(value) for value in re.findall(r"\d+", item["parsed"].tj_code)),
                str(item["parsed"].path).casefold(),
            ),
        )[0]
        for item in group:
            item["canonical"] = canonical_item["parsed"].path

    matches, assigned_metadata = _match_metadata(
        audio_items,
        metadata,
        title_aliases=title_aliases,
        minimum_artist_score=minimum_artist_score,
    )

    unique_items = [item for item in audio_items if item["parsed"].path == item["canonical"]]
    records: list[dict[str, Any]] = []
    duplicate_count = 0
    unmatched_count = 0
    for item in audio_items:
        parsed: ParsedAudioFilename = item["parsed"]
        fingerprint = item["fingerprint"]
        canonical: Path = item["canonical"]
        is_duplicate = parsed.path != canonical
        match = matches.get(parsed.path)
        matched_metadata = match[0] if match is not None else None
        match_detail = match[1] if match is not None else None
        structural_reasons: list[str] = []
        automatic_reasons: list[str] = []
        automatic_review_reasons: list[str] = []
        if is_duplicate:
            duplicate_count += 1
            structural_reasons.append("duplicate_audio")
        if matched_metadata is None:
            unmatched_count += 1
            structural_reasons.append("metadata_unmatched")
        if matched_metadata is not None and any(
            normalize_text(genre) == "trot" for genre in matched_metadata.normalized_genres
        ):
            automatic_reasons.append("genre:trot")
        kinds = _policy_kinds(parsed.artist, matched_metadata, normalized_policy)
        folder_policy = _folder_policy_entry(parsed.artist, normalized_policy)
        if folder_policy is None:
            automatic_review_reasons.append("artist_policy:missing")
        elif folder_policy["decision"] == "exclude":
            automatic_reasons.extend(
                f"artist_policy:{value}" for value in folder_policy["reason_codes"]
            )
            if not folder_policy["reason_codes"]:
                automatic_reasons.append(f"artist_policy:{folder_policy['entity_type']}")
        elif folder_policy["decision"] == "review":
            automatic_review_reasons.extend(
                f"artist_policy:{value}" for value in folder_policy["reason_codes"]
            )
            if not folder_policy["reason_codes"]:
                automatic_review_reasons.append(
                    f"artist_policy:{folder_policy['entity_type']}"
                )
        if (
            match_detail is not None
            and match_detail.get("match_method") == "unique_title_fallback"
            and folder_policy is not None
            and folder_policy["decision"] == "include"
        ):
            automatic_review_reasons.append("metadata_match:low_artist_confidence")
        collaboration = _collaboration_signals(parsed.artist, matched_metadata)
        if collaboration:
            automatic_reasons.extend(collaboration)
        reasons = structural_reasons + automatic_reasons
        review_reasons = automatic_review_reasons
        override = _track_override(parsed.artist, parsed.title, artist_policy)
        if override is not None:
            override_decision = str(override.get("decision", "review")).casefold()
            override_reasons = [
                f"track_policy:{value}" for value in override.get("reason_codes", [])
            ] or [f"track_policy:{override.get('trot_status', 'override')}"]
            if override_decision == "exclude":
                reasons = structural_reasons + override_reasons
                review_reasons = []
            elif override_decision == "review":
                reasons = list(structural_reasons)
                review_reasons = override_reasons
            elif override_decision == "include":
                reasons = list(structural_reasons)
                review_reasons = []
            elif override_decision != "include":
                raise ValueError(
                    f"invalid track override decision for {parsed.artist}||{parsed.title}"
                )
        reasons = sorted(set(reasons))
        review_reasons = sorted(set(review_reasons))
        decision = "exclude" if reasons else "review" if review_reasons else "include"

        checksum = fingerprint["sha256"]
        source_id = hashlib.sha256(str(parsed.path).encode("utf-8")).hexdigest()[:16]
        aliases = {
            "filename_artist": parsed.artist,
            "filename_title": parsed.title,
            "artist_keys": list(artist_aliases(parsed.artist)),
            "title_keys": list(title_keys(parsed.title, title_aliases)),
        }
        if matched_metadata is not None:
            aliases["metadata_artist_keys"] = list(
                artist_aliases(matched_metadata.display_artist)
            )
            aliases["metadata_title_keys"] = list(
                title_keys(matched_metadata.title, title_aliases)
            )

        record = {
            "source_id": source_id,
            "track_id": _track_id(parsed, checksum),
            "decision": decision,
            "exclusion_reasons": reasons,
            "review_reasons": review_reasons,
            "automatic_exclusion_reasons": sorted(set(automatic_reasons)),
            "automatic_review_reasons": sorted(set(automatic_review_reasons)),
            "duplicate_of": (
                _track_id(parse_audio_filename(canonical), checksum) if is_duplicate else None
            ),
            "source": {
                "path": str(parsed.path),
                "size": fingerprint["size"],
                "mtime_ns": fingerprint["mtime_ns"],
                "sha256": checksum,
                "tj_code": parsed.tj_code,
            },
            "aliases": aliases,
            "artist_policy_kinds": list(kinds),
            "artist_policy": folder_policy,
            "track_policy": override,
            "match": (
                {
                    "metadata": matched_metadata.to_public_dict(),
                    **(match_detail or {}),
                }
                if matched_metadata is not None
                else None
            ),
        }
        records.append(record)

    unmatched_metadata = tuple(
        item.to_public_dict() for item in metadata if item.source_index not in assigned_metadata
    )
    selected_count = sum(item["decision"] == "include" for item in records)
    review_count = sum(item["decision"] == "review" for item in records)
    summary = {
        "audio_files": len(records),
        "unique_audio": len(unique_items),
        "duplicate_audio": duplicate_count,
        "metadata_records": len(metadata),
        "matched_audio": len(records) - unmatched_count,
        "unmatched_audio": unmatched_count,
        "unmatched_metadata": len(unmatched_metadata),
        "selected": selected_count,
        "review": review_count,
        "excluded": len(records) - selected_count - review_count,
    }
    return CatalogPlan(
        metadata_source=str(metadata_path),
        metadata_sha256=metadata_sha256,
        records=tuple(records),
        unmatched_metadata=unmatched_metadata,
        summary=summary,
    )


def write_catalog_plan(plan: CatalogPlan, output_dir: Path | str) -> dict[str, Path]:
    """Atomically write the full audit plan plus builder-friendly CSV views."""

    output = Path(output_dir)
    json_path = atomic_write_json(output / "catalog_plan.json", plan.to_dict())
    jsonl_path = atomic_write_jsonl(output / "catalog_plan.jsonl", plan.records)
    builder_rows: list[dict[str, Any]] = []
    aliases_by_hash: dict[str, list[str]] = {}
    for record in plan.records:
        source = record["source"]
        aliases_by_hash.setdefault(source["sha256"], []).append(source["tj_code"])
    for record in plan.selected_records:
        match = record.get("match") or {}
        metadata = match.get("metadata") or {}
        source = record["source"]
        builder_rows.append(
            {
                "track_id": record["track_id"],
                "audio_path": source["path"],
                "audio_sha256": source["sha256"],
                "title": metadata.get("title", record["aliases"]["filename_title"]),
                "artist": metadata.get("artist", record["aliases"]["filename_artist"]),
                "decision": "include",
                "include": True,
                "metadata": {
                    "genres": metadata.get("normalized_genres", []),
                    "album_name": metadata.get("album_name"),
                    "release_date": metadata.get("release_date"),
                    "display_image_url": metadata.get("display_image_url"),
                    "image_type": metadata.get("image_type"),
                    "languages": metadata.get("languages"),
                    "tj_id": source["tj_code"],
                    "tj_aliases": sorted(set(aliases_by_hash[source["sha256"]])),
                },
            }
        )
    selection_path = atomic_write_jsonl(output / "selection.jsonl", builder_rows)
    csv_fields = (
        "track_id",
        "decision",
        "exclusion_reasons",
        "audio_path",
        "source_sha256",
        "tj_code",
        "title",
        "artist",
        "genres",
        "album_name",
        "release_date",
        "languages",
        "display_image_url",
    )

    def csv_row(record: Mapping[str, Any]) -> dict[str, Any]:
        match = record.get("match") or {}
        metadata = match.get("metadata") or {}
        source = record["source"]
        return {
            "track_id": record["track_id"],
            "decision": record["decision"],
            "exclusion_reasons": "|".join(record["exclusion_reasons"]),
            "audio_path": source["path"],
            "source_sha256": source["sha256"],
            "tj_code": source["tj_code"],
            "title": metadata.get("title", record["aliases"]["filename_title"]),
            "artist": metadata.get("artist", record["aliases"]["filename_artist"]),
            "genres": "|".join(metadata.get("normalized_genres", [])),
            "album_name": metadata.get("album_name") or "",
            "release_date": metadata.get("release_date") or "",
            "languages": metadata.get("languages") or "",
            "display_image_url": metadata.get("display_image_url") or "",
        }

    selected_path = atomic_write_csv(
        output / "catalog_selected.csv",
        (csv_row(item) for item in plan.selected_records),
        csv_fields,
    )
    excluded_path = atomic_write_csv(
        output / "catalog_excluded.csv",
        (csv_row(item) for item in plan.excluded_records),
        csv_fields,
    )
    review_path = atomic_write_csv(
        output / "catalog_review.csv",
        (csv_row(item) for item in plan.review_records),
        csv_fields,
    )
    return {
        "json": json_path,
        "jsonl": jsonl_path,
        "selection_jsonl": selection_path,
        "selected_csv": selected_path,
        "excluded_csv": excluded_path,
        "review_csv": review_path,
    }
