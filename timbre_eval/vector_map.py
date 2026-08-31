# 전체 벡터 공간 인터랙티브 지도: 845트랙 t-SNE -> vector_map.html (줌·팬·호버·검색)
# 2D는 왜곡이 있으므로 이웃보존율(10-NN 겹침)을 실측해 헤더에 표기 — 쌍별 판정은 원공간으로.
# 사용: python timbre_eval/vector_map.py
import json
import re
from pathlib import Path

import numpy as np
from sklearn.manifold import TSNE
from scipy.cluster.vq import kmeans2

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
OUT = HERE / "vector_map.html"
DUET = re.compile(r"(,|&|feat\.?|듀엣|with )", re.IGNORECASE)
GROUPS = set((HERE / "groups.txt").read_text(encoding="utf-8").split("\n")) - {""}

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
names = sorted(E)
X = np.stack([(lambda m: m / np.linalg.norm(m))(E[t].mean(0)) for t in names])

P = TSNE(n_components=2, perplexity=30, init="pca", random_state=0).fit_transform(X)
_, cl = kmeans2(X, 2, seed=0, minit="++")

# 이웃보존율: 원공간(코사인) 10-NN과 2D(유클리드) 10-NN의 평균 겹침
S = X @ X.T
np.fill_diagonal(S, -9)
D2 = ((P[:, None, :] - P[None, :, :]) ** 2).sum(-1)
np.fill_diagonal(D2, 1e18)
k = 10
nn_hi = np.argsort(-S, 1)[:, :k]
nn_lo = np.argsort(D2, 1)[:, :k]
preserve = np.mean([len(set(a) & set(b)) / k for a, b in zip(nn_hi, nn_lo)])

pts = []
for i, t in enumerate(names):
    singer, _, song = t.partition("__")
    flag = ("user" if singer in ("지유림", "정다운") else
            "group" if singer in GROUPS else
            "duet" if DUET.search(singer) else
            "env" if ("라이브" in t or "콘서트" in t) else "pool")
    pts.append({"s": singer, "g": song, "x": round(float(P[i, 0]), 2),
                "y": round(float(P[i, 1]), 2), "c": int(cl[i]), "f": flag})

html = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>음색 벡터 지도</title>
<style>
body{font-family:'Malgun Gothic',sans-serif;margin:0;background:#fafaf8;color:#222;overflow:hidden}
#top{padding:10px 16px}h1{font-size:17px;margin:0 0 4px}
.sub{color:#888;font-size:12px}
#ctl{margin-top:8px}
input{padding:5px 10px;border:1px solid #ccc;border-radius:8px;font-size:13px;width:200px}
label{font-size:13px;margin-left:12px;user-select:none}
#cv{display:block;cursor:grab}
#tip{position:fixed;pointer-events:none;background:#222;color:#fff;padding:4px 10px;border-radius:6px;font-size:12px;display:none;z-index:9}
</style></head><body>
<div id="top">
<h1>음색 벡터 지도 — __N__트랙 (t-SNE 2D)</h1>
<div class="sub">⚠️ 192차원→2D 압축, 10-NN 이웃보존율 __PRES__% — 큰 지형(대륙·계열)만 믿고 쌍별 판정은 원공간으로 · 휠=줌, 드래그=이동, 점 위 호버=곡명(같은 가수 강조)</div>
<div id="ctl">
<input id="q" placeholder="가수·곡 검색 (강조)">
<label><input type="checkbox" id="showGated" checked> 게이트 제외분(그룹·듀엣·라이브)도 표시</label>
</div></div>
<canvas id="cv"></canvas><div id="tip"></div>
<script>
const DATA = __DATA__;
const cv = document.getElementById("cv"), ctx = cv.getContext("2d"), tip = document.getElementById("tip");
let W, H, scale = 1, ox = 0, oy = 0, q = "", showGated = true, hover = null;
const xs = DATA.map(p=>p.x), ys = DATA.map(p=>p.y);
const bx = [Math.min(...xs), Math.max(...xs)], by = [Math.min(...ys), Math.max(...ys)];
function resize(){
  W = innerWidth; H = innerHeight - document.getElementById("top").offsetHeight;
  cv.width = W; cv.height = H; draw();
}
function sx(p){ return ((p.x-bx[0])/(bx[1]-bx[0])*(W-80)+40)*scale+ox; }
function sy(p){ return ((p.y-by[0])/(by[1]-by[0])*(H-80)+40)*scale+oy; }
const COL = {0:"#378ADD", 1:"#D85A30"};
function draw(){
  ctx.clearRect(0,0,W,H);
  const vis = DATA.filter(p => showGated || p.f === "pool" || p.f === "user");
  for (const p of vis){
    if (p === hover || (hover && p.s === hover.s) || (q && (p.s+p.g).toLowerCase().includes(q))) continue;
    ctx.globalAlpha = (p.f === "pool") ? 0.75 : 0.35;
    ctx.fillStyle = (p.f === "pool") ? COL[p.c] : "#999";
    ctx.beginPath();
    if (p.f === "group" || p.f === "duet"){
      const x = sx(p), y = sy(p);
      ctx.moveTo(x-3,y-3); ctx.lineTo(x+3,y+3); ctx.moveTo(x+3,y-3); ctx.lineTo(x-3,y+3);
      ctx.strokeStyle = "#999"; ctx.stroke();
    } else ctx.arc(sx(p), sy(p), p.f==="env"?3:4, 0, 7), ctx.fill();
  }
  ctx.globalAlpha = 1;
  for (const p of vis){
    const hl = (hover && p.s === hover.s) || (q && (p.s+p.g).toLowerCase().includes(q));
    if (!hl && p.f !== "user") continue;
    const x = sx(p), y = sy(p);
    if (p.f === "user"){
      ctx.fillStyle = "#000"; ctx.font = "16px sans-serif"; ctx.fillText("★", x-8, y+6);
      ctx.font = "bold 12px sans-serif"; ctx.fillText(p.s + "·" + p.g, x+8, y+4);
    } else {
      ctx.fillStyle = COL[p.c]; ctx.strokeStyle = "#000";
      ctx.beginPath(); ctx.arc(x, y, 6, 0, 7); ctx.fill(); ctx.stroke();
      ctx.font = "11px sans-serif"; ctx.fillStyle = "#000";
      ctx.fillText(p.s + "·" + p.g.slice(0, 12), x+8, y+4);
    }
  }
}
cv.addEventListener("wheel", e=>{
  e.preventDefault();
  const f = e.deltaY < 0 ? 1.15 : 1/1.15;
  ox = e.offsetX - (e.offsetX - ox) * f; oy = e.offsetY - (e.offsetY - oy) * f;
  scale *= f; draw();
});
let drag = null;
cv.addEventListener("mousedown", e=>{ drag = [e.clientX-ox, e.clientY-oy]; cv.style.cursor="grabbing"; });
addEventListener("mouseup", ()=>{ drag = null; cv.style.cursor="grab"; });
cv.addEventListener("mousemove", e=>{
  if (drag){ ox = e.clientX-drag[0]; oy = e.clientY-drag[1]; draw(); return; }
  let best = null, bd = 100;
  for (const p of DATA){
    if (!showGated && p.f !== "pool" && p.f !== "user") continue;
    const d = (sx(p)-e.offsetX)**2 + (sy(p)-e.offsetY)**2;
    if (d < bd){ bd = d; best = p; }
  }
  if (best !== hover){ hover = best; draw(); }
  if (best){
    tip.style.display = "block"; tip.style.left = (e.clientX+14)+"px"; tip.style.top = (e.clientY+8)+"px";
    tip.textContent = best.s + " · " + best.g + (best.f !== "pool" ? ` [${{user:"팀원",group:"그룹",duet:"듀엣",env:"라이브·콘서트"}[best.f]}]` : "");
  } else tip.style.display = "none";
});
document.getElementById("q").oninput = e=>{ q = e.target.value.toLowerCase(); draw(); };
document.getElementById("showGated").onchange = e=>{ showGated = e.target.checked; draw(); };
addEventListener("resize", resize);
resize();
</script></body></html>"""

OUT.write_text(html.replace("__DATA__", json.dumps(pts, ensure_ascii=False))
                   .replace("__N__", str(len(pts)))
                   .replace("__PRES__", f"{preserve*100:.0f}"), encoding="utf-8")
print(f"{len(pts)}트랙, 이웃보존율 {preserve*100:.0f}% -> {OUT}")
