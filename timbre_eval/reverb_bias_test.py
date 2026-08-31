# 곡 쪽 프로덕션 잔향 편차가 유사도 순위를 미는가 (미측정 가설 검증)
# wetness 프록시: 발성 종료 직후 에너지 감쇠 시간 (드라이=짧음, 리버브=김)
# 검정 1(허브): wetness가 큰 곡일수록 만곡과 평균적으로 가까운가
# 검정 2(사용자): 지유림 집 녹음(깨끗한 자)과의 유사도가 wetness를 따라가는가
import json
from pathlib import Path

import numpy as np
import librosa
from scipy.stats import spearmanr

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
SEP = HERE / "separated" / "htdemucs"
SR = 22050
HOP = 512  # ~23ms


def wetness(vocals_path):
    """유성 구간 종료 후 -20dB까지의 감쇠 시간(ms) 중앙값."""
    y, _ = librosa.load(vocals_path, sr=SR, mono=True, duration=180)
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=HOP)[0]
    voiced_lvl = np.percentile(rms, 85)
    hi, lo = voiced_lvl * 0.3, voiced_lvl * 0.03
    decays = []
    i = 0
    while i < len(rms) - 1:
        if rms[i] >= hi and rms[i + 1] < hi:  # 발성 종료 지점
            j = i + 1
            while j < len(rms) and rms[j] > lo and (j - i) < 90:
                j += 1
            if j < len(rms) and rms[j] <= lo:
                decays.append((j - i) * HOP / SR * 1000)
            i = j
        else:
            i += 1
    return float(np.median(decays)) if len(decays) >= 5 else None


tv, wet, snr = {}, {}, {}
for f in sorted(CACHE.glob("*.npy")):
    name = f.stem
    if name.startswith(("지유림", "정다운")) or "라이브" in name or "콘서트" in name:
        continue
    mf = CACHE / f"{name}.meta.json"
    if mf.exists():
        m = json.loads(mf.read_text(encoding="utf-8"))
        if m["track"].get("duet_suspect"):
            continue
        snr[name] = m["track"].get("snr_proxy_db")
    e = np.load(f)
    v = e.mean(0)
    tv[name] = v / np.linalg.norm(v)
    w = wetness(SEP / name / "vocals.wav")
    if w is not None:
        wet[name] = w

names = [n for n in tv if n in wet]
X = np.vstack([tv[n] for n in names])
S = X @ X.T
np.fill_diagonal(S, np.nan)
hub = np.nanmean(S, 1)
W = np.array([wet[n] for n in names])

rho_hub, p_hub = spearmanr(W, hub)
print(f"트랙 {len(names)}개 | wetness 중앙값 {np.median(W):.0f}ms (P10 {np.percentile(W,10):.0f} ~ P90 {np.percentile(W,90):.0f})")
print(f"[검정1] wetness ↔ 허브성(만곡 평균 유사도): ρ={rho_hub:.3f} (p={p_hub:.4f})")

u = np.load(CACHE / "지유림__밤편지.npy")
uv = u.mean(0)
uv /= np.linalg.norm(uv)
usim = np.array([float(uv @ tv[n]) for n in names])
rho_u, p_u = spearmanr(W, usim)
print(f"[검정2] wetness ↔ 지유림(집)과의 유사도: ρ={rho_u:.3f} (p={p_u:.4f})")

sn = np.array([snr.get(n) or np.nan for n in names])
ok = ~np.isnan(sn)
rho_x, _ = spearmanr(W[ok], sn[ok])
print(f"[교차확인] wetness ↔ SNR프록시: ρ={rho_x:.3f} (음의 상관이면 두 프록시가 서로 지지)")
print("\nwetness 상/하위 5:")
order = np.argsort(W)
for i in list(order[:5]) + list(order[-5:]):
    print(f"  {W[i]:5.0f}ms  허브 {hub[i]:.3f}  {names[i].replace('__',' · ')}")
