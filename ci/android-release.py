"""Build/verify a direct-distribution Release APK. No upload, server, or SQL writes."""

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from urllib.parse import urlsplit
import zipfile


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def https_base(value):
    try:
        url = urlsplit(value)
        host = url.hostname or ""
        _ = url.port
        try:
            public = ipaddress.ip_address(host).is_global
        except ValueError:
            public = "." in host and not host.endswith((".localhost", ".local", ".internal"))
        return (url.scheme == "https" and public and host != "localhost"
                and not url.username and not url.password and not url.query and not url.fragment
                and url.path.endswith("/") and not re.search(r'[\s"\\<>]', value))
    except ValueError:
        return False


def validate_settings(env, repo):
    keys = ("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS",
            "RELEASE_KEY_PASSWORD", "RELEASE_CERT_SHA256", "RELEASE_VERSION_CODE",
            "PREVIOUS_RELEASE_VERSION_CODE", "RELEASE_VERSION_NAME", "API_BASE_URL", "ENGINE_BASE_URL")
    for key in keys:
        require(bool(env.get(key, "").strip()), f"Missing setting: {key}")
    try:
        code = int(env["RELEASE_VERSION_CODE"])
        previous = int(env["PREVIOUS_RELEASE_VERSION_CODE"])
    except ValueError:
        raise ReleaseError("Version codes must be integers") from None
    require(0 <= previous < code <= 2100000000, "Version code must exceed the last published code")
    version = env["RELEASE_VERSION_NAME"]
    require(bool(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version)), "Unsafe version name")
    for key in ("API_BASE_URL", "ENGINE_BASE_URL"):
        require(https_base(env[key]), f"Invalid public HTTPS base URL: {key}")
    cert = env["RELEASE_CERT_SHA256"].replace(":", "").lower()
    require(bool(re.fullmatch(r"[0-9a-f]{64}", cert)), "Expected signing certificate SHA-256 is required")
    store = Path(env["RELEASE_STORE_FILE"])
    require(store.is_absolute() and store.is_file(), "Keystore must be an existing absolute file")
    require(not store.resolve().is_relative_to(repo.resolve()), "Keystore must stay outside the repository")
    require(store.name.lower() != "debug.keystore", "Debug keystore cannot be a Release key")
    return code, version, cert


def run(args, cwd, env, stage, timeout=1200):
    # Never stream Gradle/signing diagnostics: they may contain credential values.
    try:
        result = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ReleaseError(f"Stage failed to execute: {stage}") from None
    require(result.returncode == 0, f"Stage failed: {stage}; raw output withheld to protect credentials")
    return result.stdout


def verify_metadata(badging, signature, code, version, cert):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(package is not None, "APK metadata is missing")
    require(package.groups() == ("com.micon.app", str(code), version), "APK app ID or version mismatch")
    require(not re.search(r"^application-debuggable\b", badging, re.M), "Debuggable APK is not a Release")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$", signature, re.M)
    require(len(certificates) == 1 and certificates[0].lower() == cert, "Signing certificate mismatch")
    require("CN=Android Debug" not in signature, "Android Debug certificate is forbidden")


def artifact_name(code, version, sha):
    require(bool(re.fullmatch(r"[0-9a-f]{40}", sha)), "Invalid source SHA")
    return f"micon-{version}-{code}-{sha[:12]}.apk"


def main():
    repo = Path(__file__).resolve().parent.parent
    android = repo / "frontend/android"
    env = os.environ.copy()
    code, version, cert = validate_settings(env, repo)
    require(not (android / "local.properties").exists(), "Release agent must not have local.properties")
    sha = run(["git", "rev-parse", "HEAD"], repo, env, "source", 30).strip()
    name = artifact_name(code, version, sha)
    require(not run(["git", "status", "--porcelain"], repo, env, "clean checkout", 30).strip(), "Release checkout must be clean")
    # MR jobs must not receive signing credentials; use a separate trusted Release workflow.
    require(not env.get("gitlabMergeRequestIid") and not env.get("CHANGE_ID"), "Release signing is forbidden in MR jobs")
    require(env.get("RELEASE_APPROVED_SHA") == sha, "Release approval must match the exact source SHA")
    java = run(["java", "-XshowSettings:properties", "-version"], repo, env, "JDK", 30)
    require(bool(re.search(r"java.specification.version\s*=\s*21\s*$", java, re.M)), "JDK 21 is required")
    sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT", ""))
    require(any((sdk / "platforms" / platform).is_dir() for platform in ("android-37", "android-37.0")), "SDK 37 is required")
    tools = sdk / "build-tools/36.0.0"
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    aapt = tools / ("aapt.exe" if os.name == "nt" else "aapt")
    require(signer.is_file() and aapt.is_file(), "Android Build Tools 36.0.0 are required")
    run(["git", "lfs", "fsck", "--objects"], repo, env, "LFS integrity", 120)
    # Git credentials are no longer needed during the application build.
    for key in ("GIT_USERNAME", "GIT_PASSWORD", "GIT_ASKPASS", "SSH_ASKPASS"):
        env.pop(key, None)
    env["GIT_TERMINAL_PROMPT"] = "0"
    gradle = [str(android / "gradlew.bat")] if os.name == "nt" else ["sh", str(android / "gradlew")]
    print("release stage=build_and_test")
    # AGP 9 creates the debug unit-test variant for this project; Release is
    # assembled separately with the configured production signing key.
    run(gradle + ["--no-daemon", "--no-configuration-cache", "--no-build-cache", "clean",
                  ":app:testDebugUnitTest", ":app:assembleRelease"], android, env, "build_and_test")
    # Verification tools need no signing credentials.
    for key in ("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD", "RELEASE_KEY_ALIAS"):
        env.pop(key, None)
    apk = android / "app/build/outputs/apk/release/app-release.apk"
    require(apk.is_file(), "Signed Release APK not found")
    signature = run([str(signer), "verify", "--verbose", "--print-certs", str(apk)], repo, env, "signature", 60)
    badging = run([str(aapt), "dump", "badging", str(apk)], repo, env, "APK metadata", 60)
    verify_metadata(badging, signature, code, version, cert)
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, "APK archive integrity failed")
        require("assets/micon_catalog.sqlite" not in archive.namelist(),
                "Removed local catalog must not be bundled in the APK")
    with apk.open("rb") as data:
        checksum = hashlib.file_digest(data, "sha256").hexdigest()
    output = repo / "build/releases" / name.removesuffix(".apk")
    require(not output.exists(), "Release output exists; do not overwrite published candidates")
    output.mkdir(parents=True)
    shutil.copyfile(apk, output / name)
    (output / (name + ".sha256")).write_text(f"{checksum}  {name}\n", encoding="utf-8")
    metadata = dict(application_id="com.micon.app", version_code=code, version_name=version,
                    commit_sha=sha, apk=name, apk_sha256=checksum, certificate_sha256=cert,
                    api_base_url=env["API_BASE_URL"], engine_base_url=env["ENGINE_BASE_URL"],
                    created_utc=datetime.now(timezone.utc).isoformat(), published=False)
    (output / "release.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"release result=verified_not_published artifact={name} sha256={checksum}")


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        print(f"release error={error}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("release error=unexpected_failure; no artifact should be published", file=sys.stderr)
        raise SystemExit(1)
