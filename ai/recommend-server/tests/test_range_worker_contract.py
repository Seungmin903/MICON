from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]


class RangeWorkerContractTest(unittest.TestCase):
    def test_shared_be_fixture_is_accepted_by_real_guided_range_schema(self):
        # Separate process is intentional: both services have a module named pitch_engine.
        script = """
import json, sys
from pathlib import Path
from be_range_worker import submission_from_meta
value = submission_from_meta(json.loads(Path(sys.argv[1]).read_text(encoding='utf-8')))
assert value.sampling.strategy == 'adaptive_6'
assert value.registerEvents[0].register_label == 'modal'
assert value.attempts[0].probeStage == 'anchor'
assert value.sessionId == 'range-fixture-1'
"""
        run = subprocess.run([sys.executable, "-B", "-c", script,
                              str(ROOT / "docs/contracts/onboarding-range-v7.json")],
                             cwd=ROOT / "ai/vocal-scope/model-server", capture_output=True,
                             text=True, encoding="utf-8", errors="replace", timeout=60)
        self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
