"""Build identity exposed by health checks, jobs, and structured logs."""

from __future__ import annotations

from functools import lru_cache
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parent


@lru_cache(maxsize=1)
def build_sha() -> str:
    configured = os.environ.get("MICON_BUILD_SHA", "").strip()
    if configured:
        return configured
    try:
        completed = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
            timeout=2,
        )
        revision = completed.stdout.strip()
        if len(revision) == 40:
            return revision
    except (OSError, subprocess.SubprocessError):
        pass
    return "unknown"
