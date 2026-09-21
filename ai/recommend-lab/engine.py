"""Read-only lab around the production segment scorer and final-score composer."""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

import numpy as np
from sklearn.decomposition import PCA
from threadpoolctl import threadpool_limits

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "recommend-server"))
from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.contracts import MODEL_KEYS, SCORING_VERSION
from timbre_recommender.composition import (allowed_languages, track_language, compose_two_axis,
                                             compose_by_final_score, MAX_SONGS_PER_ARTIST)
from timbre_recommender.pitch_range import RangeCatalog, _validated_profile, MAX_OUTSIDE_RANGE_RATIO
from timbre_recommender.scoring import RankerConfig
from timbre_recommender.shelves import score_all_tracks, build_shelves, VOICE_MAX_KEY_SHIFT, MIN_PROFILE_CONFIDENCE

from adapters import matrices
from improvement import evaluate as evaluate_improvement
import genres as genre_rules

# 운영 상수를 그대로 읽어 온다. 여기 숫자를 따로 적어 두면 운영이 바뀌었을 때 실험실만 옛날 값으로 남는다.
DEFAULTS = {"ecapaWeight": .5, "centroidWeight": .3, "timbreWeight": .75,
            "maxKeyShift": VOICE_MAX_KEY_SHIFT, "maxOutsideRangeRatio": MAX_OUTSIDE_RANGE_RATIO,
            # 운영은 더 이상 핵심 음역 초과 0 을 요구하지 않는다(음역 밖 시간 비율로 대체). 기본값은 제한 없음.
            "maxCoreExcess": 12.0,
            "minRangeScore": .35, "minProfileConfidence": MIN_PROFILE_CONFIDENCE,
            "useRangeGate": True, "topK": 10, "compositionMode": "finalScore",
            # 장르 실험. 0 = 장르를 순위에 쓰지 않음.
            # genrePushdown: 선택 안 한 장르를 밀어내는 세기. 1.0 = 감점 전 1위보다 높아야 들어옴.
            # useLanguageGate: 현행 방식(장르→언어 변환 후 하드 필터). 대안에서는 끈다.
            "genrePushdown": 0.0, "selectedGenres": [], "useLanguageGate": False}


def centroid(x):
    v = np.asarray(x, dtype=np.float64).mean(axis=0)
    n = np.linalg.norm(v)
    if not np.isfinite(n) or n < 1e-12:
        raise ValueError("구간 평균이 영벡터이므로 지도에 투영할 수 없습니다.")
    return v / n


def _with_genre_penalty(row):
    """장르 감점을 두 축 점수에도 반영한 사본. 진단 표에는 원래 모델 점수를 남긴다.

    구 방식(2:2:1 축별 할당) 비교용. 그쪽은 선발이 축 점수로 이뤄지므로 최종 점수만 깎으면
    이미 뽑힌 곡들의 순서만 바뀐다. 현행(최종 점수 순)에서는 필요 없다.
    """
    penalty = row.get("genre_penalty") or 0.0
    if not penalty:
        return row
    models = {key: dict(value, absolute_score=value["absolute_score"] - penalty)
              for key, value in row["models"].items()}
    return dict(row, models=models)


