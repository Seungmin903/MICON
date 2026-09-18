"""Read-only views of AI jobs and explicitly consented saved replay cases."""
from __future__ import annotations

import json
import re
from pathlib import Path

from adapters import matrices, profile_from_range

SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")


def read_json(path):
    if path.stat().st_size > 4_000_000:
        raise ValueError("JSON이 4 MB 제한을 초과했습니다.")
    value = json.loads(path.read_bytes())
    if not isinstance(value, dict):
        raise ValueError("JSON 객체가 필요합니다.")
    return value


def safe_reason(value):
    if not value:
        return None
    text = str(value)
    if "invalid v5 reference window" in text:
        return "음역 입력 검증 오류: invalid v5 reference window"
    text = re.sub(r"(?i)(bearer\s+|(?:token|password|secret)\s*[=:]\s*)[^\s,]+", r"\1[redacted]", text)
    text = re.sub(r"[A-Za-z]:[\\/][^\n'\"]+", "[server path]", text)
    # Do not expose full Pydantic inputs, which can contain voice vectors.
    text = re.sub(r"input_value=.*", "input_value=[redacted]", text)
    return text[:700]


class Repository:
    def __init__(self, analysis=None, recommendations=None, cases=None):
        self.analysis = Path(analysis).resolve() if analysis else None
        self.recommendations = Path(recommendations).resolve() if recommendations else None
        self.cases = Path(cases).resolve() if cases else None

    def _within(self, root, *parts):
        path = root.joinpath(*parts).resolve()
        if not path.is_relative_to(root):
            raise ValueError("허용 디렉터리 밖에는 접근할 수 없습니다.")
        return path

    def _find(self, job_id):
        if not SAFE_ID.fullmatch(job_id):
            raise ValueError("잘못된 job-id 형식입니다.")
        for kind, root in [("analysis", self.analysis), ("recommendation", self.recommendations)]:
            if root:
                path = self._within(root, job_id, "job.json")
                if path.is_file():
                    return kind, path, read_json(path)
        raise KeyError("이 데이터 소스에서 job-id를 찾을 수 없습니다.")

    def _job_summary(self, kind, path, job):
        meta = job.get("meta") or {}
        reason = safe_reason(job.get("failureReason") or job.get("error"))
        components = []
        callback_present = False
        for component, state in (job.get("components") or {}).items():
            if not SAFE_ID.fullmatch(component):
                continue
            callback = path.parent / f"callback.{component}.json"
            payload = {}
            try:
                if callback.exists():
                    payload = read_json(callback); callback_present = True
            except (OSError, ValueError):
                pass
            components.append({"name": component, "status": state.get("status"),
                "accepted": state.get("accepted", False), "failureCode": state.get("failureCode"),
                "failureReason": safe_reason(payload.get("failureReason")),
                "callbackHttp": (state.get("callback") or {}).get("http"),
                "callbackAttempts": (state.get("callback") or {}).get("attempts")})
        if not reason:
            reason = next((c["failureReason"] for c in components if c["failureReason"]), None)
        jid = str(job.get("jobId") or job.get("id") or path.parent.name)
        state = str(job.get("status") or "UNKNOWN")
        failed = state.upper() in {"FAILED", "INSUFFICIENT_DATA"} or any(
            str(c.get("status")).upper() in {"FAILED", "INSUFFICIENT_DATA"} for c in components)
        checks = []
        # This is a metadata diagnosis, never presented as a recorded model result.
        if meta.get("protocolVersion") in {"guided-range-v5", "guided-range-v6", "guided-range-v7"}:
            for a in meta.get("attempts", []):
                if a.get("evaluationStart") is not None and a.get("referenceMidi") is not None:
                    delta = float(a["referenceMidi"]) - float(a["targetMidi"])
                    if abs(delta) > 1.000001:
                        checks.append({"attemptId": a.get("attemptId"), "targetMidi": a["targetMidi"],
                            "referenceMidi": a["referenceMidi"], "deltaSemitones": delta,
                            "clientStatus": a.get("clientStatus"),
                            "message": "현행 ±1반음 전체 검증 조건 위반 가능성"})
        replay = kind == "analysis" and meta.get("analysisType") == "TIMBRE" and all(
            (path.parent / f"callback.{name}.json").is_file() for name in ["ECAPA", "SINGER_BYOL"])
        return {"id": jid, "kind": kind, "analysisType": meta.get("analysisType"), "status": state,
            "createdAt": job.get("createdAt"), "updatedAt": job.get("updatedAt"),
            "sessionId": meta.get("sessionId"), "profileId": job.get("profileId"),
            "requestId": job.get("requestId") or meta.get("requestId"),
            "protocolVersion": meta.get("protocolVersion"), "componentStatus": job.get("componentStatus"),
            "failureCode": job.get("failureCode"), "failureReason": reason,
            "reasonSource": "recorded" if reason else "unavailable",
            "reasonUnavailable": ("실패 원문이 보존되지 않았거나 중간 파일이 만료됐습니다." if failed and not reason else None),
            "audioDeleted": bool(job.get("audioDeleted")), "callbackDetailsAvailable": callback_present,
            "components": components, "metadataChecks": checks,
            "metadataCheckNote": "메타데이터와 현행 계약 조건의 대조이며 원본 콜백의 실패 사유를 대체하지 않습니다.",
            "replayAvailable": replay, "replayReason": None if replay else (
                "추천 원본 임베딩·음역 입력은 job.json에 저장되지 않습니다. 동의받은 동일 입력 case가 필요합니다."
                if kind == "recommendation" else "TIMBRE 성공 콜백 두 개가 필요합니다. 실패·음역 전용·만료 job은 지도 재생 불가."),
            "storedRecommendations": ((job.get("result") or {}).get("recommendations") or [])}

    def job(self, job_id):
        return self._job_summary(*self._find(job_id))

    def list(self):
        records = []
        for kind, root in [("analysis", self.analysis), ("recommendation", self.recommendations)]:
            if not root or not root.is_dir():
                continue
            paths = sorted(root.glob("*/job.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:500]
            for path in paths:
                try:
                    checked = self._within(root, path.parent.name, "job.json")
                    records.append(self._job_summary(kind, checked, read_json(checked)))
                except (OSError, ValueError, KeyError, TypeError):
                    continue
        if self.cases and self.cases.is_dir():
            for path in sorted(self.cases.glob("*.json"))[:200]:
                try:
                    value = self.case("case_" + path.stem)
                    records.append({"id": "case_" + path.stem, "kind": "case", "analysisType": "SAVED",
                        "title": value.get("label", path.stem), "createdAt": value.get("capturedAt"),
                        "status": "REPLAY_READY", "replayAvailable": True, "replayReason": None,
                        "inputKind": value.get("inputKind", "saved-case")})
                except (ValueError, OSError, KeyError, TypeError):
                    continue
        return sorted(records, key=lambda r: str(r.get("createdAt") or ""), reverse=True)[:300]

    def case(self, request_id):
        if request_id.startswith("case_"):
            stem = request_id[5:]
            if not self.cases or not SAFE_ID.fullmatch(stem):
                raise ValueError("잘못된 case ID입니다.")
            value = read_json(self._within(self.cases, stem + ".json"))
            if value.get("schemaVersion") != 1:
                raise ValueError("지원하지 않는 case schemaVersion입니다.")
            if value.get("consented") is not True or not value.get("catalogFingerprint"):
                raise ValueError("저장 case에는 명시적 동의와 catalogFingerprint가 필요합니다.")
            if not 1 <= int(value.get("topK", 10)) <= 50:
                raise ValueError("case topK는 1~50 범위여야 합니다.")
            matrices(value["query"])
            value["caseId"] = request_id
            return value
        kind, path, job = self._find(request_id)
        if kind != "analysis" or (job.get("meta") or {}).get("analysisType") != "TIMBRE":
            raise ValueError("이 job에는 추천 재생용 원본 입력이 없습니다. 저장 case를 선택해 주세요.")
        query = {}
        for name, key in [("ECAPA", "ecapa"), ("SINGER_BYOL", "singer_byol")]:
            callback = read_json(path.parent / f"callback.{name}.json")
            if callback.get("status") != "COMPLETED":
                raise ValueError(f"{name} 분석이 성공하지 않았습니다.")
            query[key] = callback
        matrices(query)
        session = (job.get("meta") or {}).get("sessionId")
        matches = []
        if session is not None and self.analysis:
            for rp in self.analysis.glob("*/job.json"):
                other = read_json(self._within(self.analysis, rp.parent.name, "job.json"))
                meta = other.get("meta") or {}
                if meta.get("sessionId") == session and meta.get("analysisType") == "RANGE":
                    cp = rp.parent / "callback.RANGE.json"
                    if cp.is_file():
                        matches.append(read_json(cp))
        if len(matches) > 1:
            raise ValueError("같은 session에 RANGE 결과가 여러 개여서 자동 결합할 수 없습니다. 저장 case를 사용해 주세요.")
        return {"caseId": request_id, "query": query, "pitchProfile": profile_from_range(matches[0]) if matches else None,
                "genres": [], "topK": 10, "inputKind": "analysis-diagnostic-defaults",
                "warning": "동일 session 분석 원본 진단입니다. 실제 추천 당시 profile와 취향 입력의 동일성은 보장하지 않습니다."}
