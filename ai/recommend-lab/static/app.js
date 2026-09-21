'use strict';
const $ = id => document.getElementById(id);
const state = {requests: [], map: [], shown: new Set(), selected: '', inspected: '', artist: null, result: null, tab: 'baseline', epoch: 0, jobEpoch: 0, busy: false, pendingAutoRun: false, autoTimer: null};
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
function badge(status) {const value = String(status || 'UNKNOWN'); return el('span', value, 'status ' + (/FAILED|INSUFFICIENT|REJECTED|CONFLICT|UNAVAILABLE/i.test(value)? 'failed' : /COMPLETE|REPLAY_READY|ACCEPTED|AVAILABLE|NO_RESULT/i.test(value)? 'complete' : 'pending'));}
function mrText(mr) {const input=mr.embeddingInput==='separated-vocals'?'보컬 분리본으로 임베딩':mr.embeddingInput==='raw-recording'?'원본 그대로 임베딩':'임베딩 입력 미기록';
  const numbers=`바닥 비율 ${mr.lowEnergyFloorRatio ?? '-'} · 동적 범위 ${mr.dynamicRangeP95P10 ?? '-'}`;
  return [...(mr.reasons || []), numbers, input, mr.separationFailureReason?'분리 실패: '+mr.separationFailureReason:''].filter(Boolean).join('\n');}
