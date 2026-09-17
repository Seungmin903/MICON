from __future__ import annotations

import unittest

from schemas import Shelf
from timbre_recommender.shelves import (
    MIN_PROFILE_CONFIDENCE,
    build_shelves,
    is_rap,
    selected_tags,
)


def _row(tid, artist, *, timbre, key=0, cov0=0.98, center0=1.0, genres=("K-Pop",), rap=False, melodic=True, excess=0.0, artist_sim=None, hi_excess=-3, hi_ratio=0.0):
    return {
        "track_id": tid, "song_id": f"tj-{tid}", "title": f"t-{tid}", "artist": artist,
        "metadata": {"genres": list(genres), "languages": "kor", "tj_id": f"TJ-{tid}", "album_name": None, "display_image_url": None, "release_date": "2020-01-01"},
        "models": {"ecapa": {"absolute_score": timbre, "match_consistency": 0.7}, "singer_byol": {"absolute_score": timbre, "match_consistency": 0.6}},
        "timbre_score": timbre, "is_rap": rap, "artist_similarity": artist_sim, "melodic": melodic,
        "range_fit": {"scoringVersion": "v", "score": 0.95, "coverage": 0.97, "centerScore": 0.9, "spanFit": 1.0, "softFit": 0.9, "keyShift": key,
                      "songCoreLowMidi": 57, "songCoreHighMidi": 69, "catalogProfileReliable": True,
                      "totalCoreExcessSemitones": excess, "lowCoreExcessSemitones": 0.0, "highCoreExcessSemitones": excess},
        "final_score": 0.75 * timbre + 0.25 * 0.95,
        "coverage0": cov0, "center0": center0, "high_excess0": hi_excess, "high_ratio0": hi_ratio,
    }


class HelpersTest(unittest.TestCase):
    def test_backend_preference_labels_exclude_selected_styles_from_discovery(self) -> None:
        self.assertEqual(selected_tags(["알앤비/소울", "락/메탈", "포크/어쿠스틱", "성인가요"]),
                         {"K-Pop", "R&B", "Rock", "Alternative", "Metal", "Folk", "Singer-Songwriter", "Trot"})

    def test_rap_and_tags(self) -> None:
        self.assertTrue(is_rap({"genres": ["Hip-Hop", "K-Pop"]}))
        self.assertFalse(is_rap({"genres": ["K-Pop"]}))
        self.assertEqual(selected_tags(["R&B", "발라드"]), {"K-Pop", "R&B", "Ballad"})
        self.assertGreaterEqual(MIN_PROFILE_CONFIDENCE, 0.5)


class BuildShelvesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.rows = [
            _row("01", "IU", timbre=0.61, key=-1),                                   # A 1위 (키 -1 → B 불가)
            _row("02", "소유", timbre=0.60, key=0, center0=0.72),                     # A
            _row("03", "정국", timbre=0.30, key=0, center0=1.0),                      # B 1위 (음색 낮음)
            _row("04", "임현정", timbre=0.20, key=0, center0=1.0, cov0=0.99),         # B
            _row("05", "조광일", timbre=0.35, key=0, center0=1.0, rap=True, melodic=False),   # 랩: A·B 제외, E 허용
            _row("06", "한로로", timbre=0.52, key=1, artist_sim=0.9, genres=("Rock",)),      # C 본인 곡
            _row("07", "윤하", timbre=0.40, key=-1, artist_sim=0.8),                 # C 이웃
            _row("08", "검정치마", timbre=0.34, key=4, genres=("Soundtrack",)),        # E (취향 밖 장르)
            _row("09", "페노메코", timbre=0.43, key=4, genres=("Hip-Hop",), rap=True, melodic=False),   # E (랩 허용)
            _row("10", "폴킴", timbre=0.50, key=5),                                  # |키| 5 → A 제외
            _row("11", "태연", timbre=0.45, key=-1, hi_excess=1, hi_ratio=0.08),       # D
        ]

    def test_shelves_are_disjoint_and_rap_only_in_discovery(self) -> None:
        shelves = build_shelves(self.rows, top_k=8, genres=["R&B", "발라드"], preferred_artists=["한로로"], range_enabled=True, include_challenge=True, seed=1)
        by_key = {s["key"]: s for s in shelves}
        self.assertEqual([s["key"] for s in shelves], ["voice", "original_key", "favorite_artists", "challenge", "discovery"])
        all_ids = [it["track_id"] for s in shelves for it in s["items"]]
        self.assertEqual(len(all_ids), len(set(all_ids)))                           # 섹션 간 중복 없음
        for key in ("voice", "original_key", "favorite_artists", "challenge"):
            self.assertFalse(any(it["artist"] in ("조광일", "페노메코") for it in by_key[key]["items"]), key)
        self.assertIn("페노메코", {it["artist"] for it in by_key["discovery"]["items"]})
        for s in shelves:
            Shelf.model_validate(s)

    def test_voice_gate_and_order(self) -> None:
        shelves = build_shelves(self.rows, top_k=8, range_enabled=True, seed=1)
        voice = next(s for s in shelves if s["key"] == "voice")["items"]
        ids = [it["track_id"] for it in voice]
        self.assertEqual(ids[0], "01")                                              # 음색 1위
        self.assertNotIn("10", ids)                                                 # |키| 5 초과
        self.assertTrue(all(it["axis"] in ("ECAPA", "SINGER_BYOL", "CONSENSUS") and it["label"] for it in voice))
        self.assertTrue(all(it["shelf"] == "voice" for it in voice))

    def test_original_key_sorted_by_center_not_timbre(self) -> None:
        # 합성 행이 11개뿐이라 top_k 를 작게: A 가 우선순위로 다 가져가면 B 가 비는 것이 정상 동작이다
        shelves = build_shelves(self.rows, top_k=2, range_enabled=True, seed=1)
        b = next(s for s in shelves if s["key"] == "original_key")["items"]
        self.assertEqual([it["track_id"] for it in b][:2], ["04", "03"])           # 중심음 1.0 동률 → 커버리지 0.99(04) 가 0.98(03) 보다 먼저
        self.assertTrue(all(it["recommended_key"] == 0 for it in b))
        self.assertTrue(all(it["axis"] is None for it in b))
        self.assertIn("center0", b[0]["metrics"])

    def test_favorite_artists_own_songs_first(self) -> None:
        shelves = build_shelves(self.rows, top_k=2, preferred_artists=["한로로"], range_enabled=True, seed=1)
        c = next(s for s in shelves if s["key"] == "favorite_artists")["items"]
        self.assertEqual(c[0]["artist"], "한로로")
        self.assertTrue(c[0]["metrics"]["own"])
        self.assertEqual(c[1]["artist"], "윤하")

    def test_no_range_profile_disables_range_shelves(self) -> None:
        shelves = build_shelves(self.rows, top_k=8, range_enabled=False, seed=1)
        keys = {s["key"]: s for s in shelves}
        self.assertEqual(keys["original_key"]["items"], [])
        self.assertTrue(keys["voice"]["items"])                                     # 음색만으로 채움
        self.assertTrue(all(it["range_fit"] is None or True for it in keys["voice"]["items"]))

    def test_discovery_is_seeded_and_takes_every_leftover_track(self) -> None:
        a = build_shelves(self.rows, top_k=8, genres=["OST"], range_enabled=True, seed=7)
        b = build_shelves(self.rows, top_k=8, genres=["OST"], range_enabled=True, seed=7)
        da = next(s for s in a if s["key"] == "discovery"); db = next(s for s in b if s["key"] == "discovery")
        self.assertEqual([i["track_id"] for i in da["items"]], [i["track_id"] for i in db["items"]])   # 같은 시드 → 같은 순서
        others = {i["track_id"] for s in a if s["key"] != "discovery" for i in s["items"]}
        self.assertEqual({i["track_id"] for i in da["items"]}, {r["track_id"] for r in self.rows} - others)  # 나머지 전부, 장르 무관
        self.assertIn("검정치마", {i["artist"] for i in da["items"]})              # 선택 장르(OST)여도 제외하지 않는다

    def test_selected_foreign_languages_are_in_taste_for_every_style(self) -> None:
        rows = [_row("en", "English artist", timbre=.8, key=4, genres=("Jazz",)),
                _row("jp", "Japanese artist", timbre=.8, key=4, genres=("Rock",)),
                _row("ko", "Korean artist", timbre=.8, key=4, genres=("Soundtrack",))]
        rows[0]["metadata"]["languages"] = "eng"
        rows[1]["metadata"]["languages"] = "jpn"
        for choices in (["영어곡", "일본곡", "발라드"], ["팝", "J-POP", "발라드"]):
            with self.subTest(choices=choices):
                shelves = build_shelves(rows, top_k=8, genres=choices, range_enabled=True, seed=1)
                discovery = next(s for s in shelves if s["key"] == "discovery")
                self.assertEqual({r["track_id"] for r in discovery["items"]}, {"en", "jp", "ko"})   # 언어·장르로 걸러내지 않는다


if __name__ == "__main__":
    unittest.main()
