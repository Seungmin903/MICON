"""Selected job's exact session: registered range and stored vs calculated artists.

SELECT only. Never guess a member's latest session, initiate callbacks,
recalculate recommendations, or return recordings/vectors/raw request payloads.
An old recommendation's mutable current profile is a separately labelled context.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import re
import subprocess
import threading
import time
from pathlib import Path

from ownership import SshMysqlLookup, numeric_id

ARTIST_JOB = re.compile(r"^artist-search-job-[0-9a-f]{32}$")


def bounded_number(value, low, high):
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (ValueError, TypeError):
        return None
    return number if math.isfinite(number) and low <= number <= high else None


def display_text(value, limit=255):
    return value[:limit] if isinstance(value, str) and not any(ord(c) < 32 for c in value) else None


def catalog_artist_ids(directory):
    """Use validated ID sidecar only to SELECT names for lab display from BE."""
    if not directory:
        return []
    root = Path(directory).resolve()
    try:
        path = root / "artist_ids.json"
        if path.stat().st_size > 4_000_000:
            return []
        value = json.loads(path.read_bytes())
        if value.get("schemaVersion") != 1 or value.get("indexSha256") != hashlib.sha256((root / "index.json").read_bytes()).hexdigest():
            return []
        ids = {numeric_id(row.get("artistId")) for row in value["tracks"]} - {None}
        return sorted(ids, key=int) if len(ids) <= 5000 else []
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        return []


def session_query(user_id, session_id, artist_ids=(), *, profile_id=None):
    user, session = numeric_id(user_id), numeric_id(session_id)
    profile = numeric_id(profile_id)
    if not user or not (session or profile):
        raise ValueError("확인된 회원과 session 번호가 필요합니다.")
    # Current mutable profile is an explicitly DIFFERENT context, never silently
    # presented as the source session of an old recommendation.
    session_selector = session or f"(SELECT p.session_id FROM user_voice_profile p WHERE p.profile_id={profile} AND p.user_id={user})"
    scope = f"s.session_id={session_selector} AND s.user_id={user}"
    queries = [f"""SELECT JSON_OBJECT('kind','session','sessionId',CAST(s.session_id AS CHAR),
      'userId',CAST(s.user_id AS CHAR),'sessionStatus',s.status,'rangeStatus',s.range_status,
      'artistSearchStatus',s.artist_search_status,'resultVersion',s.result_version,
      'measurementId',CAST(m.range_measurement_id AS CHAR),'measurementStatus',m.measurement_status,
      'qualityStatus',m.quality_status,'fullLowMidi',m.full_low_midi,'fullHighMidi',m.full_high_midi,
      'measurementSource',m.source,'ruleVersion',m.rule_version,'measuredAt',m.measured_at,
      'submittedAt',m.submitted_at) AS detail
      FROM voice_analysis_session s LEFT JOIN voice_range_measurement m ON m.session_id=s.session_id
      WHERE {scope}""",
      f"""SELECT JSON_OBJECT('kind','artistJob','componentJobId',CAST(j.component_job_id AS CHAR),
      'sessionId',CAST(s.session_id AS CHAR),'userId',CAST(s.user_id AS CHAR),'aiJobId',j.ai_job_id,
      'status',j.status,'failureCode',j.failure_code,'failureReason',j.failure_reason,
      'attemptNo',j.attempt_no,'resultVersion',j.requested_result_version,
      'catalogVersion',j.catalog_version,'scoringVersion',j.scoring_version,
      'createdAt',j.created_at,'updatedAt',j.updated_at) AS detail
      FROM voice_analysis_component_job j JOIN voice_analysis_session s ON s.session_id=j.session_id
      WHERE {scope} AND j.component_type='ARTIST_SEARCH'
      AND j.component_job_id=(SELECT MAX(x.component_job_id) FROM voice_analysis_component_job x
      WHERE x.session_id=s.session_id AND x.component_type='ARTIST_SEARCH')""",
      f"""SELECT JSON_OBJECT('kind','artistResult','sessionId',CAST(s.session_id AS CHAR),
      'userId',CAST(s.user_id AS CHAR),'componentJobId',CAST(r.component_job_id AS CHAR),
      'artistId',CAST(r.singer_id AS CHAR),'artistName',a.name,'rank',r.rank_no,
      'similarity',r.cosine_similarity,'ecapaScore',r.ecapa_score,'singerByolScore',r.singer_byol_score,
      'createdAt',r.created_at,'catalogBatchId',CAST(r.catalog_batch_id AS CHAR)) AS detail
      FROM similar_artist_result r JOIN voice_analysis_session s ON s.session_id=r.session_id
      LEFT JOIN singer a ON a.singer_id=r.singer_id WHERE {scope}"""]
    ids = sorted({numeric_id(value) for value in artist_ids} - {None}, key=int)
    if ids:
        queries.append(f"SELECT JSON_OBJECT('kind','artistLabel','artistId',CAST(a.singer_id AS CHAR),'artistName',a.name) AS detail FROM singer a WHERE a.singer_id IN ({','.join(ids)}) AND EXISTS(SELECT 1 FROM voice_analysis_session s WHERE {scope})")
    return "START TRANSACTION READ ONLY;\n" + "\nUNION ALL\n".join(queries) + ";\nCOMMIT;"


class SessionInsightsResolver:
    def __init__(self, lookup=None, artist_jobs=None, artist_ids=(), ttl=10, clock=time.monotonic):
        self.lookup = lookup
        self.artist_jobs = Path(artist_jobs).resolve() if artist_jobs else None
        self.artist_ids, self.ttl, self.clock = artist_ids, ttl, clock
        self.cache, self.lock = {}, threading.Lock()

    @classmethod
    def from_config(cls, cfg, owners):
        # Snapshot ownership alone is not permission/evidence to invent live data.
        bridge = owners.lookup
        return cls(bridge.query if isinstance(bridge, SshMysqlLookup) else None,
                   cfg.get("LAB_ARTIST_JOBS_DIR"), catalog_artist_ids(cfg.get("LAB_INDEX_DIR")))

    def _read_artist_job(self, backend_job, session, labels):
        jid = backend_job.get("aiJobId")
        if not self.artist_jobs or not isinstance(jid, str) or not ARTIST_JOB.fullmatch(jid):
            return {"lookupStatus": "not-configured" if not self.artist_jobs else "not-found"}
        path = (self.artist_jobs / jid / "job.json").resolve()
        if not path.is_relative_to(self.artist_jobs):
            return {"lookupStatus": "unavailable"}
        try:
            if path.stat().st_size > 4_000_000:
                raise ValueError("Size limit")
            job = json.loads(path.read_bytes())
            # Cross-check persisted original request; don't bind by timestamp or name.
            meta = job.get("payload") or {}
            if job.get("jobId") != jid or numeric_id(meta.get("sessionId")) != session["sessionId"]:
                return {"lookupStatus": "conflict"}
            if numeric_id(meta.get("resultVersion")) != numeric_id(backend_job.get("resultVersion")):
                return {"lookupStatus": "conflict"}
            if any(meta.get(k) != backend_job.get(k) for k in ["catalogVersion", "scoringVersion"]):
                return {"lookupStatus": "conflict"}
            callback = job.get("callback") or {}
            artists = []
            for row in (job.get("artists") or [])[:50]:
                aid = numeric_id(row.get("artistId"))
                rank = bounded_number(row.get("rank"), 1, 50)
                if rank is None or not rank.is_integer():
                    continue
                # Old null-ID records retain their historical display label, never
                # guessed into a canonical identity. New names come from singer DB.
                name = labels.get(aid) if aid else display_text(row.get("artistName"))
                artists.append({"rank": int(rank), "artistId": aid, "artistName": name,
                    "nameSource": "backend-singer" if aid and name else "historical-ai-record" if not aid and name else "unavailable",
                    "similarity": bounded_number(row.get("similarity"), -1, 1),
                    "ecapaScore": bounded_number(row.get("ecapaScore"), -1, 1),
                    "singerByolScore": bounded_number(row.get("singerByolScore"), -1, 1)})
            artists.sort(key=lambda row: row["rank"])
            rejected = isinstance(callback.get("http"), int) and 400 <= callback["http"] < 500
            return {"lookupStatus": "verified", "jobId": jid,
                "calculationStatus": job.get("calculationStatus") or job.get("status"),
                "deliveryStatus": job.get("deliveryStatus") or ("ACCEPTED" if callback.get("accepted") else "REJECTED" if rejected else "UNKNOWN"),
                "callbackHttp": callback.get("http"), "callbackAccepted": callback.get("accepted") is True,
                "deliveryFailureCode": display_text(job.get("deliveryFailureCode")),
                "artists": artists,
                "versionCurrent": numeric_id(meta.get("resultVersion")) == numeric_id(session.get("resultVersion"))}
        except FileNotFoundError:
            return {"lookupStatus": "not-found"}
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            return {"lookupStatus": "unavailable"}

    def enrich(self, row):
        user, session_id = numeric_id(row.get("userId")), numeric_id(row.get("sessionId"))
        profile_id = numeric_id(row.get("verifiedProfileId")) if row.get("kind") == "recommendation" else None
        result = {"lookupStatus": "unverified-owner"}
        if row.get("ownerStatus") != "verified" or not user or not (session_id or profile_id):
            result["reason"] = "이 job의 회원과 session 직접 연결이 확인되지 않아 음역·가수 정보를 표시하지 않습니다."
            row["voiceInsights"] = result
            return row
        if not self.lookup:
            row["voiceInsights"] = {"lookupStatus": "not-configured", "reason": "회원별 음역·가수 결과의 읽기 전용 DB 조회가 설정되지 않았습니다."}
            return row
        key = ("source-session", user, session_id) if session_id else ("current-profile", user, profile_id)
        with self.lock:
            now = self.clock()
            if key not in self.cache or now - self.cache[key][0] >= self.ttl:
                try:
                    records = self.lookup(session_query(user, session_id, self.artist_ids, profile_id=profile_id))
                    if not isinstance(records, list):
                        raise ValueError("Record shape")
                    session_rows = [r for r in records if r.get("kind") == "session"]
                    if len(session_rows) != 1:
                        value = {"lookupStatus": "not-found" if not session_rows else "conflict", "reason": "이 회원의 해당 session을 확인하지 못했습니다."}
                    elif not numeric_id(session_rows[0].get("sessionId")) or any(numeric_id(r.get("userId")) != user or numeric_id(r.get("sessionId")) != (session_id or numeric_id(session_rows[0]["sessionId"])) for r in records if r.get("kind") in {"session", "artistJob", "artistResult"}):
                        value = {"lookupStatus": "conflict", "reason": "회원/session 연결이 충돌하여 결과를 표시하지 않습니다."}
                    else:
                        s = session_rows[0]
                        jobs = [r for r in records if r.get("kind") == "artistJob"]
                        if len(jobs) > 1:
                            raise ValueError("Ambiguous artist attempt")
                        value = {"lookupStatus": "verified", "reason": "백엔드 DB · 해당 job의 원 session 현재 결과" if session_id else "백엔드 DB · 해당 job의 현재 프로필 연결 결과 (추천 당시 세션 아님)",
                            "sessionContext": "source-session" if session_id else "current-profile",
                            "currentProfileId": profile_id if not session_id else None,
                            "sessionId": numeric_id(s["sessionId"]), "sessionStatus": s.get("sessionStatus"),
                            "resultVersion": s.get("resultVersion"), "rangeStatus": s.get("rangeStatus"),
                            "artistSearchStatus": s.get("artistSearchStatus"),
                            "registeredRange": {k: s.get(k) for k in ["measurementId", "measurementStatus", "qualityStatus", "measurementSource", "ruleVersion", "measuredAt", "submittedAt"]},
                            "backendArtistJob": {k: jobs[0].get(k) for k in ["componentJobId", "aiJobId", "status", "failureCode", "failureReason", "attemptNo", "resultVersion", "catalogVersion", "scoringVersion", "createdAt", "updatedAt"]} if jobs else None,
                            "backendArtists": [], "_session": s,
                            "_labels": {numeric_id(r.get("artistId")): display_text(r.get("artistName")) for r in records if r.get("kind") == "artistLabel" and numeric_id(r.get("artistId"))}}
                        low, high = (bounded_number(s.get(k), 0, 127) for k in ["fullLowMidi", "fullHighMidi"])
                        valid = low is not None and high is not None and low <= high
                        value["registeredRange"].update(fullLowMidi=low if valid else None, fullHighMidi=high if valid else None,
                            boundsValid=valid, stableRangeMeasured=False, medianMeasured=False)
                        for r in records:
                            if r.get("kind") != "artistResult" or not numeric_id(r.get("artistId")):
                                continue
                            rank = bounded_number(r.get("rank"), 1, 50)
                            if rank is None or not rank.is_integer():
                                raise ValueError("Invalid stored artist rank")
                            artist = {k: r.get(k) for k in ["artistName", "componentJobId", "createdAt", "catalogBatchId"]}
                            artist.update(artistId=numeric_id(r["artistId"]), rank=int(rank),
                                earlierAttempt=bool(jobs and r.get("componentJobId") != jobs[0].get("componentJobId")))
                            for k in ["similarity", "ecapaScore", "singerByolScore"]:
                                artist[k] = bounded_number(r.get(k), -1, 1)
                            value["backendArtists"].append(artist)
                        value["backendArtists"].sort(key=lambda r: r["rank"])
                        if jobs:
                            # Lazy import avoids a repository -> insights import cycle.
                            from repository import safe_reason
                            value["backendArtistJob"]["failureReason"] = safe_reason(jobs[0].get("failureReason"))
                    self.cache[key] = (self.clock(), value)
                except (OSError, ValueError, TypeError, KeyError, AttributeError, subprocess.SubprocessError):
                    self.cache[key] = (self.clock(), {"lookupStatus": "unavailable", "reason": "음역·가수 결과 DB 조회에 실패했습니다. 저장 결과가 없다는 뜻은 아닙니다."})
            if len(self.cache) > 1000:
                self.cache = dict(sorted(self.cache.items(), key=lambda kv: kv[1][0], reverse=True)[:500])
            result = copy.deepcopy(self.cache[key][1])
        original_session, labels = result.pop("_session", {}), result.pop("_labels", {})
        if result.get("lookupStatus") == "verified":
            result["aiArtistCalculation"] = self._read_artist_job(result["backendArtistJob"], original_session, labels) if result["backendArtistJob"] else {"lookupStatus": "not-found"}
        row["voiceInsights"] = result
        return row
