from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

from schemas import RangeFit
from timbre_recommender.stability_scoring import StabilityCurve, best_stability_fit, blend_stability, stability_at_shift
from timbre_recommender.shelves import build_shelves, score_all_tracks
from pitch_profile import MIDI_MIN, MIDI_MAX


def curve(values):
    return {"version": "personal-acoustic-v1", "referenceMidi": 60,
            "points": [{"midi": midi, "score": score} for midi, score in values]}


def profile(values, *, reliable=True, confidence=1):
    mass = np.zeros(MIDI_MAX - MIDI_MIN + 1)
    for midi, weight in values:
        mass[midi - MIDI_MIN] = weight
    return {"histogram": mass.tolist(), "coreLowMidi": min(midi for midi, _ in values),
            "coreHighMidi": max(midi for midi, _ in values), "medianMidi": values[0][0],
            "reliable": reliable, "confidence": confidence}


class StabilityScoringTest(unittest.TestCase):
    def test_v2_quality_is_supported_without_reinterpreting_legacy_scores(self):
        current = curve([(60, 30), (62, 100)])
        current["version"] = "personal-acoustic-v2"
        measured = StabilityCurve.from_payload(current)
        self.assertEqual(measured.scores[60], .3)
        self.assertEqual(StabilityCurve.from_payload(curve([(60, 30)])).scores[60], .3)

    def test_duration_weighted_measured_score(self):
        measured = StabilityCurve.from_payload(curve([(60, 90), (62, 40)]))
        fit = stability_at_shift(measured, profile([(60, .7), (62, .3)]), 0)
        self.assertAlmostEqual(fit["score"], .75)
        self.assertAlmostEqual(fit["confidence"], 1)

    def test_full_measured_histogram_remains_within_schema_bounds(self):
        notes = list(range(MIDI_MIN, MIDI_MAX + 1))
        measured = StabilityCurve.from_payload(curve([(midi, 100) for midi in notes]))
        best = best_stability_fit(measured, profile([(midi, .1) for midi in notes]))
        self.assertEqual(best["keyShift"], 0)
        self.assertLessEqual(best["score"], 1)
        self.assertLessEqual(best["measuredCoverage"], 1)
        RangeFit.model_validate(best)

    def test_unmeasured_pitch_is_unknown_but_measured_zero_is_evidence(self):
        measured = StabilityCurve.from_payload(curve([(60, 0)]))
        fit = stability_at_shift(measured, profile([(60, .25), (62, .75)]), 0)
        self.assertEqual(fit["score"], 0)
        self.assertEqual(fit["measuredCoverage"], .25)
        score, weights = blend_stability(.8, fit)
        self.assertAlmostEqual(weights["stability"], .0375)
        self.assertAlmostEqual(score, .77)
        self.assertIsNone(stability_at_shift(measured, profile([(62, 1)]), 0))

    def test_exact_nominal_seventy_fifteen_fifteen_and_timbre_fallback(self):
        timbre = (.70 * .8 + .15 * .4) / .85
        score, weights = blend_stability(timbre, {"score": .6, "confidence": 1})
        self.assertAlmostEqual(score, .70 * .8 + .15 * .4 + .15 * .6)
        for key, expected in (("ecapa", .70), ("singer_byol", .15), ("stability", .15)):
            self.assertAlmostEqual(weights[key], expected)
        fallback, weights = blend_stability(timbre, None)
        self.assertEqual(fallback, timbre)
        self.assertEqual(weights["stability"], 0)

    def test_best_key_is_playable_and_ties_prefer_original(self):
        measured = StabilityCurve.from_payload(curve([(60, 30), (62, 90)]))
        best = best_stability_fit(measured, profile([(60, 1)]))
        self.assertEqual(best["keyShift"], 2)
        RangeFit.model_validate(best)
        tied = StabilityCurve.from_payload(curve([(60, 90), (62, 90)]))
        self.assertEqual(best_stability_fit(tied, profile([(60, 1)]))["keyShift"], 0)

    def test_bad_or_missing_measurements_do_not_create_scores(self):
        for payload in (None, curve([]), curve([(60, float("nan"))]), curve([(60, 110)]),
                        curve([(60, 80), (60, 90)]), {"version": "unknown", "points": []}):
            self.assertIsNone(StabilityCurve.from_payload(payload))
        measured = StabilityCurve.from_payload(curve([(60, 90)]))
        self.assertIsNone(best_stability_fit(measured, profile([(60, 1)], reliable=False)))

    def test_curve_works_without_stable_range_bounds_and_missing_catalog_profile_still_recommends(self):
        tracks = [SimpleNamespace(track_id=str(i), title=f"Song {i}", artist=f"Artist {i}",
                                  metadata={"song_id": f"song-{i}"}) for i in range(2)]
        catalog = SimpleNamespace(tracks=tracks)
        scores = {key: {"absolute_score": .8} for key in ("ecapa", "singer_byol")}
        with patch("timbre_recommender.shelves._timbre_scores", return_value=(scores, {})):
            rows = score_all_tracks(catalog, SimpleNamespace(profiles={"0": profile([(60, 1)])}),
                                    {key: np.ones((1, 2)) for key in scores}, query_profile=None,
                                    voice_quality=curve([(60, 100)]))
        self.assertAlmostEqual(rows[0]["final_score"], .83)
        self.assertEqual(rows[1]["final_score"], .8)
        shelves = build_shelves(rows, top_k=15, range_enabled=False)
        voice = next(shelf for shelf in shelves if shelf["key"] == "voice")
        self.assertEqual(len(voice["items"]), 2)
        self.assertEqual(voice["items"][0]["recommended_key"], 0)


if __name__ == "__main__":
    unittest.main()
