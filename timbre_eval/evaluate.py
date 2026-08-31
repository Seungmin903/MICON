# 분리된 보컬 -> 4개 모델 임베딩 -> 같은가수/다른가수 유사도 분포 + EER 리포트
# 사용: python evaluate.py [--models ecapa wavlm mert singer_id]
import argparse
import itertools
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
import librosa
import torch

HERE = Path(__file__).resolve().parent
SEP = HERE / "separated" / "htdemucs"
RESULTS = HERE / "results"
RESULTS.mkdir(exist_ok=True)

SR = 44100
CHUNK_S = 5.0
MAX_CHUNKS = 24
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


def load_chunks(vocals_path):
    """보컬 wav -> 노래가 실제로 있는 5초 청크들 (44.1k mono)."""
    y, _ = librosa.load(vocals_path, sr=SR, mono=True)
    n = int(CHUNK_S * SR)
    # 프레임 RMS 기준: 트랙 상위 90퍼센타일의 15% 이상이면 유성 프레임으로 간주
    frame = 2048
    rms = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms, 90) * 0.15
    chunks = []
    for i in range(0, len(y) - n, n):
        seg = y[i : i + n]
        seg_rms = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        if (seg_rms > thr).mean() > 0.6:
            chunks.append(seg)
    return chunks[:MAX_CHUNKS]


# ---- 모델별 임베더: fn(list[np.ndarray @44.1k]) -> np.ndarray [N, D] ----

def embed_ecapa(chunks):
    from speechbrain.inference import EncoderClassifier
    from speechbrain.utils.fetching import LocalStrategy

    clf = EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        savedir=str(HERE / "models" / "ecapa"),
        run_opts={"device": DEVICE},
        local_strategy=LocalStrategy.COPY,  # Windows: symlink 권한 없음
    )
    out = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=16000)
        with torch.no_grad():
            e = clf.encode_batch(torch.tensor(w, device=DEVICE)[None])
        out.append(e.squeeze().cpu().numpy())
    return np.stack(out)


def embed_wavlm(chunks):
    from transformers import Wav2Vec2FeatureExtractor, WavLMForXVector

    fe = Wav2Vec2FeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
    model = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv").to(DEVICE).eval()
    out = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=16000)
        inputs = fe(w, sampling_rate=16000, return_tensors="pt").to(DEVICE)
        with torch.no_grad():
            e = model(**inputs).embeddings
        out.append(e.squeeze().cpu().numpy())
    del model
    torch.cuda.empty_cache()
    return np.stack(out)


def embed_mert(chunks):
    from transformers import AutoModel, Wav2Vec2FeatureExtractor

    fe = Wav2Vec2FeatureExtractor.from_pretrained("m-a-p/MERT-v1-95M", trust_remote_code=True)
    model = AutoModel.from_pretrained("m-a-p/MERT-v1-95M", trust_remote_code=True).to(DEVICE).eval()
    out = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=24000)
        inputs = fe(w, sampling_rate=24000, return_tensors="pt").to(DEVICE)
        with torch.no_grad():
            h = model(**inputs).last_hidden_state  # [1, T, 768]
        out.append(h.mean(dim=1).squeeze().cpu().numpy())
    del model
    torch.cuda.empty_cache()
    return np.stack(out)


def make_singer_id(variant):
    def f(chunks):
        import sys

        sys.path.insert(0, str(HERE / "ssl-singer-identity"))
        from huggingface_hub import hf_hub_download
        from singer_identity import load_model

        # HF 캐시에 직접 받고 로컬 경로로 로드 (fetch()의 Windows symlink 회피)
        hp = hf_hub_download("BernardoTorres/singer-identity", f"{variant}/hyperparams.yaml")
        hf_hub_download("BernardoTorres/singer-identity", f"{variant}/model.pt")
        model = load_model(variant, source=str(Path(hp).parent.parent)).to(DEVICE).eval()
        out = []
        for c in chunks:
            with torch.no_grad():
                e = model(torch.tensor(c, device=DEVICE)[None])
            out.append(e.squeeze().cpu().numpy())
        del model
        torch.cuda.empty_cache()
        return np.stack(out)

    return f


def embed_muq(chunks):
    from muq import MuQ

    model = MuQ.from_pretrained("OpenMuQ/MuQ-large-msd-iter").to(DEVICE).eval()
    out = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=24000)
        with torch.no_grad():
            o = model(torch.tensor(w, device=DEVICE)[None])
        out.append(o.last_hidden_state.mean(dim=1).squeeze().cpu().numpy())
    del model
    torch.cuda.empty_cache()
    return np.stack(out)


def embed_facodec(chunks):
    from huggingface_hub import hf_hub_download
    from ns3_codec import FACodecDecoder, FACodecEncoder

    enc = FACodecEncoder(ngf=32, up_ratios=[2, 4, 5, 5], out_channels=256)
    dec = FACodecDecoder(
        in_channels=256, upsample_initial_channel=1024, ngf=32,
        up_ratios=[5, 5, 4, 2], vq_num_q_c=2, vq_num_q_p=1, vq_num_q_r=3,
        vq_dim=256, codebook_dim=8, codebook_size_prosody=10,
        codebook_size_content=10, codebook_size_residual=10,
        use_gr_x_timbre=True, use_gr_residual_f0=True, use_gr_residual_phone=True,
    )
    enc.load_state_dict(torch.load(
        hf_hub_download("amphion/naturalspeech3_facodec", "ns3_facodec_encoder.bin"),
        map_location="cpu"))
    dec.load_state_dict(torch.load(
        hf_hub_download("amphion/naturalspeech3_facodec", "ns3_facodec_decoder.bin"),
        map_location="cpu"))
    enc, dec = enc.to(DEVICE).eval(), dec.to(DEVICE).eval()
    out = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=16000)
        x = torch.tensor(w, device=DEVICE)[None, None]
        with torch.no_grad():
            e = enc(x)
            _, _, _, _, spk = dec(e, eval_vq=False, vq=True)  # spk = timbre 임베딩
        out.append(spk.squeeze().cpu().numpy())
    del enc, dec
    torch.cuda.empty_cache()
    return np.stack(out)