function mrBadge(mr) {const tag=el('span',mr.label,'status mr-flag'); tag.title=mrText(mr); return tag;}
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
  const filtered = state.requests.filter(r => memberQuery?String(r.userId)===memberQuery[1].replace(/^0+(?=\d)/,''):[r.id,r.loginId,r.userId,ownerText(r),r.sessionId,r.profileId,r.status,r.title,r.mrDetection?.label].join(' ').toLowerCase().includes(query));
  for(const r of filtered) {
    const card = el('div', undefined, 'request-card' + (state.selected===r.id?' selected':''));
    card.tabIndex=0; card.setAttribute('role','button'); card.dataset.userId=r.userId || ''; card.dataset.ownerStatus=r.ownerStatus || '';
    const head = el('div',undefined,'request-head'); const check = el('input'); check.type='checkbox'; check.checked=state.shown.has(r.id); check.disabled=!r.points; check.setAttribute('aria-label',`${r.id} 지도에 표시`);
    check.title = r.replayReason || '지도에 표시';
    check.onclick = e=>e.stopPropagation(); check.onchange=()=>{check.checked?state.shown.add(r.id):state.shown.delete(r.id); drawMaps();};
    head.append(check, el('strong',r.title || `${r.analysisType || '추천'} · ${r.sessionId ? 'S'+r.sessionId : r.profileId ? 'P'+r.profileId : r.kind}`));
    const owner=el('div',ownerText(r),r.loginId?'request-owner':'muted'); owner.title=r.ownerReason || '';
    card.append(head,owner,el('div',r.id,'request-id'),badge(r.status));
    if(r.mrDetection)card.append(mrBadge(r.mrDetection));
    card.append(el('div',r.createdAt || '생성 시각 미상','muted'));
    if(r.userId)card.append(el('div',`회원번호 ${r.userId}`,'muted'));
    if(!r.replayAvailable) card.append(el('div','상태 조회만 가능','muted'));
    function select(){state.selected=r.id; refreshRequestList(); if(r.kind==='case'){state.inspected='';state.jobEpoch++;$('jobDetail').replaceChildren(el('p','저장 테스트 case입니다. 실제 회원의 음역·가수 접수 결과가 아닙니다.','muted')); $('caseSelect').value=r.id; inputChanged(); } else { $('jobId').value=r.id; inspectJob(r.id); if(r.replayAvailable){ $('caseSelect').value=r.id; inputChanged(); } }}
    card.onclick=select; card.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();select();}}; $('requests').append(card);
  }
  if(!filtered.length) $('requests').append(el('p','설정된 소스에 요청이 없거나 검색 결과가 없습니다.','muted'));
}
let polling = false;
async function refreshRequests() {
  if(polling)return; polling=true;
  try {
    const body=await api('api/requests');
    state.requests=body.requests;
    const current=state.requests.find(r=>r.id===state.inspected);
    // Artist callbacks/DB results can change while this AI job stays COMPLETED.
    if(current&&current.kind!=='case')inspectJob(state.inspected,true);
    for(const id of [...state.shown]) if(!state.requests.some(r=>r.id===id && r.points)) state.shown.delete(id);
    const old=$('caseSelect').value; $('caseSelect').replaceChildren(new Option('재생 입력 선택',''));
    for(const r of state.requests.filter(r=>r.replayAvailable)) $('caseSelect').add(new Option(`${ownerText(r)} · ${r.title || r.analysisType || r.kind} · ${r.id}`,r.id));
    if(state.requests.some(r=>r.id===old && r.replayAvailable)) {$('caseSelect').value=old;$('inputInfo').textContent=inputInfo(state.requests.find(r=>r.id===old));}
    else if(old){$('caseSelect').value=''; inputChanged();}
    refreshRequestList(); drawMaps();
  }catch(e){error('요청 목록: '+e.message);}finally{polling=false;}
}
function fact(label,value){const n=el('div',undefined,'fact');n.append(el('small',label),el('div',value===null||value===undefined?'기록 없음':String(value)));return n;}
function midiNote(value){
  if(value===null||value===undefined||!Number.isFinite(Number(value)))return '미측정';
  const midi=Number(value),nearest=Math.round(midi),names=['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'];
  return `${names[nearest%12]}${Math.floor(nearest/12)-1}${Math.abs(midi-nearest)>.001?' (가장 가까운 음)':''}`;
}
function artistList(artists){
  const list=el('ol',undefined,'artist-list');
  for(const a of artists){const item=el('li');item.value=a.rank;item.dataset.artistId=a.artistId || '';item.append(el('strong',a.artistName || `가수 ID ${a.artistId || '없음'}`));
    const score=v=>v===null||v===undefined?'미기록':Number(v).toFixed(4);
    item.append(el('div',`가수 ID ${a.artistId || '없음 (이전 결과)'} · 유사도 ${score(a.similarity)}`,'muted'),el('div',`ECAPA ${score(a.ecapaScore)} · Singer-BYOL ${score(a.singerByolScore)}`,'muted'));
    if(a.nameSource==='historical-ai-record')item.append(el('div','이전 계산 기록의 이름 · ID 연결 안 됨','muted'));
    if(a.earlierAttempt)item.append(el('div','이전 시도에서 저장된 결과 · 최신 시도와 다름','failure-reason'));
    list.append(item);
  }return list;
}
function renderVoiceInsights(j){
  const holder=el('section',undefined,'voice-insights');holder.dataset.lookupStatus=j.voiceInsights?.lookupStatus || 'not-configured';holder.append(el('h3','회원 음역대 · 닮은 가수'));
  const data=j.voiceInsights;
  if(!data||data.lookupStatus!=='verified'){holder.append(el('p',data?.reason || '회원별 음역·가수 결과 소스가 연결되지 않았습니다.','muted'));return holder;}
  if(data.sessionContext==='current-profile')holder.append(el('p',`현재 프로필 ${data.currentProfileId}에 연결된 session ${data.sessionId}의 결과입니다. 이 과거 추천에는 원래 세션 ID가 저장되지 않아, 추천 당시 음역·닮은 가수는 확인할 수 없습니다.`,'metadata-check'));
  else holder.append(el('p',`이 요청에 연결된 session ${data.sessionId}의 현재 DB 저장 결과입니다. 과거 추천 시점의 입력과는 다를 수 있습니다.`,'muted'));
  const columns=el('div',undefined,'insight-columns');
  const range=el('div',undefined,'insight-card range-card');range.append(el('h3','앱에 등록된 전체 음역'),badge(data.rangeStatus));
  const r=data.registeredRange || {};
  if(r.boundsValid){range.append(el('div',`${midiNote(r.fullLowMidi)} ~ ${midiNote(r.fullHighMidi)}`,'range-value'),el('p',`MIDI ${r.fullLowMidi} ~ ${r.fullHighMidi}`,'muted'));}
  else range.append(el('p',r.measurementId?'유효한 최저·최고음이 저장되지 않았습니다.':'이 session에 등록된 앱 음역 측정 기록이 없습니다.','muted'));
  if(r.measurementId){const facts=el('div',undefined,'facts');facts.append(fact('측정 상태',r.measurementStatus),fact('품질',r.qualityStatus),fact('측정 시각 (앱 전송값)',r.measuredAt),fact('접수 시각 (서버)',r.submittedAt),fact('측정 방식 / 규칙',`${r.measurementSource || '-'} / ${r.ruleVersion || '-'}`));range.append(facts);
    if(r.measurementStatus!=='AVAILABLE')range.append(el('p','기록된 범위이며, 성공한 음역 측정으로 확정된 결과가 아닙니다.','failure-reason'));}
  range.append(el('p','전체 음역은 편안한/안정 음역이나 노래의 중심 음높이와 다릅니다. 이 기록에는 실측 중앙값·안정 음역이 없습니다.','muted'));columns.append(range);
  const backend=el('div',undefined,'insight-card backend-artists');backend.append(el('h3','백엔드에 실제 저장된 닮은 가수'),badge(data.artistSearchStatus));
  const bj=data.backendArtistJob;
  if(data.backendArtists?.length)backend.append(artistList(data.backendArtists));
  else {const message=data.artistSearchStatus==='NO_RESULT'?'처리 완료 · 닮은 가수 결과 0명':data.artistSearchStatus==='COMPLETED'?'완료 상태이지만 저장된 가수 목록은 없습니다.':'아직 저장된 추천 가수가 없습니다. 사용자에게 전달 완료된 결과가 아닙니다.';backend.append(el('p',message,'muted'));}
  if(bj){backend.append(el('p',`백엔드 작업 ${bj.componentJobId} · ${bj.status} · 시도 ${bj.attemptNo}`,'muted'));if(bj.aiJobId)backend.append(el('div',bj.aiJobId,'muted'));if(bj.failureCode||bj.failureReason)backend.append(el('p',[bj.failureCode,bj.failureReason].filter(Boolean).join(' · '),'failure-reason'));}
  backend.append(el('p','DB 저장 여부를 확인한 목록입니다. 앱 화면에 표시됐는지까지 확인한 것은 아닙니다.','muted'));columns.append(backend);holder.append(columns);
  const ai=data.aiArtistCalculation || {}, calc=el('div',undefined,'insight-card ai-artists');calc.dataset.lookupStatus=ai.lookupStatus || 'not-found';calc.append(el('h3','AI 계산 결과 · 전달 상태 별도'));
  if(ai.lookupStatus==='verified'){const statuses=el('div',undefined,'component-row');statuses.append(el('span','계산'),badge(ai.calculationStatus),el('span','전달'),badge(ai.deliveryStatus));calc.append(statuses,el('div',ai.jobId,'muted'),el('p',`콜백 HTTP ${ai.callbackHttp ?? '미기록'} · 백엔드 ${ai.callbackAccepted?'수락':'미수락'}${ai.deliveryFailureCode?' · '+ai.deliveryFailureCode:''}`,'muted'));
    if(ai.artists?.length)calc.append(artistList(ai.artists));else calc.append(el('p','보존된 계산 가수 목록이 없습니다.','muted'));
    if(!ai.callbackAccepted)calc.append(el('p','이 계산 목록은 백엔드 저장·사용자 전달이 완료된 추천이 아닙니다.','failure-reason'));
    if(!ai.versionCurrent)calc.append(el('p','현재 session의 결과 버전과 다른 이전 계산입니다.','failure-reason'));
  }else calc.append(el('p',ai.lookupStatus==='conflict'?'AI 기록의 session·버전 연결이 충돌하여 표시하지 않습니다.':ai.lookupStatus==='unavailable'?'AI 계산 기록을 읽지 못했습니다.':ai.lookupStatus==='not-configured'?'AI 검색 job 디렉터리가 연결되지 않았습니다.':'이 session의 보존된 AI 검색 계산 기록이 없습니다.','muted'));
  calc.append(el('p','가수 이름은 백엔드 singer 테이블에서 표시용으로 조회합니다. ID가 없는 과거 기록만 당시 보존된 이름을 표시하며 ID를 추측하지 않습니다. 유사도는 정확도/성공 확률이 아닙니다.','muted'));holder.append(calc);return holder;
}
async function inspectJob(id, quiet=false) {
  state.inspected=id;
  const epoch=++state.jobEpoch; if(!quiet)$('jobDetail').replaceChildren(el('p','조회 중…','muted'));
  try {
    const j=await api('api/jobs/'+encodeURIComponent(id)); if(epoch!==state.jobEpoch)return;
    const detail=$('jobDetail');detail.replaceChildren(badge(j.status));
    if(j.mrDetection){const box=el('div',undefined,'mr-detail');const head=el('div',undefined,'component-row');head.append(mrBadge(j.mrDetection),el('span',j.mrDetection.embeddingInput==='separated-vocals'?'보컬 분리본으로 임베딩했습니다.':j.mrDetection.embeddingInput==='raw-recording'?'분리 없이 원본으로 임베딩했습니다.':'임베딩 입력이 기록되지 않았습니다.'));box.append(head);
      for(const reason of j.mrDetection.reasons)box.append(el('div','· '+reason,'muted'));
      box.append(el('p',`바닥 비율 ${j.mrDetection.lowEnergyFloorRatio ?? '-'} · 동적 범위 ${j.mrDetection.dynamicRangeP95P10 ?? '-'} · 판정기 ${j.mrDetection.detectorVersion || '미기록'} · 전처리 ${j.mrDetection.preprocessingVersion || '미기록'}`,'muted'));
      if(j.mrDetection.separationFailureReason)box.append(el('p','보컬 분리 실패: '+j.mrDetection.separationFailureReason,'failure-reason'));
      box.append(el('p','AI 서버가 녹음 에너지로 내린 판정이며 MR 포함 여부를 단정하지 않습니다.','muted'));detail.append(box);}
    const facts=el('div',undefined,'facts'); facts.append(fact('가입 ID (로그인 이메일)',j.loginId || '확인 불가'),fact('회원번호 (내부 userId)',j.userId || '확인 불가'),fact('회원 확인 근거',j.ownerReason || '회원 연결 기록 없음'),fact('유형',j.analysisType||j.kind),fact('분석 컴포넌트 상태',j.componentStatus||j.status),fact('실패 코드',j.failureCode),fact('session / profile',`${j.sessionId ?? '-'} / ${j.profileId ?? '-'}`),fact('생성 시각',j.createdAt),fact('갱신 시각',j.updatedAt));detail.append(facts);
    if(j.loginIdStatus==='missing')detail.append(el('p','회원번호는 확인됐지만 가입 ID가 제공되지 않았습니다. 회원 연결 데이터 소스에 loginId가 필요합니다.','muted'));
    detail.append(renderVoiceInsights(j));
    if(j.failureReason)detail.append(el('div',j.failureReason,'failure-reason'));
    if(j.reasonUnavailable)detail.append(el('p',j.reasonUnavailable,'failure-reason'));
    for(const c of j.components){const line=el('div',undefined,'component-row');line.append(el('strong',c.name),badge(c.status),el('span',`콜백 HTTP ${c.callbackHttp ?? '미기록'} · 백엔드 ${c.accepted?'수락':'미수락'} · 시도 ${c.callbackAttempts ?? '-'}`));detail.append(line);if(c.failureReason&&c.failureReason!==j.failureReason)detail.append(el('p',c.failureReason,'failure-reason'));}
    if(j.components.length)detail.append(el('p','HTTP 200은 결과 전달 성공입니다. 분석 FAILED를 성공으로 바꾸지는 않습니다.','muted'));
    if(j.metadataChecks.length){const box=el('div',undefined,'metadata-check');box.append(el('strong','메타데이터 기반 진단 후보'));
      for(const a of j.metadataChecks)box.append(el('div',`${a.attemptId}: 목표 ${a.targetMidi} / 기준 ${Number(a.referenceMidi).toFixed(4)} / 차이 ${a.deltaSemitones.toFixed(4)}반음 / 앱 ${a.clientStatus}`));box.append(el('p',j.metadataCheckNote,'muted'));detail.append(box);}
    detail.append(el('p',j.replayReason || '분석 원본으로 진단 재생할 수 있습니다.','muted'));
    if(j.storedRecommendations.length){detail.append(el('h3','당시 AI job에 저장된 곡 추천 결과')); const list=el('ol');for(const p of j.storedRecommendations){const artist=p.artist || (p.artist_ids?.length?`가수 ID ${p.artist_ids.join(', ')}`:'가수명 확인 불가');list.append(el('li',`${artist} · ${p.title} (${p.final_score})`));}detail.append(list);}
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
function percent(value){return `${Math.round(Number(value)*100)}%`;}
function setOutput(id,text){const node=$(id);if(node)node.textContent=text;}
function updateSettingSummary(){
  const ecapa=Number($('ecapaWeight').value), centroid=Number($('centroidWeight').value), timbre=Number($('timbreWeight').value);
  setOutput('ecapaWeightValue',`ECAPA ${percent(ecapa)} · Singer-BYOL ${percent(1-ecapa)}`);
  setOutput('centroidWeightValue',`전체 평균 ${percent(centroid)} · 구간 매칭 ${percent(1-centroid)}`);
  setOutput('timbreWeightValue',`음색 ${percent(timbre)} · 음역·키 ${percent(1-timbre)}`);
  setOutput('maxKeyShiftValue',`±${$('maxKeyShift').value} 반음`);
  setOutput('maxCoreExcessValue',`${$('maxCoreExcess').value} 반음`);
  setOutput('minRangeScoreValue',Number($('minRangeScore').value).toFixed(2));
  setOutput('minProfileConfidenceValue',Number($('minProfileConfidence').value).toFixed(2));
  const mode=timbre===.75?'현재 timbre 모드 기본값':timbre===.3?'range 모드 기본값':'운영 모드가 아닌 맞춤 실험값';
  const composition=$('compositionMode').value;
  const compositionText=composition==='finalScore'?'할당 없음 · 최종 점수 순':'현행 · ECAPA/BYOL/합의 축 할당';
  setOutput('compositionModeValue',compositionText);
  $('compositionModeHelp').textContent=composition==='finalScore'
    ?'같은 후보 풀에서 축별 쿼터를 쓰지 않고 final score가 높은 순으로만 Top-K를 구성합니다. 점수식과 음역 게이트는 그대로입니다.'
    :'현행은 두 모델 축과 합의 축에서 먼저 곡을 고른 뒤 final score로 정렬합니다. 후보 풀·점수식·음역 게이트는 바꾸지 않습니다.';
  $('timbreWeightHelp').textContent=`${percent(timbre)}:${percent(1-timbre)} — ${mode}. 75:25는 timbre 모드, 30:70은 range 모드의 기본값이며, 다른 값은 두 점수를 실험용으로 재조합합니다.`;
  $('settingsSummary').textContent=`${compositionText} / ECAPA ${percent(ecapa)} · Singer-BYOL ${percent(1-ecapa)} / 전체 평균 ${percent(centroid)} · 구간 매칭 ${percent(1-centroid)} / 음색 ${percent(timbre)} · 음역·키 ${percent(1-timbre)}${$('useRangeGate').checked?' / 음역 게이트 사용':' / 음역 게이트 미사용'}`;
}
function scheduleAutoRun(){
  clearTimeout(state.autoTimer); if(!$('autoRun').checked||!$('caseSelect').value)return;
  if(state.busy){state.pendingAutoRun=true;return;}
  state.autoTimer=setTimeout(run,550);
}
function inputChanged(auto=false){state.epoch++; state.result=null; $('results').hidden=true;const r=state.requests.find(r=>r.id===$('caseSelect').value);$('run').disabled=!r||state.busy;
  $('inputInfo').textContent=inputInfo(r); updateSettingSummary();
  $('experimentState').textContent=auto&&r?'설정이 바뀌었습니다. 잠시 뒤 같은 입력으로 다시 계산합니다.':'입력 또는 설정이 변경됐습니다. 비교 실행을 누르거나 자동 계산을 켜세요.';
  if(auto)scheduleAutoRun();
}
function applyPreset(name){
  const values={current:{ecapaWeight:.5,centroidWeight:.3,timbreWeight:.75},global:{ecapaWeight:.5,centroidWeight:1,timbreWeight:.75},range:{ecapaWeight:.5,centroidWeight:.3,timbreWeight:.3}}[name];
  if(!values)return;for(const [id,value] of Object.entries(values))$(id).value=value; inputChanged(true);
}
async function run(){
  const id=$('caseSelect').value;if(!id)return;if(state.busy){state.pendingAutoRun=true;return;}
  for(const key of settingIds)if(!$(key).reportValidity())return;
  const epoch=++state.epoch; state.busy=true; $('run').disabled=true; error(''); $('experimentState').textContent='같은 입력으로 기본값과 후보 설정을 계산 중…';
  const settings={};for(const key of settingIds)settings[key]=Number($(key).value); settings.useRangeGate=$('useRangeGate').checked;settings.compositionMode=$('compositionMode').value;
  try{const result=await api('api/experiments',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({requestId:id,settings})}); if(epoch!==state.epoch)return;
    state.result=result; $('results').hidden=false;renderResults();$('experimentState').textContent=(result.inputWarning||'고정 입력 case 재생 완료')+' · '+(result.rangeAvailable?'음역 입력 있음':'음역 입력 없음: 기본값은 음색 단독 fallback');
  }catch(e){if(epoch===state.epoch){error(e.message);$('experimentState').textContent='실험 실패: '+e.message;}}
  finally{state.busy=false;$('run').disabled=!$('caseSelect').value;if(state.pendingAutoRun){state.pendingAutoRun=false;scheduleAutoRun();}}
}
function renderResults(){
  const result=state.result;if(!result)return;$('comparison').replaceChildren();
  for(const name of ['baseline','candidate']){const data=result[name];const card=el('div',undefined,'panel');card.append(el('h3',name==='baseline'?'기본값 최종 추천':'후보 설정 최종 추천'),el('p',`게이트 통과 ${data.eligibleCount} · 탈락 ${data.excludedCount} · 음역 게이트 ${data.rangeGateApplied?'적용':'미적용'}`,'muted'));const list=el('ol');
    for(const p of data.recommendations){const item=el('li',`${p.artist} · ${p.title}`);item.append(el('span',Number(p.final_score).toFixed(4),'score'));list.append(item);}card.append(list);if(!data.recommendations.length)card.append(el('p','게이트를 통과해 선택된 곡이 없습니다.','muted'));$('comparison').append(card);}
  $('comparison').append(el('p',`Top K 공통 곡 ${result.overlapAtK}개 · 배치 ${result.catalogFingerprint.slice(0,12)}`,'muted'));
  renderImprovement();
  renderRankings();
}
function improvementMetric(label,value){const card=el('div',undefined,'improvement-metric');card.append(el('small',label),el('strong',String(value)));return card;}
function renderImprovement(){
  const holder=$('improvement');holder.replaceChildren();const data=state.result?.improvement;if(!data)return;
  const rule=data.rules, base=data.baseline, candidate=data.candidate, verdict=data.verdict;
  holder.append(el('p',`음역 밖 히스토그램 질량 ≤ ${(rule.outsideMassLimit*100).toFixed(0)}% · 두 모델 상위 ${(rule.consensusFractions[0]*100).toFixed(0)}%→${(rule.consensusFractions[1]*100).toFixed(0)}% 합의 · RRF(${rule.rrfK}) 보충 · 가수당 최대 ${rule.maxSongsPerArtist}곡`,'muted'));
  const metrics=el('div',undefined,'improvement-metrics');
  for(const [label,source,key] of [['현행 고유 가수',base,'uniqueArtistCount'],['개선 고유 가수',candidate,'uniqueArtistCount'],['현행 중복 곡',base,'duplicateItems'],['개선 중복 곡',candidate,'duplicateItems'],['현행 한 가수 최다',base,'maxSongsPerArtist'],['개선 한 가수 최다',candidate,'maxSongsPerArtist']])metrics.append(improvementMetric(label,source.metrics[key]));
  holder.append(metrics);
  const status=el('p',verdict.artistDiversityImproved?'가수 다양성 지표는 개선되었습니다.':'가수 다양성 지표는 개선되지 않았거나 같은 수준입니다.',verdict.artistDiversityImproved?'improvement-pass':'improvement-note');
  status.append(' ',el('span',verdict.artistCapSatisfied?`가수 상한 ${rule.maxSongsPerArtist}곡 충족`:'가수 상한 미충족'));
  if(data.rangeProxy.available)status.append(' · ',el('span',verdict.rangeProxySatisfied?'음역 프록시 충족':'음역 프록시 미충족'));
  holder.append(status,el('p',data.rangeProxy.description,'muted'));
  const columns=el('div',undefined,'improvement-columns');
  for(const [title,side] of [['현행 Top K',base],['개선안 Top K',candidate]]){
    const card=el('div',undefined,'improvement-list');card.append(el('h3',title));const list=el('ol');
    for(const item of side.recommendations){const ratio=item.outsideMassRatio===null||item.outsideMassRatio===undefined?'음역 프록시 없음':`밖 ${(100*item.outsideMassRatio).toFixed(1)}%`;const text=title==='개선안 Top K'?`${item.artist} · ${item.title} — ${item.source} · ${ratio}`:`${item.artist} · ${item.title} — ${ratio}`;list.append(el('li',text));}
    card.append(list);const repeats=side.metrics.repeatedArtists;if(repeats.length)card.append(el('p','중복 가수: '+repeats.map(x=>`${x.artist} ${x.count}곡`).join(', '),'muted'));else card.append(el('p','동일 가수 중복 없음','muted'));columns.append(card);
  }
  holder.append(columns,el('p',verdict.qualityClaim,'improvement-note'));
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
$('refresh').onclick=refreshRequests;$('requestSearch').oninput=refreshRequestList;$('caseSelect').onchange=()=>inputChanged(true);$('run').onclick=run;
$('jobForm').onsubmit=e=>{e.preventDefault();inspectJob($('jobId').value.trim());};
$('resetMap').onclick=()=>{for(const model of models)views[model]={scale:1,x:0,y:0};state.artist=null;drawMaps();};
for(const id of settingIds){$(id).oninput=()=>inputChanged(true);$(id).onchange=()=>inputChanged(true);}
$('useRangeGate').onchange=()=>inputChanged(true);
$('compositionMode').onchange=()=>inputChanged(true);
$('autoRun').onchange=()=>{if($('autoRun').checked)scheduleAutoRun();else clearTimeout(state.autoTimer);};
for(const button of document.querySelectorAll('[data-preset]'))button.onclick=()=>applyPreset(button.dataset.preset);
for(const id of ['trackSearch','onlyExcluded','rowLimit'])$(id).oninput=renderRankings;
$('baselineTab').onclick=()=>{state.tab='baseline';renderRankings();};$('candidateTab').onclick=()=>{state.tab='candidate';renderRankings();};$('export').onclick=exportResult;
updateSettingSummary();
start();
