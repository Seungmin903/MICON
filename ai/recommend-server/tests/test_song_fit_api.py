from __future__ import annotations

import unittest
from unittest import mock

import song_fit_api


class SongFitApiTestCase(unittest.TestCase):
    def test_selected_key_must_be_within_playable_range(self) -> None:
        for selected_key in (-7, 6):
            with self.subTest(selected_key=selected_key):
                with self.assertRaises(song_fit_api.HTTPException) as raised:
                    song_fit_api.song_fit({"selectedKeyOffset": selected_key})
                self.assertEqual(raised.exception.status_code, 422)

    def test_ideal_positive_six_shift_is_limited_to_available_positive_five(self) -> None:
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
        self.assertEqual(result["recommendedKeyOffset"], 5)


if __name__ == "__main__":
    unittest.main()