EMBEDDERS = {
    "ecapa": embed_ecapa,
    "wavlm": embed_wavlm,
    "mert": embed_mert,
    "singer_id": make_singer_id("byol"),
    "singer_id_cvc": make_singer_id("contrastive-vc"),
    "singer_id_uni": make_singer_id("uniformity"),
    "muq": embed_muq,
    "facodec": embed_facodec,
}


def eer(pos, neg):
    """pos=같아야 하는 쌍 유사도, neg=달라야 하는 쌍 유사도."""
    scores = np.concatenate([pos, neg])
    labels = np.concatenate([np.ones_like(pos), np.zeros_like(neg)])
    order = np.argsort(-scores)
    labels = labels[order]
    tpr = np.cumsum(labels == 1) / (labels == 1).sum()
    fpr = np.cumsum(labels == 0) / (labels == 0).sum()
    frr = 1 - tpr
    idx = np.argmin(np.abs(frr - fpr))
    return float((frr[idx] + fpr[idx]) / 2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=list(EMBEDDERS), choices=list(EMBEDDERS))
    args = ap.parse_args()

    EXCLUDE_SINGERS = {"지유림"}  # 폰 녹음: 도메인이 달라 스튜디오 벤치마크에서 제외 (domain_gap.py 전용)
    tracks = {}  # name -> (singer, song, chunks)
    for d in sorted(SEP.iterdir()):
        v = d / "vocals.wav"
        if not v.exists():
            continue
        singer, song = d.name.split("__", 1)
        if singer in EXCLUDE_SINGERS:
            continue
        chunks = load_chunks(v)
        print(f"{d.name}: {len(chunks)} chunks")
        tracks[d.name] = (singer, song, chunks)

    report_path = RESULTS / "report.json"
    report = json.loads(report_path.read_text(encoding="utf-8")) if report_path.exists() else {}
    for mname in args.models:
        print(f"\n=== {mname} ===")
        try:
            embs = {}
            for name, (singer, song, chunks) in tracks.items():
                cache_f = HERE / "emb_cache" / mname / f"{name}.npy"
                if cache_f.exists():
                    e = np.load(cache_f)
                else:
                    e = EMBEDDERS[mname](chunks)
                    e = e / np.linalg.norm(e, axis=1, keepdims=True)
                    cache_f.parent.mkdir(parents=True, exist_ok=True)
                    np.save(cache_f, e)
                embs[name] = e
                print(f"  {name}: {e.shape}")
        except Exception as ex:
            print(f"  FAILED: {ex}")
            report[mname] = {"error": str(ex)}
            continue

        cat_sims = defaultdict(list)
        track_table = []
        for (n1, n2) in itertools.combinations(tracks, 2):
            s1, g1, _ = tracks[n1]
            s2, g2, _ = tracks[n2]
            if s1 == s2 and g1 == g2:
                continue
            if s1 == s2:
                cat = "same_singer_diff_song"
            elif g1 == g2:
                cat = "diff_singer_same_song"
            else:
                cat = "diff_singer_diff_song"
            sims = (embs[n1] @ embs[n2].T).ravel()
            cat_sims[cat].extend(sims.tolist())
            track_table.append((n1, n2, cat, float(sims.mean())))

        pos = np.array(cat_sims["same_singer_diff_song"])
        neg_hard = np.array(cat_sims["diff_singer_same_song"])
        neg_all = np.concatenate([neg_hard, np.array(cat_sims["diff_singer_diff_song"])])
        report[mname] = {
            "n_pairs": {k: len(v) for k, v in cat_sims.items()},
            "mean_sim": {k: float(np.mean(v)) for k, v in cat_sims.items()},
            "std_sim": {k: float(np.std(v)) for k, v in cat_sims.items()},
            "eer_hard": eer(pos, neg_hard),
            "eer_all": eer(pos, neg_all),
            "track_pairs": track_table,
        }
        print(f"  same_singer={pos.mean():.3f}  diff_singer_same_song={neg_hard.mean():.3f}"
              f"  EER(hard)={report[mname]['eer_hard']:.3f}")

        # 히스토그램
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        plt.figure(figsize=(7, 4))
        for cat, color in [("same_singer_diff_song", "tab:green"),
                           ("diff_singer_same_song", "tab:red"),
                           ("diff_singer_diff_song", "tab:gray")]:
            plt.hist(cat_sims[cat], bins=40, alpha=0.55, density=True, label=cat, color=color)
        plt.title(f"{mname}  EER(hard)={report[mname]['eer_hard']:.3f}")
        plt.xlabel("cosine similarity")
        plt.legend(fontsize=8)
        plt.tight_layout()
        plt.savefig(RESULTS / f"{mname}_hist.png", dpi=120)
        plt.close()

    (RESULTS / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nsaved:", RESULTS / "report.json")


if __name__ == "__main__":
    main()
