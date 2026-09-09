"""Static contract checks only: no app startup, environment reads or DB access."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
# Read by AWS SDK directly, not by Spring application.properties placeholders.
AWS_SDK_ENVIRONMENT_NAMES = {
    'AWS_SHARED_CREDENTIALS_FILE', 'AWS_PROFILE', 'AWS_EC2_METADATA_DISABLED',
}


class RuntimeContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.properties = (ROOT / 'backend/src/main/resources/application.properties').read_text(encoding='utf-8')
        cls.example = (ROOT / 'backend/.env.example').read_text(encoding='utf-8')
        cls.document = (ROOT / 'docs/operations/BACKEND_RUNTIME_CONTRACT.md').read_text(encoding='utf-8')
        cls.entries = [line.split('=', 1) for line in cls.example.splitlines()
                       if line.strip() and not line.lstrip().startswith('#')]

    def test_example_matches_current_placeholder_names(self):
        names = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)(?::[^}]*)?\}', self.properties))
        self.assertEqual(names | AWS_SDK_ENVIRONMENT_NAMES, {key for key, _ in self.entries})

    def test_example_has_no_duplicate_variables(self):
        keys = [key for key, _ in self.entries]
        self.assertEqual(len(keys), len(set(keys)))

    def test_tracked_example_contains_no_values(self):
        for key, value in self.entries:
            with self.subTest(variable=key):
                self.assertEqual(value, '', 'Tracked examples must remain empty')

    def test_every_variable_is_documented(self):
        for key, _ in self.entries:
            with self.subTest(variable=key):
                self.assertIn('| ' + key + ' |', self.document)

    def test_sql_hold_warning_present(self):
        self.assertIn('Flyway', self.example)
        self.assertIn('SQL hold', self.example)
        self.assertIn('NOT a runnable production environment', self.example)

    def test_readiness_contract_requires_db_and_separate_liveness(self):
        self.assertIn('management.endpoint.health.group.readiness.include=readinessState,db', self.document)
        self.assertIn('management.endpoint.health.group.liveness.include=livenessState', self.document)
        self.assertIn('/actuator/health/readiness', self.document)

    def test_contract_keeps_implementation_handoff_explicit(self):
        self.assertIn('150의 범위는 계약 문서화', self.document)
        self.assertIn('SQL 및 본인 프런트 구현 보류를 유지', self.document)
        self.assertIn('GitLab Runner 설치를 의미하지 않는다', self.document)


if __name__ == '__main__':
    unittest.main()
