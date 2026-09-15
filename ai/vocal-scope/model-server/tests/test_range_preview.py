import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
import range_preview_app as preview
from range_evaluator import build_report


class RangePreviewTests(unittest.TestCase):
    def test_v5_evidence_form_can_exceed_one_megabyte_without_changing_legacy_limit(self):
        meta = dict(sessionId=str(uuid4()), measurementType='guided_range', protocolVersion='guided-range-v5',
            durationSeconds=1, startMidi=None, noiseRms=.0001, stopReason='user_stopped',
            lowerBoundaryStatus='not_probed', upperBoundaryStatus='not_probed', attempts=[], padding='x'*1_100_000)
        client=TestClient(preview.app, client=('127.0.0.1',50000))
        with tempfile.TemporaryDirectory() as d, patch.object(preview,'STORAGE',Path(d)), \
                patch.object(preview,'analyze',return_value=build_report(meta,[])):
            response=client.post('/preview/guided-range',files={'audio':('test.wav',b'waveform')},data={'metadata':json.dumps(meta)})
            self.assertEqual(200,response.status_code,response.text[:300])
            meta['protocolVersion']='guided-range-v4';meta['sessionId']=str(uuid4())
            response=client.post('/preview/guided-range',files={'audio':('test.wav',b'waveform')},data={'metadata':json.dumps(meta)})
            self.assertEqual(413,response.status_code)

    def test_preview_accepts_loopback_without_login_and_rejects_remote_clients(self):
        self.assertEqual(200, TestClient(preview.app, client=("127.0.0.1", 50000)).get("/health").status_code)
        self.assertEqual(403, TestClient(preview.app, client=("192.0.2.1", 50000)).get("/health").status_code)

    def test_completed_model_result_is_idempotent_and_extension_requires_server_evidence(self):
        meta = dict(sessionId=str(uuid4()), measurementType="guided_range", protocolVersion="guided-range-v1",
                    durationSeconds=1, startMidi=None, noiseRms=.0001, stopReason="user_stopped",
                    lowerBoundaryStatus="not_probed", upperBoundaryStatus="not_probed", attempts=[])
        client = TestClient(preview.app, client=("127.0.0.1", 50000))
        with tempfile.TemporaryDirectory() as d, patch.object(preview, "STORAGE", Path(d)), \
                patch.object(preview, "analyze", return_value=build_report(meta, [])) as model:
            for _ in range(2):
                response = client.post("/preview/guided-range", files={"audio": ("a.wav", b"recording")}, data={"metadata": json.dumps(meta)})
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual("model_verified", response.json()["verification"])
            self.assertEqual(1, model.call_count)
            self.assertEqual([], list(Path(d).glob("recording-*")))
            meta.update(sessionId=str(uuid4()), mode="extension", parentAnalysisId=str(uuid4()))
            response = client.post("/preview/guided-range", files={"audio": ("a.wav", b"recording")}, data={"metadata": json.dumps(meta)})
            self.assertEqual(404, response.status_code)
            self.assertEqual(1, model.call_count)