class Engine:
    def __init__(self, directory):
        self.catalog = CatalogIndex.load(directory, verify_hashes=True)
        self.ranges = RangeCatalog.load(directory, verify_hashes=True,
                                        expected_track_ids={t.track_id for t in self.catalog.tracks})
        self.fingerprint = hashlib.sha256((Path(directory) / "index.json").read_bytes()).hexdigest()
        self.core_fingerprint = hashlib.sha256(b"".join(
            (Path(__file__).resolve().parents[1] / "recommend-server" / "timbre_recommender" / name).read_bytes()
            for name in ["scoring.py", "shelves.py", "composition.py", "pitch_range.py"])).hexdigest()
        if len(self.catalog.tracks) < 2:
            raise ValueError("지도에는 최소 두 곡이 필요합니다.")
        self.projectors, self.points = {}, []
        with threadpool_limits(limits=2):
            for key in MODEL_KEYS:
                vectors = np.array([centroid(self.catalog.embeddings(t, key)) for t in self.catalog.tracks])
                pca = PCA(n_components=2, svd_solver="randomized", random_state=42)
                coords = pca.fit_transform(vectors)
                self.projectors[key] = pca
                if not self.points:
                    self.points = [{"id": t.track_id, "title": t.title, "artist": t.artist} for t in self.catalog.tracks]
                for point, xy in zip(self.points, coords):
                    point[key] = xy.tolist()

    def info(self):
        batches = sorted({str((t.metadata or {}).get("batch_id")) for t in self.catalog.tracks if (t.metadata or {}).get("batch_id")})
        summary = genre_rules.catalog_summary(self.catalog.tracks)
        tagged = sum(1 for t in self.catalog.tracks if genre_rules.track_genres(t.metadata))
        return {"trackCount": len(self.catalog.tracks), "catalogFingerprint": self.fingerprint,
                "coreFingerprint": self.core_fingerprint, "scoringVersion": SCORING_VERSION,
                "batchIds": batches, "defaults": DEFAULTS,
                "composition": {"current": "finalScore", "artistCap": MAX_SONGS_PER_ARTIST,
                                "maxOutsideRangeRatio": MAX_OUTSIDE_RANGE_RATIO,
                                "voiceMaxKeyShift": VOICE_MAX_KEY_SHIFT},
                # 운영 번들의 scoring_version 라벨만 바꿔 읽는 사본이면 화면에 그대로 알린다.
                "relabeledFromScoringVersion": self.catalog.metadata.get("lab_relabeled_from_scoring_version"),
                "genres": {"labels": summary, "taggedTrackCount": tagged,
                           "untaggedTrackCount": len(self.catalog.tracks) - tagged,
                           "genericLabels": sorted(genre_rules.GENERIC_LABELS),
                           "languageGateRetired": True},
                "projection": {"method": "PCA normalized segment centroid seed 42",
                    "explainedVariance": {k: self.projectors[k].explained_variance_ratio_.tolist() for k in MODEL_KEYS}},
                "limitations": ["2차원 거리는 실제 구간 기반 추천 점수와 다릅니다.",
                    "모델 점수는 성공 확률이나 음색 정확도 지표가 아닙니다.",
                    "성별 메타데이터가 없어 성별 필터를 적용하지 않습니다.",
                    "장르 가산점은 실험실 전용 규칙이며 운영 추천에는 적용되지 않습니다."]}

    def project(self, query):
        x = matrices(query)
        return {k: self.projectors[k].transform(centroid(x[k]).reshape(1, -1))[0].tolist() for k in MODEL_KEYS}

    def run(self, case, settings):
        if case.get("catalogFingerprint") and case["catalogFingerprint"] != self.fingerprint:
            raise ValueError("저장 case와 현재 카탈로그 fingerprint가 다릅니다. 다른 배치를 혼용할 수 없습니다.")
        query = matrices(case["query"])
        profile = case.get("pitchProfile")
        profile = _validated_profile(profile, label="case") if profile else None
        range_available = bool(profile and profile.get("reliable", False))
        genres = case.get("genres") or []
        excluded = set(case.get("excludedTrackIds") or [])
        baseline = dict(DEFAULTS, topK=case.get("topK", 10),
                        timbreWeight=.3 if case.get("mode") == "range" else .75)
        with threadpool_limits(limits=2):
            a = self._rank(query, profile, range_available, genres, excluded, baseline, True)
            b = self._rank(query, profile, range_available, genres, excluded, dict(baseline, **settings), False)
            improvement_rows = score_all_tracks(
                self.catalog, self.ranges, query, query_profile=profile if range_available else None,
                config=RankerConfig(), mode="range" if case.get("mode") == "range" else "timbre", genres=genres,
            )
            improvement = evaluate_improvement(
                improvement_rows, baseline=a["recommendations"], profile=profile, range_available=range_available,
                ranges=self.ranges.profiles, excluded_track_ids=excluded, top_k=baseline["topK"],
            )
        # 현행 Top K 도 후보 설정의 선택 장르 기준으로 세야 "몇 곡이 줄었나"가 비교가 된다.
        candidate_names = genre_rules.canonical_set(b["genre"]["selected"])
        for side in (a, b):
            for rec in side["recommendations"]:
                rec["genreMatch"] = genre_rules.track_matches({"genres": rec.get("genres") or []}, candidate_names)
            side["genre"]["matchedInTopK"] = sum(1 for rec in side["recommendations"] if rec["genreMatch"])
            side["genre"]["breakthroughs"] = [{"title": rec["title"], "artist": rec["artist"],
                                               "genres": rec["genres"], "position": rec["position"]}
                                              for rec in side["recommendations"] if not rec["genreMatch"]]
        before = {p["track_id"]: p["position"] for p in a["recommendations"]}
        after = {p["track_id"]: p["position"] for p in b["recommendations"]}
        return {"caseId": case["caseId"], "inputKind": case.get("inputKind", "saved-case"),
                "rangeAvailable": range_available, "rangeFallbackReason": None if range_available else "검증된 음역 입력 없음 또는 신뢰도 부족",
                "catalogFingerprint": self.fingerprint, "coreFingerprint": self.core_fingerprint,
                "baseline": a, "candidate": b,
                "improvement": improvement,
                "changes": [{"id": tid, "before": before.get(tid), "after": after.get(tid)}
                            for tid in sorted(set(before) | set(after))],
                "overlapAtK": len(set(before) & set(after)), "userPoints": self.project(case["query"])}

    def _rank(self, query, profile, available, genres, excluded, cfg, baseline):
        use_range = available and cfg["useRangeGate"]
        # Scores use the existing production core; no inference, GPU, globals or DB writes.
        rows = score_all_tracks(self.catalog, self.ranges, query,
                                query_profile=profile if available else None,
                                config=RankerConfig(base_ecapa_weight=cfg["ecapaWeight"],
                                    centroid_weight=cfg["centroidWeight"], segment_weight=1-cfg["centroidWeight"]),
                                mode="range" if cfg["timbreWeight"] == .3 else "timbre")
        selected = genre_rules.selected_labels(None if baseline else cfg.get("selectedGenres"), genres)
        selected_names = genre_rules.canonical_set(selected)
        pushdown = 0.0 if baseline else float(cfg.get("genrePushdown") or 0.0)
        # 현행은 장르를 언어로 바꿔 하드 필터를 건다. 대안은 그 단계를 쓰지 않는다.
        use_language_gate = True if baseline else bool(cfg.get("useLanguageGate"))
        allowed = allowed_languages(selected) if use_language_gate else None
        for r in rows:
            fit = r["range_fit"]
            # 감점 전 점수. 감점 크기의 기준(Top-K 경쟁 구간)을 이 점수들로 잡는다.
            r["base_final_score"] = (cfg["timbreWeight"]*r["timbre_score"] + (1-cfg["timbreWeight"])*fit["score"]
                                     if available and fit else r["timbre_score"])
        language_excluded = 0
        gated = []
        for r in rows:
            fit = r["range_fit"]
            reasons = []
            if r["track_id"] in excluded:
                reasons.append({"code": "SOURCE_OR_EXCLUDED", "message": "입력 case의 제외 곡 또는 원곡"})
            lang = track_language(r["metadata"])
            if allowed is not None and lang is not None and lang not in allowed:
                reasons.append({"code": "LANGUAGE", "message": f"언어 {lang}: 허용 {','.join(sorted(allowed))}"})
                language_excluded += 1
            if r["is_rap"]:
                reasons.append({"code": "RAP", "message": "목소리 shelf에서 랩 제외"})
            if use_range:
                cand = self.ranges.profiles.get(r["track_id"], {})
                if not fit:
                    reasons.append({"code": "RANGE_MISSING", "message": "곡 음역 프로파일 없음"})
                else:
                    if not cand.get("reliable", True):
                        reasons.append({"code": "PROFILE_UNRELIABLE", "message": "곡 음역 reliable=false"})
                    confidence = float(cand.get("confidence", 1))
                    if confidence < cfg["minProfileConfidence"]:
                        reasons.append({"code": "PROFILE_CONFIDENCE", "message": f"곡 신뢰도 {confidence:.3f} < {cfg['minProfileConfidence']:.3f}"})
                    # 운영의 현행 음역 게이트. 핵심 음역 초과 반음이 아니라 음역 밖에 머무는 시간 비율이다.
                    outside = float(fit.get("outsideRangeRatio", 1.0 - float(fit.get("coverage", 0.0))))
                    if outside > cfg["maxOutsideRangeRatio"]:
                        reasons.append({"code": "OUTSIDE_RANGE", "message": f"음역 밖 시간 비율 {outside*100:.1f}% > {cfg['maxOutsideRangeRatio']*100:.1f}%"})
                    if fit["totalCoreExcessSemitones"] > cfg["maxCoreExcess"]:
                        reasons.append({"code": "CORE_EXCESS", "message": f"핵심 음역 초과 {fit['totalCoreExcessSemitones']:.2f}반음 > {cfg['maxCoreExcess']:.2f} (실험 전용 조건)"})
                    if abs(int(fit["keyShift"])) > cfg["maxKeyShift"]:
                        reasons.append({"code": "KEY_SHIFT", "message": f"최적 키 {int(fit['keyShift']):+d}반음: 허용 ±{cfg['maxKeyShift']} 초과"})
                    if fit["score"] < cfg["minRangeScore"]:
                        reasons.append({"code": "RANGE_SCORE", "message": f"음역 점수 {fit['score']:.3f} < {cfg['minRangeScore']:.3f}"})
            gated.append((r, reasons))

        # 감점 기준은 실제로 Top-K 를 놓고 겨루는 곡들(게이트 통과)의 점수 폭이다.
        eligible = [r for r, reasons in gated if not reasons]
        penalty, band, band_top, band_cutoff = genre_rules.penalty_unit(
            (r["base_final_score"] for r in eligible), pushdown, cfg["topK"])
        diagnostics = []
        for r, reasons in gated:
            # In production unreliable/missing range falls back to timbre only.
            r["genre_match"] = genre_rules.track_matches(r["metadata"], selected_names)
            r["genre_penalty"] = 0.0 if r["genre_match"] else penalty
            r["final_score"] = r["base_final_score"] - r["genre_penalty"]
            diagnostics.append({"id": r["track_id"], "title": r["title"], "artist": r["artist"],
                "scores": {k: r["models"][k]["absolute_score"] for k in MODEL_KEYS},
                "timbreScore": r["timbre_score"], "finalScore": r["final_score"], "rangeFit": r["range_fit"],
                "genres": genre_rules.track_genres(r["metadata"]), "genreMatch": r["genre_match"],
                "genrePenalty": round(r["genre_penalty"], 6),
                "eligible": not reasons, "exclusions": reasons})
        if baseline:
            # Baseline selection/order is delegated to production, not reconstructed.
            allowed_rows = [r for r in rows if r["track_id"] not in excluded and
                            (allowed is None or track_language(r["metadata"]) is None or track_language(r["metadata"]) in allowed)]
            recs = build_shelves(allowed_rows, top_k=cfg["topK"], genres=genres, range_enabled=available, seed=42)[0]["items"]
        elif cfg["compositionMode"] == "finalScore":
            # 현행 조립 방식. 운영 함수를 그대로 부르므로 가수 상한(최대 2곡)도 같이 적용된다.
            # 장르 가산점은 final_score 에 이미 들어 있어 어떤 곡이 뽑히는지를 직접 바꾼다.
            recs = compose_by_final_score(eligible, top_k=cfg["topK"], range_enabled=use_range,
                                          min_range_score=cfg["minRangeScore"], scoring_version=SCORING_VERSION)["recommendations"]
        else:
            # 구 방식(2:2:1 축별 할당) 비교용. 여기서는 선발이 축 점수로 이뤄지므로 감점을 축 점수에도
            # 같이 실어야 선발 단계까지 장르가 영향을 준다. 최종 점수만 깎으면 순서만 바뀐다.
            recs = compose_two_axis([_with_genre_penalty(r) for r in eligible], top_k=cfg["topK"], range_enabled=use_range,
                                    min_range_score=cfg["minRangeScore"], scoring_version=SCORING_VERSION)["recommendations"]
        rows_by_id = {r["track_id"]: r for r in rows}
        for rec in recs:
            row = rows_by_id.get(rec["track_id"])
            rec["genres"] = genre_rules.track_genres(row["metadata"]) if row else []
            rec["genreMatch"] = bool(row and row["genre_match"])
        lists = {}
        for key in MODEL_KEYS:
            lists[key] = [dict(d, rank=i+1) for i, d in enumerate(sorted(diagnostics, key=lambda d: (-d["scores"][key], d["id"])))]
        return {"settings": cfg, "recommendations": recs, "rankings": lists,
                "eligibleCount": sum(d["eligible"] for d in diagnostics), "excludedCount": sum(not d["eligible"] for d in diagnostics),
                "rangeGateApplied": use_range,
                "genre": {"selected": selected, "canonical": sorted(selected_names),
                          "unknownLabels": genre_rules.unknown_labels(selected),
                          "source": "experiment" if (not baseline and cfg.get("selectedGenres")) else "case",
                          "pushdown": pushdown, "penaltyPerMiss": round(penalty, 6),
                          "band": round(band, 6), "bandTop": round(band_top, 6), "bandCutoff": round(band_cutoff, 6),
                          "languageGate": use_language_gate, "languageExcludedCount": language_excluded,
                          "matchedCatalogCount": sum(1 for r, _ in gated if r["genre_match"]),
                          "matchedEligibleCount": sum(1 for r in eligible if r["genre_match"]),
                          "untaggedEligibleCount": sum(1 for r in eligible if not genre_rules.has_known_genre(r["metadata"])),
                          "matchedInTopK": sum(1 for rec in recs if rec["genreMatch"]),
                          # 장르가 다른데도 음색으로 뚫고 들어온 곡. 하드 필터가 아니라는 증거다.
                          "breakthroughs": [{"title": rec["title"], "artist": rec["artist"],
                                             "genres": rec["genres"], "position": rec["position"]}
                                            for rec in recs if not rec["genreMatch"]]}}
