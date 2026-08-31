# '내게 사랑이 뭐냐고 물어본다면'이 실제 192차원에서 누구 평균에 가까운가 + 오염 점검
from itertools import combinations
from pathlib import Path

import numpy as np

C = Path("timbre_eval/emb_cache_v2/ecapa")

def load(t):
    return np.load(C / (t + ".npy"))

def tv(e):
    m = e.mean(0)
    return m / np.linalg.norm(m)

target = "로이킴__내게 사랑이 뭐냐고 물어본다면"
roy_others = ["로이킴__봄이 와도", "로이킴__잘 지내자, 우리(여름날우리 X 로이킴)"]
han = ["한동근__그대라는 사치", "한동근__이 소설의 끝을 다시 써보려 해"]

def norm(v):
    return v / np.linalg.norm(v)

t_vec = tv(load(target))
roy_c = norm(np.mean([tv(load(x)) for x in roy_others], 0))   # 본인 다른 곡들만 (공정)
han_c = norm(np.mean([tv(load(x)) for x in han], 0))

print(f"그 곡 ↔ 로이킴 평균(그 곡 제외): {float(t_vec @ roy_c):.3f}")
print(f"그 곡 ↔ 한동근 평균:            {float(t_vec @ han_c):.3f}")

# 청크쌍 평균 (본 벤치마크와 같은 스케일)
E_t = load(target)
for label, group in [("로이킴 다른 곡", roy_others), ("한동근 곡", han)]:
    sims = [float((E_t @ load(x).T).mean()) for x in group]
    print(f"청크쌍 평균 — {label}: {[round(s,3) for s in sims]}")

# 오염 점검: 내부 응집도 + 이탈 조각
S = E_t @ E_t.T
iu = S[np.triu_indices(len(E_t), 1)]
m = tv(E_t)
per = E_t @ m
print(f"내부 응집도: {iu.mean():.3f} (카탈로그 중앙값 0.483) | 평균과 유사도 최하 3조각: {np.round(np.sort(per)[:3],3)}")
