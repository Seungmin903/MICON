# 메타데이터 시트 생성: emb_cache_v2의 .meta.json 전부 -> meta_sheet.html (정적, 재실행=갱신)
# 사용: python timbre_eval/meta_sheet.py
import json
from pathlib import Path
from statistics import median

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
OUT = HERE / "meta_sheet.html"

rows = []
for f in sorted(CACHE.glob("*.meta.json")):
    name = f.stem.replace(".meta", "")
    singer, _, song = name.partition("__")
    d = json.loads(f.read_text(encoding="utf-8"))
    t, ch = d["track"], d.get("chunks", [])
    rows.append({
        "singer": singer, "song": song,
        "chunks": len(ch),
        "coh": t["internal_coherence"],
        "outliers": t["outlier_chunks"],
        "snr": t["snr_proxy_db"],
        "clip": t["clipping_ratio"],
        "f0": (lambda v: round(median(v)) if v else None)([c["f0_median_hz"] for c in ch if c["f0_median_hz"] is not None]),
        "duet": t["duet_suspect"], "lowconf": t["low_confidence"],
        "env": ("라이브" in name or "콘서트" in name),
    })

html = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<title>음색 메타 시트</title>
<style>
body{font-family:'Malgun Gothic',sans-serif;margin:24px;background:#fafaf8;color:#222}
h1{font-size:20px} .sub{color:#777;font-size:13px;margin-bottom:16px}
.chips{margin:12px 0}
.chip{border:1px solid #ccc;background:#fff;border-radius:16px;padding:6px 14px;margin-right:8px;cursor:pointer;font-size:14px}
.chip.on{background:#222;color:#fff;border-color:#222}
input{padding:6px 10px;border:1px solid #ccc;border-radius:8px;font-size:14px;width:220px}
table{border-collapse:collapse;width:100%;background:#fff;font-size:13px}
th,td{border-bottom:1px solid #eee;padding:6px 10px;text-align:left;white-space:nowrap}
th{cursor:pointer;background:#f0efe9;position:sticky;top:0;user-select:none}
td.num{text-align:right;font-variant-numeric:tabular-nums}
.b{display:inline-block;border-radius:10px;padding:1px 8px;font-size:12px;margin-right:4px}
.b.duet{background:#fcdede;color:#8f1f1f}.b.low{background:#fdeec9;color:#7a5200}.b.env{background:#e5e5e0;color:#555}
.warn{color:#b00}
</style></head><body>
<h1>음색 메타 시트</h1>
<div class="sub">트랙별 겉표지 스티커 일람 · 응집도&lt;0.40 또는 이상조각≥4 → 저신뢰 · 듀엣은 추천 풀 제외 · 갱신: meta_sheet.py 재실행</div>
<div class="chips">
<button class="chip on" data-f="all">전체</button>
<button class="chip" data-f="duet">🔴 듀엣</button>
<button class="chip" data-f="lowconf">🟡 저신뢰</button>
<button class="chip" data-f="env">라이브·콘서트</button>
<button class="chip" data-f="clean">정상</button>
<input id="q" placeholder="가수·곡 검색">
</div>
<table><thead><tr>
<th data-k="singer">가수</th><th data-k="song">곡</th><th data-k="chunks">조각수</th>
<th data-k="coh">응집도</th><th data-k="outliers">이상조각</th><th data-k="snr">SNR(dB)</th>
<th data-k="clip">클리핑</th><th data-k="f0">f0중앙(Hz)</th><th>플래그</th>
</tr></thead><tbody id="tb"></tbody></table>
<script>
const DATA = __DATA__;
let filt = "all", q = "", sortK = "singer", asc = 1;
function pass(r){
  if (filt === "duet" && !r.duet) return false;
  if (filt === "lowconf" && !r.lowconf) return false;
  if (filt === "env" && !r.env) return false;
  if (filt === "clean" && (r.duet || r.lowconf || r.env)) return false;
  if (q && !(r.singer + r.song).toLowerCase().includes(q)) return false;
  return true;
}
function render(){
  const rows = DATA.filter(pass).sort((a,b)=>{
    const x = a[sortK], y = b[sortK];
    return (x==null)-(y==null) || (x>y)-(x<y) ? ((x>y)-(x<y))*asc : 0;
  });
  document.getElementById("tb").innerHTML = rows.map(r=>`<tr>
<td>${r.singer}</td><td>${r.song}</td><td class="num">${r.chunks}</td>
<td class="num ${r.coh<0.40?"warn":""}">${r.coh.toFixed(3)}</td>
<td class="num ${r.outliers>=4?"warn":""}">${r.outliers}</td>
<td class="num">${r.snr.toFixed(1)}</td><td class="num">${(r.clip*100).toFixed(1)}%</td>
<td class="num">${r.f0 ?? "-"}</td>
<td>${r.duet?'<span class="b duet">듀엣</span>':""}${r.lowconf?'<span class="b low">저신뢰</span>':""}${r.env?'<span class="b env">라이브·콘서트</span>':""}</td>
</tr>`).join("");
  document.title = `음색 메타 시트 (${rows.length}/${DATA.length})`;
}
document.querySelectorAll(".chip").forEach(c=>c.onclick=()=>{
  document.querySelectorAll(".chip").forEach(x=>x.classList.remove("on"));
  c.classList.add("on"); filt = c.dataset.f; render();
});
document.getElementById("q").oninput = e=>{ q = e.target.value.toLowerCase(); render(); };
document.querySelectorAll("th[data-k]").forEach(h=>h.onclick=()=>{
  asc = (sortK === h.dataset.k) ? -asc : 1; sortK = h.dataset.k; render();
});
render();
</script></body></html>"""

OUT.write_text(html.replace("__DATA__", json.dumps(rows, ensure_ascii=False)), encoding="utf-8")
print(f"{len(rows)}트랙 -> {OUT}")
