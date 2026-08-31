# 음색 임베딩 검증 웹앱: 업로드 -> 보컬분리 -> 임베딩 -> 2D 지도 + 적합성 지표
import itertools
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from flask import Flask, jsonify, redirect, request

HERE = Path(__file__).resolve().parent
DATA = HERE.parent / "data"
WAV = HERE / "wav"
SEP = HERE / "separated" / "htdemucs"
CACHE = HERE / "emb_cache"
MODELS = ["ecapa", "singer_id", "wavlm", "mert", "muq", "facodec"]
PY = sys.executable

app = Flask(__name__)


def run(args):
    print(">>", " ".join(str(a) for a in args), flush=True)
    subprocess.run([str(a) for a in args], check=True, cwd=HERE.parent)


def process_new_files():
    """mp3 -> wav -> demucs, 없는 것만."""
    run([PY, HERE / "prepare.py"])
    todo = [w for w in WAV.glob("*.wav") if not (SEP / w.stem / "vocals.wav").exists()]
    if todo:
        run([PY, "-m", "demucs", "--two-stems=vocals", "-n", "htdemucs",
             "-o", HERE / "separated", *todo])


@app.post("/upload")
def upload():
    singer = re.sub(r'[\\/:*?"<>|]', "", request.form["singer"]).strip()
    if not singer:
        return "가수/사람 이름이 필요합니다", 400
    d = DATA / singer
    d.mkdir(parents=True, exist_ok=True)
    for f in request.files.getlist("files"):
        if f.filename and f.filename.lower().endswith(".mp3"):
            f.save(d / Path(f.filename).name)
    process_new_files()
    return redirect("/")


@app.get("/api/analyze")
def analyze():
    model = request.args.get("model", "ecapa")
    if model not in MODELS:
        return jsonify({"error": "unknown model"}), 400
    # 캐시에 없는 트랙 임베딩 (별도 프로세스 - torch를 서버에 안 올림)
    run([PY, HERE / "embed_cli.py", "--model", model])

    embs = {}  # track -> [n_chunk, D]
    for f in sorted((CACHE / model).glob("*.npy")):
        embs[f.stem] = np.load(f)
    all_songs = sorted({n.split("__", 1)[1] for n in embs})
    song = request.args.get("song", "")
    if song:
        embs = {n: e for n, e in embs.items() if n.split("__", 1)[1] == song}
    if len(embs) < 2:
        return jsonify({"error": "트랙이 2개 이상 필요합니다"}), 400

    names = list(embs)
    means = {n: e.mean(0) / np.linalg.norm(e.mean(0)) for n, e in embs.items()}

    def meta(n):
        s, g = n.split("__", 1)
        return s, g

    pairs = []
    for n1, n2 in itertools.combinations(names, 2):
        s1, g1 = meta(n1)
        s2, g2 = meta(n2)
        if s1 == s2 and g1 == g2:
            continue
        cat = ("same_singer" if s1 == s2 else
               "cover" if g1 == g2 else "unrelated")
        sim = float((embs[n1] @ embs[n2].T).mean())  # 청크쌍 평균 (기존 실험과 동일 기준)
        pairs.append({"a": n1, "b": n2, "cat": cat, "sim": round(sim, 3)})

    pos = [p["sim"] for p in pairs if p["cat"] == "same_singer"]
    hard = [p["sim"] for p in pairs if p["cat"] == "cover"]
    pos_min = min(pos) if pos else None
    inversions = ([p for p in pairs if p["cat"] == "cover" and p["sim"] >= pos_min]
                  if pos_min is not None else [])

    verdict = "판정 불가 (같은 가수의 곡 2개 이상 + 커버 쌍이 필요)"
    if pos and hard:
        gap = float(np.mean(pos) - np.mean(hard))
        rate = len(inversions) / len(hard)
        if gap < 0.05:
            verdict = f"부적합 — 간격 {gap:.3f}: 곡/목소리 구분 능력이 사실상 없음"
        elif rate <= 0.15:
            verdict = f"적합 — 간격 {gap:.3f}, 역전 {len(inversions)}/{len(hard)}"
        elif rate <= 0.4:
            verdict = f"조건부 — 간격 {gap:.3f}, 역전 {len(inversions)}/{len(hard)} (동성 구분 확인 필요)"
        else:
            verdict = f"부적합 — 역전율 {rate:.0%}: 순위 기반 추천에 부적절"

    # PCA 2D (청크 전체 기준)
    X = np.vstack([embs[n] for n in names])
    Xc = X - X.mean(0)
    _, _, Vt = np.linalg.svd(Xc, full_matrices=False)
    P = Xc @ Vt[:2].T
    points, i = [], 0
    for n in names:
        s, g = meta(n)
        k = len(embs[n])
        for x, y in P[i:i + k]:
            points.append({"x": float(x), "y": float(y), "singer": s, "song": g})
        i += k
    cent = []
    for n in names:
        s, g = meta(n)
        c = (means[n] - X.mean(0)) @ Vt[:2].T
        cent.append({"x": float(c[0]), "y": float(c[1]), "singer": s, "song": g})

    return jsonify({
        "model": model, "n_tracks": len(names), "verdict": verdict,
        "all_songs": all_songs, "song": song,
        "mean_same_singer": round(float(np.mean(pos)), 3) if pos else None,
        "mean_cover": round(float(np.mean(hard)), 3) if hard else None,
        "pos_min": round(pos_min, 3) if pos_min is not None else None,
        "pairs": sorted(pairs, key=lambda p: -p["sim"]),
        "inversions": [f'{p["a"]} ↔ {p["b"]} ({p["sim"]})' for p in inversions],
        "points": points, "centroids": cent,
    })


