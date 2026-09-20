"""Pure v8 contract tests; model decoding is covered by its existing tests."""
import unittest

from guided_range_v8 import evaluate_stages, validate_meta


def metadata():
    return {"protocolVersion": "guided-range-v8", "mode": "basic", "noiseRms": .001,
            "stages": [
                dict(kind="comfort", start=0, end=3, holdStart=.8, holdEnd=2.8,
                     startMidi=60, endpointMidi=60),
                dict(kind="down", start=3.2, end=7.5, holdStart=5.2, holdEnd=7.2,
                     startMidi=60, endpointMidi=57),
                dict(kind="up", start=7.7, end=12, holdStart=9.8, holdEnd=11.8,
                     startMidi=60, endpointMidi=64)]}


def frames(start, end, start_pitch, end_pitch, hold_start):
    result = []
    for index in range(round(start * 100), round(end * 100)):
        t = index / 100
        pitch = start_pitch if t < start + .5 else (
            end_pitch if t >= hold_start else
            start_pitch + (end_pitch - start_pitch) * (t - start - .5) / (hold_start - start - .5))
        result.append(dict(t=t, rawMidi=pitch, voiced=True, agreement=True,
                           rmsLow=.02, clippedRatio=0))
    return result


class FreeRangeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.meta = metadata()
        self.frames = [frames(0, 3, 60, 60, .8),
                       frames(3.2, 7.5, 60, 57, 5.2),
                       frames(7.7, 12, 60, 64, 9.8)]

    def test_verified_sweeps_return_stable_held_bounds_without_note_scores(self):
        result = evaluate_stages(self.meta, self.frames, 12)
        self.assertEqual("available", result["availability"])
        self.assertEqual(57, result["stableLowMidi"])
        self.assertEqual(64, result["stableHighMidi"])
        self.assertEqual([], result["notes"])

    def test_missing_held_voice_is_rejected(self):
        for frame in self.frames[1]:
            if 5.5 < frame["t"] < 6:
                frame["voiced"] = False
        with self.assertRaisesRegex(ValueError, "held note"):
            evaluate_stages(self.meta, self.frames, 12)

    def test_half_second_hold_accepts_one_and_half_semitone_variation(self):
        for stage in self.meta["stages"]:
            stage["holdStart"] = stage["holdEnd"] - .5
        for stage, frames_for_stage in zip(self.meta["stages"], self.frames):
            for frame in frames_for_stage:
                if frame["t"] >= stage["holdStart"]:
                    frame["rawMidi"] += 1.4 if round(frame["t"] * 100) % 2 else -1.4
        result = evaluate_stages(self.meta, self.frames, 12)
        self.assertEqual("available", result["availability"])

    def test_pitch_outside_one_and_half_semitones_is_rejected(self):
        stage = self.meta["stages"][1]
        stage["holdStart"] = stage["holdEnd"] - .5
        for frame in self.frames[1]:
            if frame["t"] >= stage["holdStart"]:
                frame["rawMidi"] += 2.0 if round(frame["t"] * 100) % 2 else -2.0
        with self.assertRaisesRegex(ValueError, "unstable"):
            evaluate_stages(self.meta, self.frames, 12)

    def test_wrong_start_and_short_sweep_are_rejected(self):
        wrong_start = [dict(frame, rawMidi=67) if frame["t"] < 3.7 else frame
                       for frame in self.frames[1]]
        with self.assertRaisesRegex(ValueError, "sweep"):
            evaluate_stages(self.meta, [self.frames[0], wrong_start, self.frames[2]], 12)
        self.meta["stages"][1]["holdStart"] = 3.9
        with self.assertRaises(ValueError):
            validate_meta(self.meta, 12)


if __name__ == "__main__":
    unittest.main()
