'use strict';
const $ = id => document.getElementById(id);
const state = {requests: [], map: [], shown: new Set(), selected: '', inspected: '', artist: null, result: null, tab: 'baseline', epoch: 0, jobEpoch: 0, busy: false};
const models = ['ecapa', 'singer_byol'];
const settingIds = ['ecapaWeight','centroidWeight','timbreWeight','maxKeyShift','maxCoreExcess','minRangeScore','minProfileConfidence','topK'];
function el(tag, text, cls) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if(cls) n.className = cls; return n; }
function error(message) { $('error').textContent = message || ''; $('error').hidden = !message; }
async function api(path, options) {
  const response = await fetch(path, {...options, cache: 'no-store'});
  let body; try { body = await response.json(); } catch { throw new Error(`응답을 읽지 못했습니다 (${response.status}).`); }
  if (!response.ok) throw new Error(typeof body.detail === 'string' ? body.detail : JSON.stringify(body.detail || body));
  return body;
}
function badge(status) {const value = String(status || 'UNKNOWN'); return el('span', value, 'status ' + (/FAILED|INSUFFICIENT/i.test(value)? 'failed' : /COMPLETE|REPLAY_READY/i.test(value)? 'complete' : 'pending'));}
function ownerText(r) {return r.kind==='case'?'테스트 case':r.loginId?`가입 ID ${r.loginId}`:'가입 ID 확인 불가';}
function inputInfo(r) {return r?(ownerText(r)+' · '+(r.kind==='case'?'동의받아 저장한 고정 입력 case':'분석 원본 진단 · 당시 추천의 취향과 profile 동일성 보장 안 됨')):'';}
function tip(event, text) {
  const box = $('tooltip'); box.textContent = text; box.hidden = false;
  box.style.left = Math.max(8, Math.min(event.clientX+14, window.innerWidth-box.offsetWidth-8))+'px';
  box.style.top = Math.max(8, Math.min(event.clientY+12, window.innerHeight-box.offsetHeight-8))+'px';
}
function refreshRequestList() {
  const query = $('requestSearch').value.toLowerCase(); $('requests').replaceChildren();
  const memberQuery = query.match(/^(?:회원(?:\s*id)?|user(?:\s*id)?)\s*[:#]?\s*(\d+)$/);
  const filtered = state.requests.filter(r => memberQuery?String(r.userId)===memberQuery[1].replace(/^0+(?=\d)/,''):[r.id,r.loginId,r.userId,ownerText(r),r.sessionId,r.profileId,r.status,r.title].join(' ').toLowerCase().includes(query));
  for(const r of filtered) {
    const card = el('div', undefined, 'request-card' + (state.selected===r.id?' selected':''));
    card.tabIndex=0; card.setAttribute('role','button'); card.dataset.userId=r.userId || ''; card.dataset.ownerStatus=r.ownerStatus || '';
    const head = el('div',undefined,'request-head'); const check = el('input'); check.type='checkbox'; check.checked=state.shown.has(r.id); check.disabled=!r.points; check.setAttribute('aria-label',`${r.id} 지도에 표시`);
    check.title = r.replayReason || '지도에 표시';
    check.onclick = e=>e.stopPropagation(); check.onchange=()=>{check.checked?state.shown.add(r.id):state.shown.delete(r.id); drawMaps();};
    head.append(check, el('strong',r.title || `${r.analysisType || '추천'} · ${r.sessionId ? 'S'+r.sessionId : r.profileId ? 'P'+r.profileId : r.kind}`));
    const owner=el('div',ownerText(r),r.loginId?'request-owner':'muted'); owner.title=r.ownerReason || '';
    card.append(head,owner,el('div',r.id,'request-id'),badge(r.status),el('div',r.createdAt || '생성 시각 미상','muted'));
    if(r.userId)card.append(el('div',`회원번호 ${r.userId}`,'muted'));
    if(!r.replayAvailable) card.append(el('div','상태 조회만 가능','muted'));
    function select(){state.selected=r.id; refreshRequestList(); if(r.kind==='case'){ $('caseSelect').value=r.id; inputChanged(); } else { $('jobId').value=r.id; inspectJob(r.id); if(r.replayAvailable){ $('caseSelect').value=r.id; inputChanged(); } }}
    card.onclick=select; card.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();select();}}; $('requests').append(card);
  }
  if(!filtered.length) $('requests').append(el('p','설정된 소스에 요청이 없거나 검색 결과가 없습니다.','muted'));
}
let polling = false;
async function refreshRequests() {
  if(polling)return; polling=true;
  try {
    const body=await api('api/requests');
    const previous=state.requests.find(r=>r.id===state.inspected);
    state.requests=body.requests;
    const current=state.requests.find(r=>r.id===state.inspected);
    if(current&&previous&&(current.status!==previous.status||current.updatedAt!==previous.updatedAt||current.userId!==previous.userId||current.loginId!==previous.loginId||current.ownerStatus!==previous.ownerStatus))inspectJob(state.inspected);
    for(const id of [...state.shown]) if(!state.requests.some(r=>r.id===id && r.points)) state.shown.delete(id);
    const old=$('caseSelect').value; $('caseSelect').replaceChildren(new Option('재생 입력 선택',''));
    for(const r of state.requests.filter(r=>r.replayAvailable)) $('caseSelect').add(new Option(`${ownerText(r)} · ${r.title || r.analysisType || r.kind} · ${r.id}`,r.id));
    if(state.requests.some(r=>r.id===old && r.replayAvailable)) {$('caseSelect').value=old;$('inputInfo').textContent=inputInfo(state.requests.find(r=>r.id===old));}
    else if(old){$('caseSelect').value=''; inputChanged();}
    refreshRequestList(); drawMaps();
  }catch(e){error('요청 목록: '+e.message);}finally{polling=false;}
}
function fact(label,value){const n=el('div',undefined,'fact');n.append(el('small',label),el('div',value===null||value===undefined?'기록 없음':String(value)));return n;}
async function inspectJob(id) {
  state.inspected=id;
  const epoch=++state.jobEpoch; $('jobDetail').replaceChildren(el('p','조회 중…','muted'));
  try {
    const j=await api('api/jobs/'+encodeURIComponent(id)); if(epoch!==state.jobEpoch)return;
    const detail=$('jobDetail');detail.replaceChildren(badge(j.status));
    const facts=el('div',undefined,'facts'); facts.append(fact('가입 ID (로그인 이메일)',j.loginId || '확인 불가'),fact('회원번호 (내부 userId)',j.userId || '확인 불가'),fact('회원 확인 근거',j.ownerReason || '회원 연결 기록 없음'),fact('유형',j.analysisType||j.kind),fact('분석 컴포넌트 상태',j.componentStatus||j.status),fact('실패 코드',j.failureCode),fact('session / profile',`${j.sessionId ?? '-'} / ${j.profileId ?? '-'}`),fact('생성 시각',j.createdAt),fact('갱신 시각',j.updatedAt));detail.append(facts);
    if(j.loginIdStatus==='missing')detail.append(el('p','회원번호는 확인됐지만 가입 ID가 제공되지 않았습니다. 회원 연결 데이터 소스에 loginId가 필요합니다.','muted'));
    if(j.failureReason)detail.append(el('div',j.failureReason,'failure-reason'));
    if(j.reasonUnavailable)detail.append(el('p',j.reasonUnavailable,'failure-reason'));
    for(const c of j.components){const line=el('div',undefined,'component-row');line.append(el('strong',c.name),badge(c.status),el('span',`콜백 HTTP ${c.callbackHttp ?? '미기록'} · 백엔드 ${c.accepted?'수락':'미수락'} · 시도 ${c.callbackAttempts ?? '-'}`));detail.append(line);if(c.failureReason&&c.failureReason!==j.failureReason)detail.append(el('p',c.failureReason,'failure-reason'));}
    if(j.components.length)detail.append(el('p','HTTP 200은 결과 전달 성공입니다. 분석 FAILED를 성공으로 바꾸지는 않습니다.','muted'));
    if(j.metadataChecks.length){const box=el('div',undefined,'metadata-check');box.append(el('strong','메타데이터 기반 진단 후보'));
      for(const a of j.metadataChecks)box.append(el('div',`${a.attemptId}: 목표 ${a.targetMidi} / 기준 ${Number(a.referenceMidi).toFixed(4)} / 차이 ${a.deltaSemitones.toFixed(4)}반음 / 앱 ${a.clientStatus}`));box.append(el('p',j.metadataCheckNote,'muted'));detail.append(box);}
    detail.append(el('p',j.replayReason || '분석 원본으로 진단 재생할 수 있습니다.','muted'));
    if(j.storedRecommendations.length){detail.append(el('h3','당시 저장된 추천 결과')); const list=el('ol');for(const p of j.storedRecommendations)list.append(el('li',`${p.artist} · ${p.title} (${p.final_score})`));detail.append(list);}
  }catch(e){if(epoch===state.jobEpoch)$('jobDetail').replaceChildren(el('p',e.message,'failure-reason'));}
}
const svgNS='http://www.w3.org/2000/svg';
function svg(tag,attrs){const n=document.createElementNS(svgNS,tag);for(const [key,value] of Object.entries(attrs||{}))n.setAttribute(key,value);return n;}
function color(artist){let h=0;for(const char of artist)h=(h*31+char.charCodeAt(0))>>>0;return `hsl(${h%360},52%,53%)`;}
const views={ecapa:{scale:1,x:0,y:0},singer_byol:{scale:1,x:0,y:0}};
function drawMaps(){
  for(const model of models){const root=$(model+'Map'); root.replaceChildren();
    const requests=state.requests.filter(r=>state.shown.has(r.id)&&r.points);
    const coords=[...state.map.map(p=>p[model]),...requests.map(r=>r.points[model])].filter(Boolean);
    if(!coords.length)continue;
    let xmin=Math.min(...coords.map(p=>p[0])),xmax=Math.max(...coords.map(p=>p[0])),ymin=Math.min(...coords.map(p=>p[1])),ymax=Math.max(...coords.map(p=>p[1]));
    const dx=Math.max(xmax-xmin,.0001),dy=Math.max(ymax-ymin,.0001);xmin-=dx*.08;xmax+=dx*.08;ymin-=dy*.08;ymax+=dy*.08;
    const sx=x=>40+(x-xmin)/(xmax-xmin)*530,sy=y=>305-(y-ymin)/(ymax-ymin)*270;
    root.append(svg('line',{x1:40,y1:305,x2:570,y2:305,class:'map-line'}),svg('line',{x1:40,y1:35,x2:40,y2:305,class:'map-line'}));
    for(const [x,y,text] of [[530,330,'PC 1'],[10,25,'PC 2']]){const t=svg('text',{x,y,class:'map-axis'});t.textContent=text;root.append(t);}
    const g=svg('g'); root.append(g);const v=views[model];g.setAttribute('transform',`translate(${v.x},${v.y}) scale(${v.scale})`);
    for(const p of state.map){const dot=svg('circle',{cx:sx(p[model][0]),cy:sy(p[model][1]),r:3.2,fill:color(p.artist),class:'map-dot'+(state.artist?(p.artist===state.artist?' highlight':' dim'):'')});
      dot.onpointermove=e=>tip(e,`${p.artist}\n${p.title}\n${p.id}`);dot.onpointerleave=()=>{$('tooltip').hidden=true;};dot.onclick=()=>{state.artist=state.artist===p.artist?null:p.artist;drawMaps();};g.append(dot);}
    for(const r of requests){const x=sx(r.points[model][0]),y=sy(r.points[model][1]);const dot=svg('path',{d:`M${x} ${y-7} L${x+7} ${y} L${x} ${y+7} L${x-7} ${y} Z`,class:'user-point'});dot.onpointermove=e=>tip(e,`${ownerText(r)}\n회원번호 ${r.userId || '확인 불가'}\n요청 ${r.title || r.analysisType || r.kind}\n${r.id}\n${r.status}`);dot.onpointerleave=()=>{$('tooltip').hidden=true;};g.append(dot);const label=svg('text',{x:x+10,y:y+4,class:'user-label'});label.textContent=(r.userId?'#'+r.userId+' · ':'')+(r.sessionId?'S'+r.sessionId:r.id.slice(0,12));g.append(label);}
    root.onwheel=e=>{e.preventDefault();const rect=root.getBoundingClientRect();const px=(e.clientX-rect.left)/rect.width*600,py=(e.clientY-rect.top)/rect.height*350;const next=Math.min(8,Math.max(.7,v.scale*(e.deltaY<0?1.15:1/1.15)));const ratio=next/v.scale;v.x=px-(px-v.x)*ratio;v.y=py-(py-v.y)*ratio;v.scale=next;g.setAttribute('transform',`translate(${v.x},${v.y}) scale(${v.scale})`);};
    let drag=null;root.onpointerdown=e=>{if(e.target===root||e.target===g)drag={x:e.clientX,y:e.clientY,tx:v.x,ty:v.y};};root.onpointermove=e=>{if(!drag)return;const rect=root.getBoundingClientRect();v.x=drag.tx+(e.clientX-drag.x)/rect.width*600;v.y=drag.ty+(e.clientY-drag.y)/rect.height*350;g.setAttribute('transform',`translate(${v.x},${v.y}) scale(${v.scale})`);};root.onpointerup=root.onpointerleave=()=>{drag=null;};
  }
}
function inputChanged(){state.epoch++; state.result=null; $('results').hidden=true;const r=state.requests.find(r=>r.id===$('caseSelect').value);$('run').disabled=!r||state.busy;
  $('inputInfo').textContent=inputInfo(r);
  $('experimentState').textContent='입력 또는 설정이 변경됐습니다. 실험 실행을 눌러 새 결과를 계산하세요.';
}
async function run(){
  const id=$('caseSelect').value;if(!id||state.busy)return;
  for(const key of settingIds)if(!$(key).reportValidity())return;
  const epoch=++state.epoch; state.busy=true; $('run').disabled=true; error(''); $('experimentState').textContent='같은 입력으로 기본값과 후보 설정을 계산 중…';
  const settings={};for(const key of settingIds)settings[key]=Number($(key).value); settings.useRangeGate=$('useRangeGate').checked;
  try{const result=await api('api/experiments',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({requestId:id,settings})}); if(epoch!==state.epoch)return;
    state.result=result; $('results').hidden=false;renderResults();$('experimentState').textContent=(result.inputWarning||'고정 입력 case 재생 완료')+' · '+(result.rangeAvailable?'음역 입력 있음':'음역 입력 없음: 기본값은 음색 단독 fallback');
  }catch(e){if(epoch===state.epoch){error(e.message);$('experimentState').textContent='실험 실패: '+e.message;}}
  finally{state.busy=false;$('run').disabled=!$('caseSelect').value;}
}
function renderResults(){
  const result=state.result;if(!result)return;$('comparison').replaceChildren();
  for(const name of ['baseline','candidate']){const data=result[name];const card=el('div',undefined,'panel');card.append(el('h3',name==='baseline'?'기본값 최종 추천':'후보 설정 최종 추천'),el('p',`게이트 통과 ${data.eligibleCount} · 탈락 ${data.excludedCount} · 음역 게이트 ${data.rangeGateApplied?'적용':'미적용'}`,'muted'));const list=el('ol');
    for(const p of data.recommendations){const item=el('li',`${p.artist} · ${p.title}`);item.append(el('span',Number(p.final_score).toFixed(4),'score'));list.append(item);}card.append(list);if(!data.recommendations.length)card.append(el('p','게이트를 통과해 선택된 곡이 없습니다.','muted'));$('comparison').append(card);}
  $('comparison').append(el('p',`Top K 공통 곡 ${result.overlapAtK}개 · 배치 ${result.catalogFingerprint.slice(0,12)}`,'muted'));
  renderRankings();
}
function renderRankings(){if(!state.result)return;
  $('baselineTab').classList.toggle('active',state.tab==='baseline');$('candidateTab').classList.toggle('active',state.tab==='candidate');
  const query=$('trackSearch').value.toLowerCase(), limit=Number($('rowLimit').value);
  for(const model of models){const holder=$(model+'Rank');holder.replaceChildren();const source=state.result[state.tab].rankings[model];const filtered=source.filter(p=>(p.artist+' '+p.title).toLowerCase().includes(query)&&(!$('onlyExcluded').checked||!p.eligible));holder.append(el('p',`전체 ${source.length}곡 · 검색 ${filtered.length}곡 · 표시 ${Math.min(limit,filtered.length)}곡`,'muted'));
    const table=el('table'),head=el('thead'),tr=el('tr');for(const name of ['순위','곡과 가수','음색 점수','게이트와 사유'])tr.append(el('th',name));head.append(tr);table.append(head);const body=el('tbody');
    for(const p of filtered.slice(0,limit)){const row=el('tr',undefined,p.eligible?'':'excluded');row.dataset.trackId=p.id;row.append(el('td',p.rank));const song=el('td');song.append(el('div',p.title,'song-title'),el('div',p.artist),el('div',`최적 키 ${p.rangeFit?Number(p.rangeFit.keyShift).toFixed(0):'—'} · 음역 점수 ${p.rangeFit?Number(p.rangeFit.score).toFixed(3):'—'}`,'muted'));row.append(song,el('td',p.scores[model].toFixed(4)));const reasons=el('td',undefined,'reason');if(p.eligible)reasons.textContent='통과';else for(const r of p.exclusions)reasons.append(el('div',r.message));row.append(reasons);row.onpointerenter=()=>{state.artist=p.artist;drawMaps();};row.onpointerleave=()=>{state.artist=null;drawMaps();};body.append(row);}table.append(body);const scroll=el('div',undefined,'table-scroll');scroll.append(table);holder.append(scroll);
  }
}
function exportResult(){if(!state.result)return;const blob=new Blob([JSON.stringify({exportedAt:new Date().toISOString(),...state.result},null,2)],{type:'application/json'});const link=el('a');link.href=URL.createObjectURL(blob);link.download=`recommend-lab-${state.result.caseId}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);}
async function start(){
  try{const status=await api('api/status'); $('connection').textContent=status.engineReady?'카탈로그 연결됨':'상태 조회 모드 · 카탈로그 미연결';$('sourceInfo').textContent='소스: '+Object.entries(status.dataSources).filter(([,on])=>on).map(([key])=>key).join(' / ');
    if(status.engineReady){const body=await api('api/map');state.map=body.points;const info=body.catalog; $('catalogInfo').append(el('strong',info.trackCount+'곡'),el('div','카탈로그 '+info.catalogFingerprint.slice(0,12)),el('div','추천 core '+info.coreFingerprint.slice(0,12)),el('div',info.scoringVersion,'muted'));$('projectionInfo').textContent=`PCA · 평균 구간 벡터 정규화 · 분산 설명 ECAPA ${(100*info.projection.explainedVariance.ecapa.reduce((a,b)=>a+b,0)).toFixed(1)}%, BYOL ${(100*info.projection.explainedVariance.singer_byol.reduce((a,b)=>a+b,0)).toFixed(1)}% · 지도 거리는 실제 추천 점수가 아닙니다.`;drawMaps();}
    else error(status.engineError || '카탈로그 소스를 설정해야 지도와 실험을 사용할 수 있습니다.');
    await refreshRequests(); setInterval(refreshRequests,10000);
  }catch(e){$('connection').textContent='연결 실패';error(e.message);}
}
$('refresh').onclick=refreshRequests;$('requestSearch').oninput=refreshRequestList;$('caseSelect').onchange=inputChanged;$('run').onclick=run;
$('jobForm').onsubmit=e=>{e.preventDefault();inspectJob($('jobId').value.trim());};
$('resetMap').onclick=()=>{for(const model of models)views[model]={scale:1,x:0,y:0};state.artist=null;drawMaps();};
for(const id of [...settingIds,'useRangeGate'])$(id).onchange=inputChanged;
for(const id of ['trackSearch','onlyExcluded','rowLimit'])$(id).oninput=renderRankings;
$('baselineTab').onclick=()=>{state.tab='baseline';renderRankings();};$('candidateTab').onclick=()=>{state.tab='candidate';renderRankings();};$('export').onclick=exportResult;
start();
