#!/usr/bin/env python3
"""Create, distribute, upload, and verify pre-rendered ±3 semitone instrumentals."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from pathlib import Path
from typing import Any

KEYS = (-3, -2, -1, 1, 2, 3)
MIME = "audio/ogg"
DEFAULT_OBJECT_PREFIX = "catalog/정다운/tj975-v1/instrumental"
MAX_DURATION_DELTA_SECONDS = 0.050
_manifest_lock = threading.Lock()


def run(command: list[str], *, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=True, capture_output=capture)


def require_tools() -> None:
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            raise SystemExit(f"{tool} is required and must be on PATH")
    result = run(["ffmpeg", "-hide_banner", "-filters"])
    filters = result.stdout + result.stderr
    if "rubberband" not in filters:
        raise SystemExit("ffmpeg was built without the rubberband filter")


def probe(path: Path) -> dict[str, Any]:
    result = run([
        "ffprobe", "-v", "error", "-select_streams", "a:0",
        "-show_entries", "format=duration:stream=codec_name,channels,sample_rate",
        "-of", "json", str(path),
    ])
    payload = json.loads(result.stdout)
    stream = payload.get("streams", [{}])[0]
    return {
        "durationSec": float(payload["format"]["duration"]),
        "codec": stream.get("codec_name"),
        "channels": int(stream.get("channels") or 0),
        "sampleRate": int(stream.get("sample_rate") or 0),
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def md5_file(path: Path) -> str:
    digest = hashlib.md5(usedforsecurity=False)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def command_preflight(_: argparse.Namespace) -> None:
    require_tools()
    print(json.dumps({"ok": True, "keys": KEYS, "codec": "libopus", "bitrate": "128k"}))


def command_inventory(args: argparse.Namespace) -> None:
    require_tools()
    root = Path(args.source_root).resolve()
    files = sorted(root.glob("*/instrumental.opus"))
    if not files:
        raise SystemExit(f"no */instrumental.opus files below {root}")

    def inspect(path: Path) -> dict[str, Any]:
        media = probe(path)
        return {
            "songId": path.parent.name,
            "relativeSource": path.relative_to(root).as_posix(),
            "sourceBytes": path.stat().st_size,
            **media,
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        songs = list(pool.map(inspect, files))
    if len({song["songId"] for song in songs}) != len(songs):
        raise SystemExit("duplicate songId in source tree")
    write_json(Path(args.output), {"version": 1, "sourceRoot": str(root), "songs": songs})
    print(f"inventory={args.output} songs={len(songs)} durationHours={sum(x['durationSec'] for x in songs) / 3600:.2f}")


def balanced_shards(songs: list[dict[str, Any]], count: int) -> list[list[dict[str, Any]]]:
    shards: list[list[dict[str, Any]]] = [[] for _ in range(count)]
    loads = [0.0] * count
    for song in sorted(songs, key=lambda item: (-float(item["durationSec"]), item["songId"])):
        target = min(range(count), key=lambda index: (loads[index], len(shards[index]), index))
        shards[target].append(song)
        loads[target] += float(song["durationSec"])
    return shards


def command_shard(args: argparse.Namespace) -> None:
    inventory = read_json(Path(args.inventory))
    songs = inventory["songs"]
    shards = balanced_shards(songs, args.count)
    output = Path(args.output_dir)
    summary = []
    seen: set[str] = set()
    for index, songs_in_shard in enumerate(shards, 1):
        ids = {item["songId"] for item in songs_in_shard}
        if seen.intersection(ids):
            raise SystemExit("shard overlap detected")
        seen.update(ids)
        payload = {
            "version": 1,
            "shard": index,
            "shardCount": len(shards),
            "keys": KEYS,
            "songs": sorted(songs_in_shard, key=lambda item: item["songId"]),
        }
        path = output / f"shard-{index:02d}.json"
        write_json(path, payload)
        summary.append({"shard": index, "songs": len(ids), "durationHours": round(sum(x["durationSec"] for x in songs_in_shard) / 3600, 3)})
    if seen != {item["songId"] for item in songs}:
        raise SystemExit("shards do not cover inventory exactly once")
    write_json(output / "summary.json", {"songCount": len(songs), "variantCount": len(songs) * len(KEYS), "shards": summary})
    print(json.dumps(summary, ensure_ascii=False))


def command_pilot(args: argparse.Namespace) -> None:
    songs = sorted(read_json(Path(args.inventory))["songs"], key=lambda item: (float(item["durationSec"]), item["songId"]))
    if len(songs) < 3:
        raise SystemExit("pilot needs at least three songs")
    selected = [songs[0], songs[len(songs) // 2], songs[-1]]
    write_json(Path(args.output), {"version": 1, "shard": 0, "shardCount": 1, "keys": KEYS, "pilot": True, "songs": selected})
    print(json.dumps([{"songId": x["songId"], "durationSec": x["durationSec"]} for x in selected]))


def command_package(args: argparse.Namespace) -> None:
    """Stage seven self-contained source folders without duplicating a song between laptops."""
    source_root = Path(args.source_root).resolve()
    output_root = Path(args.output_dir).resolve()
    shard_files = sorted(Path(args.shards_dir).glob("shard-*.json"))
    if not shard_files:
        raise SystemExit("no shard-*.json files found")
    for shard_path in shard_files:
        shard = read_json(shard_path)
        package = output_root / f"laptop-{int(shard['shard']):02d}"
        inputs = package / "input"
        package.mkdir(parents=True, exist_ok=True)
        shutil.copy2(shard_path, package / "shard.json")
        for name in ("pipeline.py", "requirements.txt", "README.md"):
            shutil.copy2(Path(__file__).with_name(name), package / name)
        for song in shard["songs"]:
            source = source_root / song["relativeSource"]
            target = inputs / song["relativeSource"]
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                continue
            if args.hardlink:
                os.link(source, target)
            else:
                shutil.copy2(source, target)
        write_json(package / "package.json", {
            "shard": shard["shard"], "songs": len(shard["songs"]), "variants": len(shard["songs"]) * len(KEYS),
            "sourceBytes": sum(int(song["sourceBytes"]) for song in shard["songs"]),
        })
        print(f"staged {package}")


def key_suffix(key: int) -> str:
    return f"{key:+d}"


def render_one(song: dict[str, Any], key: int, source_root: Path, output_root: Path, prefix: str, shard: int) -> dict[str, Any]:
    source = source_root / song["relativeSource"]
    output = output_root / song["songId"] / f"instrumental_key{key_suffix(key)}.opus"
    output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    needs_render = True
    if output.exists():
        existing = probe(output)
        existing_delta_ms = round(abs(float(existing["durationSec"]) - float(song["durationSec"])) * 1000)
        needs_render = existing["codec"] != "opus" or output.stat().st_size == 0 or existing_delta_ms > 50
    if needs_render:
        ratio = 2 ** (key / 12)
        with tempfile.NamedTemporaryFile(prefix=output.stem + ".", suffix=".partial.opus", dir=output.parent, delete=False) as handle:
            temporary = Path(handle.name)
        try:
            run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
                "-map", "0:a:0",
                "-filter:a", (
                    f"rubberband=pitch={ratio:.12f},"
                    f"apad=pad_dur=1,atrim=duration={float(song['durationSec']):.6f}"
                ),
                "-c:a", "libopus", "-b:a", "128k", "-vbr", "on", "-compression_level", "10",
                "-application", "audio", str(temporary),
            ])
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)
    media = probe(output)
    delta_ms = round(abs(float(media["durationSec"]) - float(song["durationSec"])) * 1000)
    if media["codec"] != "opus" or output.stat().st_size == 0 or delta_ms > 50:
        raise RuntimeError(f"invalid output codec={media['codec']} bytes={output.stat().st_size} durationDeltaMs={delta_ms}")
    prefix = prefix.strip("/")
    object_key = f"{prefix}/{song['songId']}/instrumental_key{key_suffix(key)}.opus"
    return {
        "status": "ok", "shard": shard, "songId": song["songId"], "semitones": key,
        "relativeOutput": output.relative_to(output_root).as_posix(), "objectKey": object_key,
        "mimeType": MIME, "bytes": output.stat().st_size, "sha256": sha256_file(output), "md5": md5_file(output),
        "sourceDurationMs": round(float(song["durationSec"]) * 1000), "durationMs": round(float(media["durationSec"]) * 1000),
        "durationDeltaMs": delta_ms, "channels": media["channels"], "sampleRate": media["sampleRate"],
        "elapsedSec": round(time.monotonic() - started, 3),
    }


def load_completed(path: Path) -> dict[tuple[str, int], dict[str, Any]]:
    completed: dict[tuple[str, int], dict[str, Any]] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            item = json.loads(line)
            if item.get("status") == "ok":
                completed[(item["songId"], int(item["semitones"]))] = item
    return completed


def command_render(args: argparse.Namespace) -> None:
    require_tools()
    shard = read_json(Path(args.shard))
    source_root = Path(args.source_root).resolve()
    output_root = Path(args.output_root).resolve()
    manifest = Path(args.manifest)
    completed = load_completed(manifest)
    tasks = [(song, key) for song in shard["songs"] for key in KEYS if (song["songId"], key) not in completed]

    def work(task: tuple[dict[str, Any], int]) -> dict[str, Any]:
        song, key = task
        try:
            return render_one(song, key, source_root, output_root, args.object_prefix, int(shard["shard"]))
        except Exception as error:
            return {"status": "error", "shard": shard["shard"], "songId": song["songId"], "semitones": key, "error": str(error)}

    manifest.parent.mkdir(parents=True, exist_ok=True)
    with manifest.open("a", encoding="utf-8") as handle, concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for item in pool.map(work, tasks):
            with _manifest_lock:
                handle.write(json.dumps(item, ensure_ascii=False) + "\n")
                handle.flush()
            print(json.dumps(item, ensure_ascii=False), flush=True)
    final = load_completed(manifest)
    expected = len(shard["songs"]) * len(KEYS)
    if len(final) != expected:
        raise SystemExit(f"render incomplete: {len(final)}/{expected}; rerun the same command to retry failures")


def command_merge(args: argparse.Namespace) -> None:
    inventory = read_json(Path(args.inventory))
    expected_songs = {song["songId"] for song in inventory["songs"]}
    rows: dict[tuple[str, int], dict[str, Any]] = {}
    conflicts = []
    for pattern in args.manifests:
        for filename in glob.glob(pattern):
            for line in Path(filename).read_text(encoding="utf-8").splitlines():
                row = json.loads(line)
                if row.get("status") != "ok":
                    continue
                key = (row["songId"], int(row["semitones"]))
                if key in rows and rows[key]["sha256"] != row["sha256"]:
                    conflicts.append(key)
                rows[key] = row
    expected = {(song_id, key) for song_id in expected_songs for key in KEYS}
    missing = sorted(expected - set(rows))
    extra = sorted(set(rows) - expected)
    report = {
        "ok": not missing and not extra and not conflicts,
        "songs": len(expected_songs), "expectedVariants": len(expected), "actualVariants": len(rows),
        "missing": missing[:100], "extra": extra[:100], "conflicts": conflicts[:100],
        "maxDurationDeltaMs": max((row["durationDeltaMs"] for row in rows.values()), default=None),
        "totalBytes": sum(row["bytes"] for row in rows.values()),
    }
    write_json(Path(args.output), {"version": 1, "variants": sorted(rows.values(), key=lambda row: (row["songId"], row["semitones"]))})
    write_json(Path(args.report), report)
    print(json.dumps(report, ensure_ascii=False))
    if not report["ok"]:
        raise SystemExit(2)


def command_presign(args: argparse.Namespace) -> None:
    try:
        import boto3
    except ImportError as error:
        raise SystemExit("install requirements.txt before presigning") from error
    variants = read_json(Path(args.manifest))["variants"]
    client = boto3.client("s3", region_name=args.region)
    plans: dict[int, list[dict[str, Any]]] = {}
    for row in variants:
        checksum = base64.b64encode(bytes.fromhex(row["sha256"])).decode("ascii")
        params = {"Bucket": args.bucket, "Key": row["objectKey"], "ContentType": MIME,
                  "ServerSideEncryption": "AES256", "ChecksumSHA256": checksum,
                  "IfNoneMatch": "*"}
        url = client.generate_presigned_url("put_object", Params=params, ExpiresIn=args.expires)
        plans.setdefault(int(row["shard"]), []).append({
            "songId": row["songId"], "semitones": row["semitones"], "relativeOutput": row["relativeOutput"],
            "objectKey": row["objectKey"], "bytes": row["bytes"], "sha256": row["sha256"], "url": url,
            "headers": {"Content-Type": MIME, "x-amz-server-side-encryption": "AES256",
                        "x-amz-checksum-sha256": checksum, "If-None-Match": "*"},
        })
    output = Path(args.output_dir)
    for shard, uploads in plans.items():
        write_json(output / f"upload-shard-{shard:02d}.json", {"bucket": args.bucket, "expiresIn": args.expires, "uploads": uploads})
    print(f"plans={len(plans)} uploads={len(variants)}")


def command_upload(args: argparse.Namespace) -> None:
    plan = read_json(Path(args.plan))
    output_root = Path(args.output_root)
    failures = []
    for item in plan["uploads"]:
        path = output_root / item["relativeOutput"]
        try:
            if sha256_file(path) != item["sha256"] or path.stat().st_size != item["bytes"]:
                raise RuntimeError("local file no longer matches render manifest")
            request = urllib.request.Request(item["url"], data=path.read_bytes(), method="PUT", headers=item["headers"])
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                if response.status not in (200, 201, 204):
                    raise RuntimeError(f"HTTP {response.status}")
            print(f"uploaded {item['songId']} {item['semitones']:+d}")
        except Exception as error:
            failures.append({"songId": item["songId"], "semitones": item["semitones"], "error": str(error)})
    if failures:
        print(json.dumps(failures, ensure_ascii=False, indent=2), file=sys.stderr)
        raise SystemExit(2)


def command_verify_s3(args: argparse.Namespace) -> None:
    try:
        import boto3
    except ImportError as error:
        raise SystemExit("install requirements.txt before verification") from error
    variants = read_json(Path(args.manifest))["variants"]
    client = boto3.client("s3", region_name=args.region)

    def verify(row: dict[str, Any]) -> dict[str, Any] | None:
        try:
            head = client.head_object(Bucket=args.bucket, Key=row["objectKey"], ChecksumMode="ENABLED")
            expected = base64.b64encode(bytes.fromhex(row["sha256"])).decode("ascii")
            if (head["ContentLength"] != row["bytes"] or head.get("ContentType") != MIME
                    or head.get("ServerSideEncryption") != "AES256" or head.get("ChecksumSHA256") != expected):
                return {"songId": row["songId"], "semitones": row["semitones"], "reason": "metadata mismatch"}
            return None
        except Exception as error:
            return {"songId": row["songId"], "semitones": row["semitones"], "reason": str(error)}

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        failures = [item for item in pool.map(verify, variants) if item]
    report = {"ok": not failures, "checked": len(variants), "failures": failures[:100]}
    write_json(Path(args.output), report)
    print(json.dumps(report, ensure_ascii=False))
    if failures:
        raise SystemExit(2)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)
    sub.add_parser("preflight").set_defaults(func=command_preflight)

    inventory = sub.add_parser("inventory")
    inventory.add_argument("--source-root", required=True); inventory.add_argument("--output", required=True)
    inventory.add_argument("--jobs", type=int, default=max(2, min(16, (os.cpu_count() or 4))))
    inventory.set_defaults(func=command_inventory)

    shard = sub.add_parser("shard")
    shard.add_argument("--inventory", required=True); shard.add_argument("--output-dir", required=True); shard.add_argument("--count", type=int, default=7)
    shard.set_defaults(func=command_shard)
    pilot = sub.add_parser("pilot")
    pilot.add_argument("--inventory", required=True); pilot.add_argument("--output", required=True); pilot.set_defaults(func=command_pilot)
    package = sub.add_parser("package")
    package.add_argument("--shards-dir", required=True); package.add_argument("--source-root", required=True); package.add_argument("--output-dir", required=True)
    package.add_argument("--hardlink", action="store_true", help="stage quickly on the same NTFS volume; copy before sending to another laptop")
    package.set_defaults(func=command_package)

    render = sub.add_parser("render")
    render.add_argument("--shard", required=True); render.add_argument("--source-root", required=True); render.add_argument("--output-root", required=True)
    render.add_argument("--manifest", required=True); render.add_argument("--object-prefix", default=DEFAULT_OBJECT_PREFIX)
    render.add_argument("--jobs", type=int, default=max(1, min(4, (os.cpu_count() or 4) // 2)))
    render.set_defaults(func=command_render)

    merge = sub.add_parser("merge")
    merge.add_argument("--inventory", required=True); merge.add_argument("--manifests", nargs="+", required=True)
    merge.add_argument("--output", required=True); merge.add_argument("--report", required=True); merge.set_defaults(func=command_merge)

    presign = sub.add_parser("presign")
    presign.add_argument("--manifest", required=True); presign.add_argument("--bucket", required=True); presign.add_argument("--region", default="ap-northeast-2")
    presign.add_argument("--output-dir", required=True); presign.add_argument("--expires", type=int, default=43200); presign.set_defaults(func=command_presign)
    upload = sub.add_parser("upload")
    upload.add_argument("--plan", required=True); upload.add_argument("--output-root", required=True); upload.add_argument("--timeout", type=int, default=180)
    upload.set_defaults(func=command_upload)
    verify = sub.add_parser("verify-s3")
    verify.add_argument("--manifest", required=True); verify.add_argument("--bucket", required=True); verify.add_argument("--region", default="ap-northeast-2")
    verify.add_argument("--output", required=True); verify.add_argument("--jobs", type=int, default=16); verify.set_defaults(func=command_verify_s3)
    return root


if __name__ == "__main__":
    options = parser().parse_args()
    options.func(options)