PAGE = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>음색 임베딩 실험실</title>
<script src="https://cdn.plot.ly/plotly-2.35.2.min.js"></script>
<style>
body{font-family:"Malgun Gothic",sans-serif;max-width:1000px;margin:24px auto;padding:0 16px;color:#1b2b33}
h1{font-size:22px} h2{font-size:17px;margin-top:28px}
fieldset{border:1px solid #ccc;border-radius:8px;margin:12px 0;padding:12px}
button{padding:6px 14px;margin-right:6px;cursor:pointer;border:1px solid #888;border-radius:6px;background:#fff}
button.active{background:#0e6e8c;color:#fff;border-color:#0e6e8c}
table{border-collapse:collapse;font-size:13.5px;margin:10px 0}
td,th{border:1px solid #ddd;padding:4px 10px} .warn{background:#fde8e6}
#verdict{font-weight:600;padding:10px 14px;border-radius:8px;background:#e3eff3;margin:12px 0}
#status{color:#666;font-size:13px}
</style></head><body>
<h1>🎤 음색 임베딩 실험실</h1>
<p>mp3를 업로드하면 보컬 분리 → 임베딩 → 목소리 지도와 적합성 지표를 계산합니다.
파일명 규칙: <b>곡명이 같으면 같은 곡</b>으로 간주 (예: "웬디 - 밤편지.mp3"와 "첸 - 밤편지.mp3")</p>

<fieldset><legend>업로드</legend>
<form method="post" action="/upload" enctype="multipart/form-data" onsubmit="status.textContent='처리 중... 보컬 분리에 곡당 30초쯤 걸립니다'">
가수/사람 이름: <input name="singer" required placeholder="예: 태연">
<input type="file" name="files" accept=".mp3" multiple required>
<button type="submit">업로드 & 분리</button>
</form></fieldset>

<fieldset><legend>모델 · 곡 필터</legend>
<span id="models"></span>
&nbsp; 곡 필터: <select id="songsel" onchange="load()"><option value="">전체</option></select>
<span style="font-size:12px;color:#666">(한 곡만 고르면 곡 변수가 통제돼 남는 차이 = 순수 목소리)</span>
<div id="status"></div></fieldset>

<div id="verdict"></div>
<div id="plot" style="height:520px"></div>
<h2>쌍별 유사도 (청크쌍 평균, 높은 순)</h2>
<div id="pairs"></div>

<script>
const MODELS=["ecapa","singer_id","wavlm","mert"];
const CAT={same_singer:"① 같은 가수·다른 곡",cover:"② 다른 가수·같은 곡",unrelated:"③ 다른 가수·다른 곡"};
let cur="ecapa";
const mdiv=document.getElementById("models");
MODELS.forEach(m=>{const b=document.createElement("button");b.textContent=m;b.id="b_"+m;
b.onclick=()=>{cur=m;load()};mdiv.appendChild(b)});

async function load(){
 MODELS.forEach(m=>document.getElementById("b_"+m).classList.toggle("active",m===cur));
 status.textContent="분석 중... (처음 보는 트랙이 있으면 임베딩에 시간이 걸립니다)";
 const song=document.getElementById("songsel").value;
 const r=await fetch("/api/analyze?model="+cur+"&song="+encodeURIComponent(song)); const d=await r.json();
 if(d.error){status.textContent=d.error;return}
 const sel=document.getElementById("songsel");
 if(sel.options.length<=1)d.all_songs.forEach(s=>{const o=document.createElement("option");o.value=s;o.textContent=s;sel.appendChild(o)});
 status.textContent=`트랙 ${d.n_tracks}개 · 같은가수 평균 ${d.mean_same_singer} · 커버쌍 평균 ${d.mean_cover}`;
 verdict.textContent=`[${d.model}] 판정: ${d.verdict}`;

 const singers=[...new Set(d.points.map(p=>p.singer))];
 const traces=singers.map(s=>({
   x:d.points.filter(p=>p.singer===s).map(p=>p.x),
   y:d.points.filter(p=>p.singer===s).map(p=>p.y),
   text:d.points.filter(p=>p.singer===s).map(p=>p.song),
   name:s,mode:"markers",type:"scatter",marker:{size:6,opacity:.45}}));
 traces.push({x:d.centroids.map(c=>c.x),y:d.centroids.map(c=>c.y),
   text:d.centroids.map(c=>c.singer+"·"+c.song),mode:"markers+text",type:"scatter",
   textposition:"top center",name:"트랙 중심",marker:{size:14,symbol:"diamond",color:"#111",line:{width:1,color:"#fff"}}});
 Plotly.newPlot("plot",traces,{title:cur+" 임베딩 지도 (PCA 2D) — 점=5초 청크, ◆=트랙 평균",
   xaxis:{title:"PC1"},yaxis:{title:"PC2"},legend:{orientation:"h"}},{responsive:true});

 let h="<table><tr><th>범주</th><th>A</th><th>B</th><th>유사도</th></tr>";
 d.pairs.filter(p=>p.cat!=="unrelated").forEach(p=>{
   const warn=(p.cat==="cover"&&d.pos_min!==null&&p.sim>=d.pos_min)?" class=warn":"";
   h+=`<tr${warn}><td>${CAT[p.cat]}</td><td>${p.a}</td><td>${p.b}</td><td>${p.sim}</td></tr>`});
 h+="</table>";
 if(d.inversions.length)h+="<p>⚠️ 역전(같은 가수 쌍 최솟값 "+d.pos_min+" 이상인 커버 쌍): "+d.inversions.join(" / ")+"</p>";
 document.getElementById("pairs").innerHTML=h;
}
load();
</script></body></html>"""


@app.get("/")
def index():
    return PAGE


if __name__ == "__main__":
    app.run(port=5001, debug=False)
