"""Synthetic settings/verification text only; no signing keys or real APK generated."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("android_release", Path(__file__).with_name("android-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.store = self.root / "fixture.txt"
        self.store.write_text("NOT A KEYSTORE: config validation fixture only", encoding="utf-8")
        self.env = {
            "RELEASE_STORE_FILE": str(self.store), "RELEASE_STORE_PASSWORD": "test-only",
            "RELEASE_KEY_ALIAS": "fixture", "RELEASE_KEY_PASSWORD": "test-only",
            "RELEASE_CERT_SHA256": "a" * 64, "RELEASE_VERSION_CODE": "2",
            "PREVIOUS_RELEASE_VERSION_CODE": "1", "RELEASE_VERSION_NAME": "0.1.1-rc.1",
            "API_BASE_URL": "https://api.example.com/api/v1/",
            "ENGINE_BASE_URL": "https://engine.example.com/",
        }
        self.badging = "package: name='com.micon.app' versionCode='2' versionName='0.1.1-rc.1' platformBuildVersionName='16'"
        self.signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64

    def test_valid_configuration_fixture(self):
        self.assertEqual(release.validate_settings(self.env, self.repo), (2, "0.1.1-rc.1", "a" * 64))

    def test_missing_settings(self):
        for key in self.env:
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.validate_settings({k: v for k, v in self.env.items() if k != key}, self.repo)

    def test_non_increasing_or_invalid_codes(self):
        for value in ("1", "0", "-1", "abc", "2100000001"):
            with self.subTest(value=value), self.assertRaises(release.ReleaseError):
                release.validate_settings(dict(self.env, RELEASE_VERSION_CODE=value), self.repo)

    def test_unsafe_version_name(self):
        with self.assertRaises(release.ReleaseError):
            release.validate_settings(dict(self.env, RELEASE_VERSION_NAME="../bad"), self.repo)

    def test_keystore_inside_repository(self):
        fixture = self.repo / "fixture.txt"
        fixture.write_text("not a key", encoding="utf-8")
        with self.assertRaises(release.ReleaseError):
            release.validate_settings(dict(self.env, RELEASE_STORE_FILE=str(fixture)), self.repo)

    def test_keystore_does_not_exist(self):
        with self.assertRaises(release.ReleaseError):
            release.validate_settings(dict(self.env, RELEASE_STORE_FILE=str(self.root / "absent")), self.repo)

    def test_https_urls(self):
        for value in ("http://10.0.2.2:8787/", "https://127.0.0.1/", "https://192.168.1.2/",
                      "https://[::1]/", "https://localhost/", "https://host.local/",
                      "https://user:password@example.com/", "https://example.com/?token=x",
                      "https://example.com/path", 'https://example.com/"/'):
            with self.subTest(value=value):
                self.assertFalse(release.https_base(value))
        self.assertTrue(release.https_base("https://j15b110.p.ssafy.io/api/v1/"))

    def test_valid_apk_verification_fixture(self):
        release.verify_metadata(self.badging, self.signature, 2, "0.1.1-rc.1", "a" * 64)

    def test_wrong_package_or_version(self):
        for badging in (self.badging.replace("com.micon.app", "com.other.app"),
                        self.badging.replace("versionCode='2'", "versionCode='1'"),
                        self.badging.replace("0.1.1-rc.1", "0.1.0")):
            with self.subTest(badging=badging), self.assertRaises(release.ReleaseError):
                release.verify_metadata(badging, self.signature, 2, "0.1.1-rc.1", "a" * 64)

    def test_debug_apk(self):
        with self.assertRaises(release.ReleaseError):
            release.verify_metadata(self.badging + "\napplication-debuggable", self.signature, 2, "0.1.1-rc.1", "a" * 64)

    def test_wrong_or_multiple_certificates(self):
        for signature in (self.signature.replace("a" * 64, "b" * 64), "", self.signature + "\n" + self.signature):
            with self.subTest(signature=signature), self.assertRaises(release.ReleaseError):
                release.verify_metadata(self.badging, signature, 2, "0.1.1-rc.1", "a" * 64)

    def test_debug_certificate(self):
        with self.assertRaises(release.ReleaseError):
            release.verify_metadata(self.badging, self.signature + "\nSigner #1 certificate DN: CN=Android Debug", 2, "0.1.1-rc.1", "a" * 64)

    def test_artifact_filename_and_sha(self):
        self.assertEqual(release.artifact_name(2, "0.1.1-rc.1", "a" * 40), "micon-0.1.1-rc.1-2-aaaaaaaaaaaa.apk")
        with self.assertRaises(release.ReleaseError):
            release.artifact_name(2, "0.1.1", "../bad")


if __name__ == "__main__":
    unittest.main()
