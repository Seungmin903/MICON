"""Read-only lab around the production segment scorer and two-axis composer."""
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
from timbre_recommender.composition import allowed_languages, track_language, compose_two_axis
from timbre_recommender.pitch_range import RangeCatalog, _validated_profile
from timbre_recommender.scoring import RankerConfig
from timbre_recommender.shelves import score_all_tracks, build_shelves, VOICE_MAX_KEY_SHIFT, MIN_PROFILE_CONFIDENCE

from adapters import matrices
from improvement import evaluate as evaluate_improvement
import genres as genre_rules

DEFAULTS = {"ecapaWeight": .5, "centroidWeight": .3, "timbreWeight": .75,
            "maxKeyShift": VOICE_MAX_KEY_SHIFT, "maxCoreExcess": 0.0,
            "minRangeScore": .35, "minProfileConfidence": MIN_PROFILE_CONFIDENCE,
            "useRangeGate": True, "topK": 10, "compositionMode": "twoAxisQuota",
            # 0 = 현행. 장르는 언어 게이트로만 쓰이고 곡 선택에는 영향을 주지 않는다.
            "genreWeight": 0.0, "preferredGenres": []}


def centroid(x):
    v = np.asarray(x, dtype=np.float64).mean(axis=0)
    n = np.linalg.norm(v)
    if not np.isfinite(n) or n < 1e-12:
        raise ValueError("구간 평균이 영벡터이므로 지도에 투영할 수 없습니다.")
    return v / n


def _with_genre_bonus(row):
    """장르 가산점을 두 축 점수에도 반영한 사본. 진단 표에는 원래 모델 점수를 남긴다."""
    bonus = row.get("genre_bonus") or 0.0
    if not bonus:
        return row
    models = {key: dict(value, absolute_score=value["absolute_score"] + bonus)
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
                "genres": {"labels": summary, "taggedTrackCount": tagged,
                           "untaggedTrackCount": len(self.catalog.tracks) - tagged,
                           "genericKeys": sorted(genre_rules.GENERIC_KEYS)},
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
        # 현행 Top K 도 후보 설정의 선호 장르 기준으로 세야 "몇 곡이 늘었나"가 비교가 된다.
        candidate_keys = genre_rules.genre_keys(b["genre"]["preferred"])
        for side in (a, b):
            for rec in side["recommendations"]:
                rec["genreMatch"] = genre_rules.track_matches({"genres": rec.get("genres") or []}, candidate_keys)
            side["genre"]["matchedInTopK"] = sum(1 for rec in side["recommendations"] if rec["genreMatch"])
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
        # 선호 장르는 현행대로 언어 게이트를 그대로 태우고(0% 결과 = 현행), 가산점만 실험값이다.
        preferred = genre_rules.preferred_labels(None if baseline else cfg.get("preferredGenres"), genres)
        preferred_keys = genre_rules.genre_keys(preferred)
        weight = 0.0 if baseline else float(cfg.get("genreWeight") or 0.0)
        allowed = allowed_languages(preferred)
        for r in rows:
            fit = r["range_fit"]
            # 장르 가산점 이전의 점수. 가산점 크기를 이 분포 폭에 맞춘다.
            r["base_final_score"] = (cfg["timbreWeight"]*r["timbre_score"] + (1-cfg["timbreWeight"])*fit["score"]
                                     if available and fit else r["timbre_score"])
        genre_bonus, score_span = genre_rules.bonus_unit((r["base_final_score"] for r in rows), weight)
        language_excluded = 0
        diagnostics = []
        eligible = []
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
                    if fit["totalCoreExcessSemitones"] > cfg["maxCoreExcess"]:
                        reasons.append({"code": "CORE_EXCESS", "message": f"핵심 음역 초과 {fit['totalCoreExcessSemitones']:.2f}반음 > {cfg['maxCoreExcess']:.2f}"})
                    if abs(int(fit["keyShift"])) > cfg["maxKeyShift"]:
                        reasons.append({"code": "KEY_SHIFT", "message": f"최적 키 {int(fit['keyShift']):+d}반음: 허용 ±{cfg['maxKeyShift']} 초과"})
                    if fit["score"] < cfg["minRangeScore"]:
                        reasons.append({"code": "RANGE_SCORE", "message": f"음역 점수 {fit['score']:.3f} < {cfg['minRangeScore']:.3f}"})
            # In production unreliable/missing range falls back to timbre only.
            r["genre_match"] = genre_rules.track_matches(r["metadata"], preferred_keys)
            r["genre_bonus"] = genre_bonus if r["genre_match"] else 0.0
            r["final_score"] = r["base_final_score"] + r["genre_bonus"]
            diagnostics.append({"id": r["track_id"], "title": r["title"], "artist": r["artist"],
                "scores": {k: r["models"][k]["absolute_score"] for k in MODEL_KEYS},
                "timbreScore": r["timbre_score"], "finalScore": r["final_score"], "rangeFit": fit,
                "genres": genre_rules.track_genres(r["metadata"]), "genreMatch": r["genre_match"],
                "genreBonus": round(r["genre_bonus"], 6),
                "eligible": not reasons, "exclusions": reasons})
            if not reasons:
                eligible.append(r)
        if baseline:
            # Baseline selection/order is delegated to production, not reconstructed.
            allowed_rows = [r for r in rows if r["track_id"] not in excluded and
                            (allowed is None or track_language(r["metadata"]) is None or track_language(r["metadata"]) in allowed)]
            recs = build_shelves(allowed_rows, top_k=cfg["topK"], genres=genres, range_enabled=available, seed=42)[0]["items"]
        elif cfg["compositionMode"] == "finalScore":
            ordered = sorted(eligible, key=lambda item: (-float(item["final_score"]), item["track_id"]))[:cfg["topK"]]
            recs = [{
                "position": position, "track_id": item["track_id"], "title": item["title"], "artist": item["artist"],
                "axis": "FINAL_SCORE", "label": "축별 할당 없이 최종 점수 순", "timbre_score": item["timbre_score"],
                "range_fit": item["range_fit"] if use_range else None,
                "recommended_key": int(item["range_fit"]["keyShift"]) if use_range and item["range_fit"] else None,
                "final_score": item["final_score"],
            } for position, item in enumerate(ordered, start=1)]
        else:
            # 가산점을 축 점수에도 같이 실어야 할당(2:2:1) 단계의 **선발**까지 장르가 영향을 준다.
            # 최종 점수에만 더하면 이미 뽑힌 10곡의 순서만 바뀐다.
            recs = compose_two_axis([_with_genre_bonus(r) for r in eligible], top_k=cfg["topK"], range_enabled=use_range,
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
                "genre": {"preferred": preferred, "unknownLabels": genre_rules.unknown_labels(preferred),
                          "source": "experiment" if (not baseline and cfg.get("preferredGenres")) else "case",
                          "weight": weight, "bonusPerMatch": round(genre_bonus, 6), "scoreSpan": round(score_span, 6),
                          "matchedCatalogCount": sum(1 for r in rows if r["genre_match"]),
                          "matchedEligibleCount": sum(1 for r in eligible if r["genre_match"]),
                          "languageExcludedCount": language_excluded,
                          "matchedInTopK": sum(1 for rec in recs if rec["genreMatch"])}}
