from __future__ import annotations

import unittest
from copy import deepcopy
from unittest import mock

import song_fit_api


class SongFitApiTestCase(unittest.TestCase):
    def test_selected_key_must_be_within_playable_range(self) -> None:
        for selected_key in (-4, 4):
            with self.subTest(selected_key=selected_key):
                with self.assertRaises(song_fit_api.HTTPException) as raised:
                    song_fit_api.song_fit({"selectedKeyOffset": selected_key})
                self.assertEqual(raised.exception.status_code, 422)

    def test_ideal_positive_six_shift_is_limited_to_available_positive_three(self) -> None:
        payload = {
            "songId": 1,
            "analysisId": 2,
            "selectedKeyOffset": 0,
            "userStableLowMidi": 66,
            "userStableHighMidi": 67,
            "songLowMidi": 60,
            "songHighMidi": 61,
        }

        with mock.patch.object(
            song_fit_api,
            "_configuration_status",
            return_value=({}, ["index_dir"]),
        ):
            result = song_fit_api.song_fit(payload)

        self.assertEqual(result["evaluationStatus"], "AVAILABLE")
        self.assertEqual(result["recommendedKeyOffset"], 3)

    def test_optional_timbre_metadata_survives_available_and_unavailable_results(self) -> None:
        for available in (True, False):
            for metadata in ({}, {"timbreAxis": "ECAPA", "timbreLabel": "목소리 물성이 가까워요"}):
                with self.subTest(available=available, metadata=metadata):
                    scores = {"overall": 0.88, "timbre": 0.77, "algorithmVersion": "fit-v1", **metadata}
                    payload = {
                        "songId": "7", "analysisId": 9, "selectedKeyOffset": -2,
                        "userStableLowMidi": 55 if available else None, "userStableHighMidi": 72,
                        "songLowMidi": 60, "songHighMidi": 74,
                        "recommendationScores": scores,
                        "burdenSections": [{"sectionId": 1, "startMs": 1000, "endMs": 3000}],
                    }
                    before = deepcopy(payload)
                    with mock.patch.object(song_fit_api, "_configuration_status", return_value=({}, ["index_dir"])):
                        result = song_fit_api.song_fit(payload)
                    self.assertEqual(result["evaluationStatus"], "AVAILABLE" if available else "UNAVAILABLE")
                    self.assertEqual(result["recommendationScores"], scores)
                    self.assertEqual(result["burdenSections"], payload["burdenSections"])
                    self.assertEqual(result["songId"], "7")
                    self.assertEqual(result["analysisId"], 9)
                    self.assertEqual(result["selectedKeyOffset"], -2)
                    self.assertEqual(payload, before)
                    if available:
                        self.assertEqual(result["originalKeyFit"]["songHighMidi"], 74)
                        self.assertEqual(result["selectedKeyFit"]["songHighMidi"], 72)


if __name__ == "__main__":
    unittest.main()
