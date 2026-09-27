"""Pure v8 contract tests; model decoding is covered by its existing tests."""
import unittest

from guided_range_v8 import evaluate_stages, validate_meta


def metadata():
    return {"protocolVersion": "guided-range-v8", "mode": "basic", "noiseRms": .001,
            "stages": [
                dict(kind="comfort", start=0, end=3, holdStart=.8, holdEnd=2.8,
                     startMidi=60, endpointMidi=60),
                dict(kind="down", start=3.2, end=18.2, holdStart=8, holdEnd=8.5,
                     startMidi=60, endpointMidi=57),
                dict(kind="up", start=18.4, end=33.4, holdStart=23, holdEnd=23.5,
                     startMidi=60, endpointMidi=64)]}


def frames(start, end, origin, extreme, ramp_end, hold_end, settle):
    """Origin for half a second, a slow ramp to the extreme, a short hold, then a drift back."""
    result = []
    for index in range(round(start * 100), round(end * 100)):
        t = index / 100
        if t < start + .5:
            pitch = origin
        elif t < ramp_end:
            pitch = origin + (extreme - origin) * (t - start - .5) / (ramp_end - start - .5)
        elif t < hold_end:
            pitch = extreme
        else:
            pitch = extreme + (settle - extreme) * min(1.0, (t - hold_end) / 6)
        result.append(dict(t=t, rawMidi=pitch, voiced=True, agreement=True,
                           rmsLow=.02, clippedRatio=0))
    return result


class FreeRangeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.meta = metadata()
        self.frames = [frames(0, 3, 60, 60, .8, 2.8, 60),
                       frames(3.2, 18.2, 60, 57, 8, 8.5, 59),
                       frames(18.4, 33.4, 60, 64, 23, 23.5, 62)]

    def test_verified_sweeps_return_stable_held_bounds_without_note_scores(self):
        result = evaluate_stages(self.meta, self.frames, 33.5)
        self.assertEqual("available", result["availability"])
        self.assertEqual(57, result["stableLowMidi"])
        self.assertEqual(64, result["stableHighMidi"])
        self.assertEqual([], result["notes"])

    def test_missing_held_voice_is_rejected(self):
        for frame in self.frames[1]:
            if 8.1 < frame["t"] < 8.4:
                frame["voiced"] = False
        with self.assertRaisesRegex(ValueError, "held note"):
            evaluate_stages(self.meta, self.frames, 33.5)

    def test_three_tenths_of_a_second_accepts_one_and_half_semitone_variation(self):
        for stage, frames_for_stage in zip(self.meta["stages"][1:], self.frames[1:]):
            stage["holdStart"] = stage["holdEnd"] - .3
            for frame in frames_for_stage:
                if frame["t"] >= stage["holdStart"]:
                    frame["rawMidi"] += 1.4 if round(frame["t"] * 100) % 2 else -1.4
        result = evaluate_stages(self.meta, self.frames, 33.5)
        self.assertEqual("available", result["availability"])
        self.assertEqual(57, result["stableLowMidi"])

    def test_pitch_outside_one_and_half_semitones_is_rejected(self):
        stage = self.meta["stages"][1]
        stage["holdStart"] = stage["holdEnd"] - .3
        for frame in self.frames[1]:
            if frame["t"] >= stage["holdStart"]:
                frame["rawMidi"] += 2.0 if round(frame["t"] * 100) % 2 else -2.0
        with self.assertRaisesRegex(ValueError, "unstable"):
            evaluate_stages(self.meta, self.frames, 33.5)

    def test_a_hold_shorter_than_three_tenths_is_rejected(self):
        self.meta["stages"][1]["holdStart"] = 8.3
        with self.assertRaises(ValueError):
            validate_meta(self.meta, 33.5)

    def test_a_sweep_shorter_than_the_mission_time_is_rejected(self):
        meta = metadata()
        meta["stages"][1]["end"] = 12.0
        with self.assertRaises(ValueError):
            validate_meta(meta, 33.5)

    def test_ten_second_down_sweep_and_legacy_fifteen_second_up_are_verified(self):
        self.meta["stages"][1]["end"] = 13.2
        self.frames[1] = [frame for frame in self.frames[1] if frame["t"] < 13.2]
        result = evaluate_stages(self.meta, self.frames, 33.5)
        self.assertEqual(57, result["stableLowMidi"])
        self.assertEqual(64, result["stableHighMidi"])

        too_short_up = metadata()
        too_short_up["stages"][2]["end"] = 32.0
        with self.assertRaises(ValueError):
            validate_meta(too_short_up, 33.5)

    def test_new_twenty_second_up_sweep_is_verified(self):
        self.meta["stages"][2]["end"] = 38.4
        self.frames[2] = frames(18.4, 38.4, 60, 64, 23, 23.5, 62)
        result = evaluate_stages(self.meta, self.frames, 38.5)
        self.assertEqual(64, result["stableHighMidi"])

    def test_a_sweep_may_run_past_the_mission_time_up_to_the_cap(self):
        allowed = metadata()
        allowed["stages"][1]["end"] = 25
        allowed["stages"][2].update(start=25.2, end=40.2, holdStart=30, holdEnd=30.5)
        self.assertEqual(3, len(validate_meta(allowed, 41)))

        beyond = metadata()
        beyond["stages"][1]["end"] = 50
        beyond["stages"][2].update(start=50.2, end=65.2, holdStart=55, holdEnd=55.5)
        with self.assertRaises(ValueError):
            validate_meta(beyond, 66)

    def test_a_comfort_hold_shorter_than_one_and_a_half_seconds_is_rejected(self):
        self.meta["stages"][0]["holdStart"] = 1.5
        with self.assertRaises(ValueError):
            validate_meta(self.meta, 33.5)

    def test_wrong_start_and_an_immediate_endpoint_are_rejected(self):
        wrong_start = [dict(frame, rawMidi=67) if frame["t"] < 3.7 else frame
                       for frame in self.frames[1]]
        with self.assertRaisesRegex(ValueError, "sweep"):
            evaluate_stages(self.meta, [self.frames[0], wrong_start, self.frames[2]], 33.5)
        self.meta["stages"][1]["holdStart"] = 3.3
        with self.assertRaises(ValueError):
            validate_meta(self.meta, 33.5)


if __name__ == "__main__":
    unittest.main()
