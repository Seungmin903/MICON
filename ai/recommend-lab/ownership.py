"""Read-only job -> signup login ID and internal member number. No recency guesses."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

SAFE_JOB = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
SAFE_TARGET = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9_.-]*$")
SOURCES = {"backend-component-job", "backend-analysis-job", "backend-recommendation-job", "ownership-snapshot"}


def numeric_id(value):
    # Strings preserve MySQL BIGINT identity in JavaScript without rounding.
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        return None
    text = str(value)
    return str(int(text)) if re.fullmatch(r"[0-9]{1,19}", text) and 0 < int(text) <= 2**63 - 1 else None


def login_identifier(value):
    if not isinstance(value, str):
        return None
    text = value.strip()
    return text if 1 <= len(text) <= 255 and not any(ord(char) < 32 or 127 <= ord(char) < 160 for char in text) else None


def job_key(row):
    kind, jid = row.get("kind"), row.get("id")
    return (kind, jid) if kind in {"analysis", "recommendation"} and isinstance(jid, str) and SAFE_JOB.fullmatch(jid) else None


def ownership_query(rows):
    ids = {kind: sorted({r["id"] for r in rows if job_key(r) and r["kind"] == kind}) for kind in ["analysis", "recommendation"]}
    selects = []
    # Only validated job IDs enter SQL. The signup login email is explicitly requested;
    # no password, tokens, nickname or unrelated account data is selected.
    if ids["analysis"]:
        values = ",".join("'" + jid + "'" for jid in ids["analysis"])
        for table, source in [("voice_analysis_component_job", "backend-component-job"), ("voice_analysis_job", "backend-analysis-job")]:
            selects.append(f"SELECT JSON_OBJECT('kind','analysis','id',j.ai_job_id,'userId',CAST(s.user_id AS CHAR),'loginId',u.email,'sessionId',CAST(s.session_id AS CHAR),'source','{source}') AS owner FROM {table} j JOIN voice_analysis_session s ON s.session_id=j.session_id LEFT JOIN users u ON u.user_id=s.user_id WHERE j.ai_job_id IN ({values})")
    if ids["recommendation"]:
        values = ",".join("'" + jid + "'" for jid in ids["recommendation"])
        selects.append(f"SELECT JSON_OBJECT('kind','recommendation','id',r.job_id,'userId',CAST(r.user_id AS CHAR),'loginId',u.email,'profileId',CAST(r.profile_id AS CHAR),'sessionId',CAST(r.source_session_id AS CHAR),'source','backend-recommendation-job') AS owner FROM recommendation r LEFT JOIN users u ON u.user_id=r.user_id WHERE r.job_id IN ({values})")
    return "START TRANSACTION READ ONLY;\n" + "\nUNION ALL\n".join(selects) + ";\nCOMMIT;" if selects else None


class SshMysqlLookup:
    """Optional local bridge to the existing BE DB. Fixed SELECT-only transaction."""
    def __init__(self, target, key, executable=None):
        if not SAFE_TARGET.fullmatch(target) or not Path(key).is_file():
            raise ValueError("회원 조회 SSH target 또는 key 파일을 확인해 주세요.")
        self.target, self.key = target, str(Path(key).resolve())
        self.executable = executable or shutil.which("ssh")
        if not self.executable:
            raise ValueError("회원 조회용 OpenSSH client가 필요합니다.")

    def __call__(self, rows):
        query = ownership_query(rows)
        if not query:
            return []
        # Password is read only inside the remote DB container and never returned.
        remote = "import subprocess,sys\nquery=" + repr(query) + "\n" + '''
script='export MYSQL_PWD="$(cat "$MYSQL_ROOT_PASSWORD_FILE")"; exec mysql -u root --batch --raw --skip-column-names --default-character-set=utf8mb4 micon'
p=subprocess.run(['docker','exec','-i','micon-mysql-1','sh','-c',script],input=query,capture_output=True,text=True,timeout=12)
if p.returncode:sys.exit(1)
sys.stdout.write(p.stdout)
'''
        command = [self.executable, "-i", self.key, "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes",
                   "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=5", "-o", "ServerAliveInterval=5",
                   "-o", "ServerAliveCountMax=1", "--", self.target, "sudo -n python3 -"]
        result = subprocess.run(command, input=remote, capture_output=True, text=True, encoding="utf-8", timeout=20)
        if result.returncode or len(result.stdout) > 2_000_000:
            # Deliberately do not send remote stderr, SQL or credentials to the client/log.
            raise OSError("회원 조회 연결 실패")
        return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


class SnapshotLookup:
    """Portable optional mapping supplied by an approved BE export, never a heuristic."""
    def __init__(self, path):
        self.path = Path(path).resolve()

    def __call__(self, rows):
        if self.path.stat().st_size > 2_000_000:
            raise ValueError("회원 연결 snapshot 크기 제한 초과")
        value = json.loads(self.path.read_bytes())
        if not isinstance(value, dict) or value.get("schemaVersion") != 1 or not isinstance(value.get("owners"), list):
            raise ValueError("회원 연결 snapshot 형식 오류")
        # An expired export must not remain displayed as currently verified identity.
        if not isinstance(value.get("validUntilEpoch"), (int, float)) or value["validUntilEpoch"] <= time.time():
            raise ValueError("회원 연결 snapshot 만료")
        wanted = {job_key(row) for row in rows}
        return [dict(row, source="ownership-snapshot") for row in value["owners"] if isinstance(row, dict) and job_key(row) in wanted]


class OwnerResolver:
    def __init__(self, lookup=None, ttl=30, clock=time.monotonic):
        self.lookup, self.ttl, self.clock = lookup, ttl, clock
        self.cache = {}
        self.lock = threading.Lock()

    @classmethod
    def from_config(cls, cfg):
        target, key = cfg.get("LAB_OWNER_SSH_TARGET"), cfg.get("LAB_OWNER_SSH_KEY")
        if target or key:
            if not (target and key):
                raise ValueError("LAB_OWNER_SSH_TARGET과 LAB_OWNER_SSH_KEY가 모두 필요합니다.")
            return cls(SshMysqlLookup(target, key, cfg.get("LAB_OWNER_SSH_BIN")))
        return cls(SnapshotLookup(cfg["LAB_OWNER_MAP"]) if cfg.get("LAB_OWNER_MAP") else None)

    def enrich(self, rows):
        with self.lock:
            now = self.clock()
            pending = {job_key(r): r for r in rows if job_key(r) and (job_key(r) not in self.cache or now - self.cache[job_key(r)][0] >= self.ttl)}
            if pending and self.lookup:
                try:
                    found = self.lookup(list(pending.values()))
                    if not isinstance(found, list):
                        raise ValueError("회원 연결 조회 형식 오류")
                    grouped = {key: [] for key in pending}
                    for owner in found:
                        if isinstance(owner, dict) and job_key(owner) in grouped and owner.get("source") in SOURCES and numeric_id(owner.get("userId")):
                            grouped[job_key(owner)].append(owner)
                    self.cache.update({key: (self.clock(), "verified" if owners else "not-found", owners) for key, owners in grouped.items()})
                except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
                    self.cache.update({key: (self.clock(), "unavailable", []) for key in pending})
            if len(self.cache) > 2000:
                self.cache = dict(sorted(self.cache.items(), key=lambda kv: kv[1][0], reverse=True)[:1000])
            for row in rows:
                key = job_key(row)
                _, status, owners = self.cache.get(key, (now, "not-configured" if key else "not-applicable", []))
                users = {numeric_id(o["userId"]) for o in owners}
                logins = {login_identifier(o.get("loginId")) for o in owners} - {None}
                # Check exact job linkage against retained session/profile metadata too.
                mismatch = any(numeric_id(row.get(field)) and numeric_id(o.get(field)) and numeric_id(row[field]) != numeric_id(o[field]) for o in owners for field in ["sessionId", "profileId"])
                if len(users) > 1 or len(logins) > 1 or mismatch:
                    status = "conflict"
                row["userId"] = next(iter(users)) if status == "verified" and len(users) == 1 else None
                row["loginId"] = next(iter(logins)) if status == "verified" and len(logins) == 1 else None
                row["loginIdStatus"] = ("verified" if row["loginId"] else "missing") if status == "verified" else status
                row["ownerStatus"] = status
                row["ownerSource"] = sorted({o["source"] for o in owners}) if status == "verified" else []
                row["ownerReason"] = {
                    "verified": "백엔드 job-id 연결 확인",
                    "not-found": "백엔드에 이 job-id의 회원 연결이 없습니다. 삭제되거나 보존되지 않은 기록일 수 있습니다.",
                    "unavailable": "회원 연결을 조회하지 못했습니다. DB 연결 또는 snapshot 유효 기간을 확인해 주세요.",
                    "not-configured": "회원 연결 데이터 소스가 설정되지 않았습니다.",
                    "not-applicable": "저장 테스트 case입니다. 실제 회원 접수 job이 아닙니다.",
                    "conflict": "job-id의 회원 또는 session/profile 연결이 충돌하여 표시하지 않습니다.",
                }[status]
                if status == "verified":
                    row["ownerReason"] = "백엔드 DB · job-id 직접 연결" if "ownership-snapshot" not in row["ownerSource"] else "승인된 백엔드 snapshot · job-id 직접 연결"
        return rows
