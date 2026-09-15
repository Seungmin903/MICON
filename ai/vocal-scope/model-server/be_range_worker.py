"""Run guided-range in its own namespace: the recommendation service has a different pitch_engine."""
from __future__ import annotations

import json
from pathlib import Path
import sys

from guided_range import analyze
from range_models import Submission


def submission_from_meta(meta):
    return Submission.model_validate({
        "sessionId": str(meta["measurementId"]), "measurementType": "guided_range",
        **{key: meta[key] for key in (
            "protocolVersion", "mode", "startMidi", "noiseRms", "durationSeconds", "stopReason",
            "lowerBoundaryStatus", "upperBoundaryStatus", "attempts", "sampling")},
        "parentAnalysisId": str(meta["parentAnalysisId"]) if meta.get("parentAnalysisId") is not None else None,
        "registerEvents": meta.get("registerEvents") or [], "keepOriginal": False,
    })


def main():
    audio, meta_file, output = (Path(value) for value in sys.argv[1:4])
    meta = json.loads(meta_file.read_text(encoding="utf-8"))
    submission = submission_from_meta(meta)
    previous = json.loads(Path(sys.argv[4]).read_text(encoding="utf-8")) if len(sys.argv) > 4 else None
    report = analyze(audio, submission, previous)
    report.update(verification="model_verified", analysisId=str(meta["analysisId"]))
    temporary = output.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    temporary.replace(output)


if __name__ == "__main__":
    main()
