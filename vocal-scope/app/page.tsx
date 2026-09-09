'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { RangeAnalysis } from '../lib/audio-analysis';
import { KaraokeEditor, REGISTER_COLORS, REGISTER_LABELS, lightnessColor, midiName, type KaraokeNote } from './karaoke-editor';
import { KeyShiftPlayer } from './key-shift-player';
import { PerformanceCompare } from './performance-compare';
import { LiveScoring } from './live-scoring';

type Phase = 'idle' | 'uploading' | 'analyzing' | 'done' | 'error';
type SourceType = 'mix' | 'vocal';
type Separator = 'melband' | 'htdemucs';
type EngineState = 'checking' | 'online' | 'offline';
type ModelResult = RangeAnalysis & {
  noteCount: number;
  voicedDuration: number;
  device: string;
  model: string;
  tempoBpm: number;
  tempoRawBpm?: number | null;
  tempoCandidates?: number[];
  timeSignature: string;
  meterConfidence: number;
  timeSignatureCandidates?: { timeSignature: string; confidence: number }[];
  vocalStart: number;
  vocalEnd: number;
  voicedRatio: number;
  voicedSeconds: number;
  f0AgreementRatio: number;
  octaveMismatchRatio: number;
  tuningCents: number;
  key: { name: string; sharps: number; confidence: number; tonic?: number; mode?: string };
  pitchMethod: string;
  artifacts: Record<string, string>;
  lyrics?: { source: 'user' | 'whisper' | 'none'; text: string; syllables: number; alignmentScore: number | null; unknownTokens?: number; explicitExtensions?: number; error: string | null };
  karaoke?: { sourceNotes: number; karaokeNotes: number; notesWithLyric: number; melismaNotes?: number; lowConfidenceLyrics?: number; quantizationAdjustments?: number; unassignedSyllables: number; lyricsEmbedded?: boolean; edited?: boolean };
  karaokeNotes?: KaraokeNote[];
  karaokeVersion?: number;
  scoreOctave?: { mode: 'auto' | 'up' | 'actual'; shift: number };
  separation?: { backend: string; model: string | null; seconds: number; requested?: string; fallbackReason?: string; chunkSeconds?: number; oomRetries?: number };
  register?: {
    error?: string;
    method?: string;
    bimodal?: boolean;
    ashmanD?: number | null;
    singleRegisterLabel?: 'modal' | 'unclear' | null;
    seconds?: Record<RegisterLabel, number>;
    ratios?: Record<RegisterLabel, number>;
    medianFeatures?: Record<string, number | null>;
    modalRange?: string | null;
    falsettoRange?: string | null;
    modalRangeMidi?: number[] | null;
    falsettoRangeMidi?: number[] | null;
    usableFrames?: number;
    atypicalFrames?: number;
    caveats?: string[];
    confidenceScale?: number;
    breathyRatio?: number;
    falsettoPitchFloorMidi?: number | null;
    twoRegisters?: boolean;
    lightnessMean?: number | null;
    lightnessHistogram?: number[];
  };
};
type RegisterLabel = 'modal' | 'falsetto' | 'mixed' | 'unclear';
const REGISTER_ORDER: RegisterLabel[] = ['modal', 'falsetto', 'mixed', 'unclear'];
type ScoreView = 'karaoke' | 'source';
type ModelJob = {
  id: string;
  status: 'queued' | 'processing' | 'complete' | 'failed';
  progress: number;
  message: string;
  error?: string | null;
  result?: ModelResult | null;
};
type EngineInfo = { device: string; gpu?: string | null; models: string[] };
type SavedJob = { id: string; filename: string; key?: string | null; tempoBpm?: number | null; vocalStart?: number | null; vocalEnd?: number | null; hasKaraoke?: boolean; createdAt?: number | null };

const MODEL_API = process.env.NEXT_PUBLIC_MODEL_API || 'http://127.0.0.1:8787';
const bars = [24, 48, 30, 66, 86, 54, 92, 43, 70, 34, 78, 58, 88, 46, 64, 27, 51, 36];
const sleep = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const formatPercent = (ratio: number) => `${Math.round(ratio * 100)}%`;
const qualityFlag = (result: { f0AgreementRatio: number; octaveMismatchRatio: number }) =>
  result.f0AgreementRatio < 0.6 || result.octaveMismatchRatio > 0.1;
const formatTime = (seconds: number) => {
  const tenths = Math.round(seconds * 10);
  return `${Math.floor(tenths / 600)}:${String(Math.floor((tenths % 600) / 10)).padStart(2, '0')}.${tenths % 10}`;
};

const noteLabelToMidi = (label: string): number | null => {
  const match = /^\s*([A-G])([♯#♭b]?)(\d)\s*$/.exec(label);
  if (!match) return null;
  const base: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
  const accidental = match[2] === '♯' || match[2] === '#' ? 1 : match[2] ? -1 : 0;
  return (Number(match[3]) + 1) * 12 + base[match[1]] + accidental;
};

/** BPM 게이지: 속도계 모양의 원호 + 바늘 (60–190 BPM 스케일) */
function TempoGauge({ bpm }: { bpm: number }) {
  const clamped = Math.max(60, Math.min(190, bpm));
  const sweep = ((clamped - 60) / 130) * 240; // -120° → +120°
  const toPoint = (deg: number, radius: number) => {
    const rad = (deg * Math.PI) / 180;
    return [21 + radius * Math.sin(rad), 22 - radius * Math.cos(rad)] as const;
  };
  const [sx, sy] = toPoint(-120, 15);
  const [ex, ey] = toPoint(120, 15);
  const [px, py] = toPoint(-120 + sweep, 15);
  const [nx, ny] = toPoint(-120 + sweep, 10);
  return (
    <svg viewBox="0 0 42 42" className="tempo-gauge" aria-hidden="true">
      <path d={`M ${sx} ${sy} A 15 15 0 1 1 ${ex} ${ey}`} fill="none" stroke="#455149" strokeWidth="4.5" strokeLinecap="round" />
      <path d={`M ${sx} ${sy} A 15 15 0 ${sweep > 180 ? 1 : 0} 1 ${px} ${py}`} fill="none" stroke="#d7ff43" strokeWidth="4.5" strokeLinecap="round" />
      <line x1="21" y1="22" x2={nx} y2={ny} stroke="#f5f3ea" strokeWidth="2.5" strokeLinecap="round" />
      <circle cx="21" cy="22" r="3" fill="#d7ff43" />
    </svg>
  );
}

const BLACK_PCS = new Set([1, 3, 6, 8, 10]);
const WHITES_BEFORE = [0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6]; // 옥타브 내 피치클래스 → 앞선 흰건반 수

/** 음역대 지도: 피아노 건반 위에 발성 시간 분포·핵심 음역 밴드·성구 음역을 표시 */
function RangeMap({ result }: { result: ModelResult }) {
  const durations = new Map<number, number>();
  for (const note of result.notes) durations.set(note.midi, (durations.get(note.midi) ?? 0) + note.duration);
  if (!durations.size) return null;
  const coreParts = result.coreRange.split('–').map(noteLabelToMidi);
  const core = coreParts[0] != null && coreParts[1] != null ? ([coreParts[0], coreParts[1]] as const) : null;
  const keyLo = Math.floor(Math.min(result.lowestMidi, core?.[0] ?? Infinity) / 12) * 12; // 옥타브 시작(C)
  const keyHi = Math.ceil((Math.max(result.highestMidi, core?.[1] ?? -Infinity) + 1) / 12) * 12 - 1; // 옥타브 끝(B)
  const totalWhites = ((keyHi + 1 - keyLo) / 12) * 7;
  const W = 880;
  const left = 14;
  const ww = (W - left * 2) / totalWhites;
  const whiteIndex = (midi: number) => Math.floor((midi - keyLo) / 12) * 7 + WHITES_BEFORE[(midi - keyLo) % 12];
  const centerOf = (midi: number) => (BLACK_PCS.has(midi % 12) ? left + (whiteIndex(midi) + 1) * ww : left + whiteIndex(midi) * ww + ww / 2);
  const edgeL = (midi: number) => (BLACK_PCS.has(midi % 12) ? centerOf(midi) - ww * 0.31 : left + whiteIndex(midi) * ww);
  const edgeR = (midi: number) => (BLACK_PCS.has(midi % 12) ? centerOf(midi) + ww * 0.31 : left + (whiteIndex(midi) + 1) * ww);
  const histTop = 4;
  const histH = 52;
  const markerY = histTop + histH + 15;
  const kbTop = markerY + 7;
  const kbH = 76;
  const blackH = 47;
  const bracketY = kbTop + kbH + 12;
  const registerRows = [
    result.register?.modalRangeMidi?.length === 2 ? { label: `진성 우세 ${result.register.modalRange ?? ''}`, range: result.register.modalRangeMidi, color: '#68b581' } : null,
    result.register?.falsettoRangeMidi?.length === 2 ? { label: `가성 우세 ${result.register.falsettoRange ?? ''}`, range: result.register.falsettoRangeMidi, color: REGISTER_COLORS.falsetto } : null,
  ].filter((row): row is NonNullable<typeof row> => row != null);
  const rowsTop = bracketY + 26;
  const H = rowsTop + registerRows.length * 17 + 2;
  const maxDuration = Math.max(1e-6, ...durations.values());
  const allMidis: number[] = [];
  for (let midi = keyLo; midi <= keyHi; midi++) allMidis.push(midi);
  const boundaryLabels = core ? [...new Set([result.lowestMidi, core[0], core[1], result.highestMidi])] : [result.lowestMidi, result.highestMidi];
  return (
    <div className="range-map" aria-label="음역대 지도">
      <div className="range-map-head">
        <div><p className="eyebrow">RANGE MAP · 음역대 지도</p><h3>어느 음을 얼마나 오래 불렀나요?</h3></div>
        <small>건반 위 막대 = 각 음의 발성 시간 · <b style={{ color: '#d7ff43' }}>형광 밴드</b> = 핵심 음역(발성 시간의 5–95%)</small>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`전체 음역 ${result.lowest}–${result.highest}, 핵심 음역 ${result.coreRange}`}>
        {[...durations.entries()].filter(([midi]) => midi >= keyLo && midi <= keyHi).map(([midi, seconds]) => {
          const barH = Math.max(2, (seconds / maxDuration) * histH);
          const inCore = core ? midi >= core[0] && midi <= core[1] : false;
          return <rect key={`hist-${midi}`} x={centerOf(midi) - ww * 0.26} y={histTop + histH - barH} width={ww * 0.52} height={barH} rx={1.5} fill={inCore ? '#d7ff43' : '#55605a'}><title>{`${midiName(midi)} · ${seconds.toFixed(1)}초`}</title></rect>;
        })}
        {boundaryLabels.map((midi) => {
          const isCoreBound = core != null && (midi === core[0] || midi === core[1]);
          return (
            <g key={`marker-${midi}`}>
              <text x={centerOf(midi)} y={markerY - 7} textAnchor="middle" fontSize="11" fontWeight={isCoreBound ? 800 : 500} fill={isCoreBound ? '#d7ff43' : '#87938b'}>{midiName(midi)}</text>
              <circle cx={centerOf(midi)} cy={markerY} r="2.6" fill={isCoreBound ? '#d7ff43' : '#87938b'} />
            </g>
          );
        })}
        {Array.from({ length: totalWhites }, (_, index) => {
          const midi = keyLo + Math.floor(index / 7) * 12 + [0, 2, 4, 5, 7, 9, 11][index % 7];
          const inRange = midi >= result.lowestMidi && midi <= result.highestMidi;
          return (
            <g key={`white-${index}`}>
              <rect x={left + index * ww + 0.5} y={kbTop} width={ww - 1} height={kbH} rx={2} fill={inRange ? '#f5f3ea' : '#b9b6aa'} />
              {midi % 12 === 0 && <text x={left + index * ww + ww / 2} y={kbTop + kbH - 6} textAnchor="middle" fontSize="9" fill="#8a9089">{midiName(midi)}</text>}
            </g>
          );
        })}
        {allMidis.filter((midi) => BLACK_PCS.has(midi % 12)).map((midi) => (
          <rect key={`black-${midi}`} x={centerOf(midi) - ww * 0.31} y={kbTop} width={ww * 0.62} height={blackH} rx={2} fill="#141b17" stroke="rgba(255,255,255,.22)" strokeWidth="0.6" />
        ))}
        {core && <rect x={edgeL(core[0])} y={kbTop} width={edgeR(core[1]) - edgeL(core[0])} height={kbH} rx={3} fill="rgba(215,255,67,.24)" stroke="#d7ff43" strokeWidth="1.2" />}
        {core && (
          <g>
            <path d={`M ${edgeL(core[0])} ${bracketY - 4} V ${bracketY} H ${edgeR(core[1])} V ${bracketY - 4}`} fill="none" stroke="#d7ff43" strokeWidth="1.4" />
            <text x={(edgeL(core[0]) + edgeR(core[1])) / 2} y={bracketY + 14} textAnchor="middle" fontSize="11" fontWeight="800" fill="#d7ff43">핵심 음역 {result.coreRange}</text>
          </g>
        )}
        {registerRows.map((row, index) => {
          const y = rowsTop + index * 17;
          const x0 = centerOf(row.range![0]);
          const x1 = Math.max(x0 + 4, centerOf(row.range![1]));
          const labelFits = x1 + 8 + row.label.length * 6.2 < W - left;
          return (
            <g key={row.label}>
              <line x1={x0} y1={y} x2={x1} y2={y} stroke={row.color} strokeWidth="6" strokeLinecap="round" />
              <text x={labelFits ? x1 + 8 : x0 - 8} y={y + 3.5} textAnchor={labelFits ? 'start' : 'end'} fontSize="10" fill="#cfd8d0">{row.label}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

function MusicScore({ source, noteCount, tempoBpm, timeSignature, karaoke }: { source: string; noteCount: number; tempoBpm: number; timeSignature: string; karaoke: boolean }) {
  const scoreRef = useRef<HTMLDivElement>(null);
  const [scoreState, setScoreState] = useState<'loading' | 'ready' | 'error'>('loading');

  useEffect(() => {
    let cancelled = false;
    const container = scoreRef.current;
    if (!container) return;
    container.replaceChildren();
    setScoreState('loading');

    const renderScore = async () => {
      try {
        const response = await fetch(source, { cache: 'no-store' });
        if (!response.ok) throw new Error('악보 파일을 불러오지 못했습니다.');
        const xml = await response.text();
        const { OpenSheetMusicDisplay } = await import('opensheetmusicdisplay');
        if (cancelled) return;
        const osmd = new OpenSheetMusicDisplay(container, {
          autoResize: true,
          backend: 'svg',
          drawingParameters: 'compacttight',
          drawTitle: false,
          drawComposer: false,
          drawPartNames: false,
          drawMeasureNumbers: true,
          drawMeasureNumbersOnlyAtSystemStart: true,
          drawMetronomeMarks: true,
          drawTimeSignatures: true,
          drawLyrics: true,
        });
        await osmd.load(xml);
        if (cancelled) return;
        osmd.render();
        setScoreState('ready');
      } catch {
        if (!cancelled) setScoreState('error');
      }
    };

    void renderScore();
    return () => {
      cancelled = true;
      container.replaceChildren();
    };
  }, [source]);

  return (
    <>
      <div className="score-legend">
        <b>음표명 표기</b><span><em>C3</em> = 3옥타브 C</span><span><em>F♯4</em> = 4옥타브 F♯</span>
        {karaoke ? <span>1행 = 가사 음절, 2행 = 음이름 · 장식음을 정리한 노래방 기준 멜로디입니다.</span> : <span>원가수 표현(꾸밈음·이음)을 그대로 옮긴 채보입니다.</span>}
      </div>
      <div className={`sheet-scroll ${scoreState}`}>
        {scoreState === 'loading' && <p>전체 악보를 그리고 있습니다…</p>}
        {scoreState === 'error' && <p>화면 악보를 불러오지 못했습니다. MusicXML 다운로드 파일에서는 전체 악보를 확인할 수 있습니다.</p>}
        <div ref={scoreRef} className="sheet-music" aria-label={karaoke ? '노래방 오선 악보' : '원곡 채보 오선 악보'} />
      </div>
      <p className="score-caption">{noteCount}개 음표 · BPM과 첫 박자 위치를 음원에서 추정해 {timeSignature} 박자 격자에 정렬했습니다. 추정 {tempoBpm} BPM.</p>
    </>
  );
}

function ScoreSection({ result, jobId, onResult }: { result: ModelResult; jobId: string; onResult: (next: ModelResult) => void }) {
  const hasKaraoke = Boolean(result.karaokeNotes?.length);
  const [view, setView] = useState<ScoreView>(hasKaraoke ? 'karaoke' : 'source');
  const [saving, setSaving] = useState(false);
  const [realigning, setRealigning] = useState(false);
  const [lyricsDraft, setLyricsDraft] = useState(result.lyrics?.text ?? '');
  const [editingLyrics, setEditingLyrics] = useState(false);
  const [error, setError] = useState('');
  const version = result.karaokeVersion ?? 1;
  const meter = Number(result.timeSignature.split('/')[0]) || 4;
  const url = (kind: string) => `${MODEL_API}/jobs/${jobId}/files/${kind}?v=${version}`;

  const post = async (path: string, body: unknown) => {
    const response = await fetch(`${MODEL_API}/jobs/${jobId}/${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const payload = await response.json() as ModelJob & { detail?: string };
    if (!response.ok || !payload.result) throw new Error(payload.detail || '요청을 처리하지 못했습니다.');
    return payload.result;
  };

  const octaveMode = result.scoreOctave?.mode ?? 'auto';
  const octaveShift = result.scoreOctave?.shift ?? 0;

  const saveKaraoke = async (notes: KaraokeNote[], octave: string = octaveMode) => {
    setSaving(true);
    setError('');
    try {
      onResult(await post('karaoke', { notes, octave }));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '악보를 다시 만들지 못했습니다.');
    } finally {
      setSaving(false);
    }
  };

  const realign = async () => {
    setRealigning(true);
    setError('');
    try {
      onResult(await post('lyrics', { text: lyricsDraft }));
      setEditingLyrics(false);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '가사를 다시 정렬하지 못했습니다.');
    } finally {
      setRealigning(false);
    }
  };

  const lyrics = result.lyrics;
  const sourceLabel = lyrics?.source === 'user' ? '입력한 가사' : lyrics?.source === 'whisper' ? 'Whisper 자동 받아쓰기' : '가사 없음';

  return (
    <div className="score-card">
      <div className="score-head">
        <div><span>{view === 'karaoke' ? 'KARAOKE SCORE' : 'FULL VOCAL SCORE'}</span><small>{view === 'karaoke' ? `노래방 기준 멜로디 ${result.karaokeNotes?.length ?? 0}개 음표 · 가사 ${result.karaoke?.notesWithLyric ?? 0}개` : `원곡 채보 ${result.noteCount}개 음표`} · {result.tempoBpm} BPM · {result.timeSignature}</small></div>
        <span aria-hidden="true">𝄞</span>
      </div>
      <div className="score-controls">
        <div className="score-tabs" role="tablist">
          <button type="button" role="tab" aria-selected={view === 'karaoke'} className={view === 'karaoke' ? 'active' : ''} onClick={() => setView('karaoke')} disabled={!hasKaraoke}>노래방 악보</button>
          <button type="button" role="tab" aria-selected={view === 'source'} className={view === 'source' ? 'active' : ''} onClick={() => setView('source')}>원곡 채보</button>
        </div>
        <label className="octave-select">
          <span>표기 옥타브</span>
          <select value={octaveMode} disabled={saving || !hasKaraoke} onChange={(event) => void saveKaraoke(result.karaokeNotes ?? [], event.target.value)}>
            <option value="auto">자동 (현재: {octaveShift ? '한 옥타브 위 · 8vb' : '실음'})</option>
            <option value="up">한 옥타브 위에 표기 (남성곡 관례 · 8vb)</option>
            <option value="actual">실음 그대로 표기</option>
          </select>
          <small>{octaveShift ? '음표는 한 옥타브 위에 그리고, 음이름(예: D3)은 실제 음높이입니다.' : '음표를 실제 음높이에 그립니다. 낮은 음은 덧줄이 많아집니다.'}</small>
        </label>
      </div>
      {view === 'karaoke' && (
        <div className="lyrics-summary">
          <div>
            <b>LYRICS · {sourceLabel}{lyrics?.alignmentScore != null ? ` · 정렬 신뢰도 ${Math.round(lyrics.alignmentScore * 100)}%` : ''}{lyrics?.syllables ? ` · ${lyrics.syllables}음절` : ''}</b>
            {lyrics?.error && <small className="lyrics-error">가사 처리 실패: {lyrics.error}</small>}
            {editingLyrics ? (
              <textarea value={lyricsDraft} onChange={(event) => setLyricsDraft(event.target.value)} placeholder="정확한 가사를 줄 단위로 붙여 넣으세요" aria-label="가사 수정" />
            ) : (
              <p>{lyrics?.text || '가사가 없어 음이름만 표기했습니다. 정확한 가사를 붙여 넣고 다시 정렬하면 음절이 음표에 붙습니다.'}</p>
            )}
            {lyrics?.source === 'whisper' && !editingLyrics && <small>자동 받아쓰기는 오탈자가 있을 수 있습니다. “가사 수정”으로 정확한 가사를 넣으면 정렬이 더 정확해집니다.</small>}
            {!!lyrics?.unknownTokens && !editingLyrics && <small className="lyrics-error">영어·숫자 {lyrics.unknownTokens}개는 현재 한국어 정렬 모델의 미등록 토큰이라 근사 타이밍입니다.</small>}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {editingLyrics ? (
              <>
                <button type="button" onClick={realign} disabled={realigning || !lyricsDraft.trim()}>{realigning ? '정렬 중…' : '가사 다시 정렬'}</button>
                <button type="button" onClick={() => { setEditingLyrics(false); setLyricsDraft(lyrics?.text ?? ''); }} disabled={realigning}>취소</button>
              </>
            ) : (
              <button type="button" onClick={() => setEditingLyrics(true)}>가사 수정</button>
            )}
          </div>
        </div>
      )}
      {error && <div className="quality-flag" role="alert"><b>{error}</b></div>}
      <MusicScore
        source={url(view === 'karaoke' ? 'karaoke-musicxml' : 'musicxml')}
        noteCount={view === 'karaoke' ? result.karaokeNotes?.length ?? 0 : result.noteCount}
        tempoBpm={result.tempoBpm}
        timeSignature={result.timeSignature}
        karaoke={view === 'karaoke'}
      />
      {view === 'karaoke' && result.karaokeNotes && (
        <KaraokeEditor key={version} initialNotes={result.karaokeNotes} meter={meter} tempoBpm={result.tempoBpm} saving={saving} onSave={saveKaraoke} />
      )}
    </div>
  );
}

export default function Home() {
  const inputRef = useRef<HTMLInputElement>(null);
  const resultRef = useRef<HTMLElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [sourceType, setSourceType] = useState<SourceType>('mix');
  const [separator, setSeparator] = useState<Separator>('melband');
  const [lyricsText, setLyricsText] = useState('');
  const [phase, setPhase] = useState<Phase>('idle');
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState('');
  const [jobId, setJobId] = useState('');
  const [result, setResult] = useState<ModelResult | null>(null);
  const [engineState, setEngineState] = useState<EngineState>('checking');
  const [engineInfo, setEngineInfo] = useState<EngineInfo | null>(null);
  const [resultName, setResultName] = useState('');
  const [liveJob, setLiveJob] = useState<unknown>(null);
  const [savedJobs, setSavedJobs] = useState<SavedJob[]>([]);
  const [loadingSaved, setLoadingSaved] = useState(false);

  const refreshSavedJobs = useCallback(async () => {
    try {
      const response = await fetch(`${MODEL_API}/jobs?kind=reference&limit=200`, { cache: 'no-store' });
      if (!response.ok) return;
      const payload = await response.json() as { jobs: SavedJob[] };
      setSavedJobs(payload.jobs.filter((item) => item.filename));
    } catch {
      /* listing is optional */
    }
  }, []);

  const checkEngine = useCallback(async () => {
    setEngineState('checking');
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 3500);
      const response = await fetch(`${MODEL_API}/health`, { signal: controller.signal });
      clearTimeout(timer);
      if (!response.ok) throw new Error('offline');
      const info = await response.json() as EngineInfo;
      setEngineInfo(info);
      setEngineState('online');
      void refreshSavedJobs();
      return true;
    } catch {
      setEngineState('offline');
      return false;
    }
  }, [refreshSavedJobs]);

  /** 이전에 분석한 원곡 결과를 다시 불러와 결과 화면(+ 부른 노래 채점)을 연다. */
  const loadSavedJob = async (id: string) => {
    if (!id || loadingSaved) return;
    setLoadingSaved(true);
    try {
      const response = await fetch(`${MODEL_API}/jobs/${id}`, { cache: 'no-store' });
      const job = await response.json() as ModelJob & { filename?: string; detail?: string };
      if (!response.ok || job.status !== 'complete' || !job.result) throw new Error(job.detail || '저장된 분석을 불러오지 못했습니다.');
      setFile(null);
      setLiveJob(null);
      setJobId(job.id);
      setResult(job.result);
      setResultName(job.filename || '저장된 원곡');
      setPhase('done');
      setMessage('');
      setTimeout(() => resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 100);
    } catch (error) {
      setPhase('error');
      setMessage(error instanceof Error ? error.message : '저장된 분석을 불러오지 못했습니다.');
    } finally {
      setLoadingSaved(false);
    }
  };

  useEffect(() => {
    const timer = setTimeout(() => { void checkEngine(); }, 0);
    return () => clearTimeout(timer);
  }, [checkEngine]);

  const chooseFile = (next?: File) => {
    if (!next) return;
    setFile(next);
    setPhase('idle');
    setResult(null);
    setMessage('');
  };

  const startAnalysis = async () => {
    if (!file || phase === 'uploading' || phase === 'analyzing') return;
    try {
      if (engineState !== 'online' && !(await checkEngine())) {
        throw new Error('PC의 VocalScope AI 엔진이 꺼져 있습니다. 실행 파일을 먼저 열어주세요.');
      }
      setPhase('uploading');
      setProgress(1);
      setMessage('로컬 AI 엔진으로 음원을 전송하고 있습니다.');
      const form = new FormData();
      form.append('audio', file);
      form.append('sourceType', sourceType);
      form.append('separator', separator);
      form.append('lyrics', lyricsText.trim());
      const response = await fetch(`${MODEL_API}/jobs`, { method: 'POST', body: form });
      const payload = await response.json() as ModelJob & { detail?: string };
      if (!response.ok) throw new Error(payload.detail || 'AI 엔진에 음원을 전달하지 못했습니다.');
      setJobId(payload.id);
      setLiveJob(null);
      setResultName(file.name);
      setPhase('analyzing');

      for (;;) {
        await sleep(1400);
        const statusResponse = await fetch(`${MODEL_API}/jobs/${payload.id}`, { cache: 'no-store' });
        if (!statusResponse.ok) throw new Error('분석 상태를 확인하지 못했습니다.');
        const job = await statusResponse.json() as ModelJob;
        setProgress(job.progress);
        setMessage(job.message);
        if (job.status === 'failed') throw new Error(job.error || 'AI 모델 분석에 실패했습니다.');
        if (job.status === 'complete' && job.result) {
          setResult(job.result);
          setPhase('done');
          void refreshSavedJobs();
          setTimeout(() => resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 100);
          break;
        }
      }
    } catch (error) {
      setPhase('error');
      setMessage(error instanceof Error ? error.message : '분석 중 오류가 발생했습니다.');
    }
  };

  const artifactUrl = (kind: string) => `${MODEL_API}/jobs/${jobId}/files/${kind}`;
  const working = phase === 'uploading' || phase === 'analyzing';
  const statusText = engineState === 'online'
    ? `${engineInfo?.device === 'cuda' ? 'GPU' : 'CPU'} ENGINE ONLINE`
    : engineState === 'checking' ? 'ENGINE CHECK' : 'ENGINE OFFLINE';

  return (
    <main>
      <header className="site-header">
        <a className="brand" href="#" aria-label="VocalScope 홈"><span className="brand-mark" aria-hidden="true">V</span><span>VocalScope</span></a>
        <nav aria-label="주요 메뉴"><a href="#workflow">작업 과정</a><a href="#analysis">분석 항목</a><button className={`status-pill ${engineState}`} onClick={checkEngine} type="button"><i /> {statusText}</button></nav>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <p className="eyebrow">VOCAL INTELLIGENCE STUDIO</p>
          <h1>노래 한 곡에서<br/><em>목소리의 범위</em>를 발견하세요.</h1>
          <p className="hero-description">Demucs가 보컬을 분리하고 보컬 활동 감지(VAD)로 노래가 시작되기 전 구간을 제외합니다. CREPE Full과 pYIN 두 경로가 가창 기본주파수(F0)를 교차 검증한 단선율을 박자 격자에 맞춰 악보로 만듭니다.</p>
          <div className="trust-row" aria-label="사용 모델"><span>MEL-BAND ROFORMER SEPARATION</span><span>VAD + CREPE FULL F0</span><span>PYIN CROSS-CHECK</span><span>WHISPER + WAV2VEC2 LYRICS</span><span>DTW SING SCORE</span></div>
          <div className={`engine-card ${engineState}`}>
            <span className="engine-light" />
            <div><b>{engineState === 'online' ? 'AI 엔진 연결됨' : engineState === 'checking' ? 'AI 엔진 확인 중' : 'AI 엔진 실행 필요'}</b><small>{engineState === 'online' ? `${engineInfo?.gpu || 'CPU'} · ${engineInfo?.models.join(' + ')}` : '이 PC의 로컬 모델 서버를 실행하면 전체 분석을 시작할 수 있습니다.'}</small></div>
            {engineState === 'offline' && <button type="button" onClick={checkEngine}>다시 확인</button>}
          </div>
        </div>

        <div className="studio-shell">
          <div className="studio-topline"><span>NEW AI ANALYSIS</span><span className="secure-label">● LOCAL & PRIVATE</span></div>
          <div className="source-switch" role="radiogroup" aria-label="음원 종류">
            <button className={sourceType === 'mix' ? 'active' : ''} onClick={() => setSourceType('mix')} role="radio" aria-checked={sourceType === 'mix'} type="button"><b>원곡 전체 분석 · 권장</b><small>보컬 분리와 박자 추정 포함</small></button>
            <button className={sourceType === 'vocal' ? 'active' : ''} onClick={() => setSourceType('vocal')} role="radio" aria-checked={sourceType === 'vocal'} type="button"><b>보컬 파일 분석</b><small>분리 생략 · F0 추적 중심 채보</small></button>
          </div>
          {sourceType === 'mix' && (
            <label className="separator-field">
              <span>보컬 분리 모델</span>
              <select value={separator} onChange={(event) => setSeparator(event.target.value as Separator)}>
                <option value="melband">Mel-Band RoFormer · 권장 (보컬 SDR ≈ 11 dB, 배음 보존)</option>
                <option value="htdemucs">HTDemucs · 빠름 (보컬 SDR ≈ 8.4 dB)</option>
              </select>
            </label>
          )}
          <div
            className={`drop-zone ${dragging ? 'is-dragging' : ''} ${file ? 'has-file' : ''}`}
            onDragOver={(event) => { event.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFile(event.dataTransfer.files[0]); }}
            onClick={() => inputRef.current?.click()}
            role="button" tabIndex={0}
            onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') inputRef.current?.click(); }}
          >
            <input ref={inputRef} type="file" accept="audio/*,.mp3,.wav,.flac,.m4a,.ogg" onChange={(event) => chooseFile(event.target.files?.[0])} />
            <div className="upload-icon" aria-hidden="true">↥</div>
            {file ? <><strong>{file.name}</strong><span>{(file.size / 1024 / 1024).toFixed(1)} MB · AI 분석 준비 완료</span></> : <><strong>분석할 원곡을 놓아주세요</strong><span>MP3, WAV, FLAC, M4A · 최대 250MB</span></>}
          </div>
          <label className="lyrics-field">
            <span><b>가사 (선택)</b><em>{lyricsText.trim() ? `${lyricsText.trim().length}자 · 음절 강제 정렬` : '비우면 Whisper가 자동 받아쓰기'}</em></span>
            <textarea value={lyricsText} onChange={(event) => setLyricsText(event.target.value)} placeholder="정확한 가사를 줄 단위로 붙여 넣으면 노래방 악보의 음절 타이밍이 정확해집니다." maxLength={20000} disabled={working} />
          </label>
          {savedJobs.length > 0 && (
            <label className="saved-jobs">
              <span>또는 이전에 분석한 원곡 불러오기 → 바로 내 노래 채점</span>
              <select value="" disabled={working || loadingSaved} onChange={(event) => void loadSavedJob(event.target.value)}>
                <option value="">{loadingSaved ? '불러오는 중…' : `저장된 원곡 ${savedJobs.length}곡 중 선택`}</option>
                {savedJobs.map((item) => <option key={item.id} value={item.id}>{item.filename}{item.key ? ` · ${item.key}` : ''}{item.tempoBpm ? ` · ${item.tempoBpm} BPM` : ''}{item.hasKaraoke ? ' · 노래방 악보' : ''}</option>)}
              </select>
            </label>
          )}
          <button className="analyze-button" type="button" disabled={!file || working} onClick={startAnalysis}><span>{working ? 'AI 모델 처리 중…' : file ? '전체 AI 분석 시작' : '음원을 먼저 선택하세요'}</span><b aria-hidden="true">→</b></button>
          {phase !== 'idle' && <div className={`process-status ${phase}`} role="status"><div><span>{message}</span><b>{progress}%</b></div><i><em style={{ width: `${progress}%` }} /></i>{jobId && <small>LOCAL JOB {jobId.slice(0, 8).toUpperCase()}</small>}</div>}
          <p className="upload-note">모든 AI 연산은 이 PC에서 실행되며 음원이 외부 서버로 전송되지 않습니다</p>
        </div>
      </section>

      {result && (
        <section className="result-panel" ref={resultRef}>
          <div className="result-title"><div><p className="eyebrow">AI ANALYSIS COMPLETE</p><h2>{resultName || file?.name}</h2><p>{result.noteCount}개 음표 · {result.model} · {result.device.toUpperCase()}</p></div><div className="download-group">{result.karaokeNotes?.length ? <><a href={`${artifactUrl('karaoke-musicxml')}?v=${result.karaokeVersion ?? 1}`} download>노래방 MusicXML ↓</a><a href={`${artifactUrl('karaoke-midi')}?v=${result.karaokeVersion ?? 1}`} download>노래방 MIDI ↓</a></> : null}<a href={artifactUrl('musicxml')} download>원곡 MusicXML ↓</a><a href={artifactUrl('midi')} download>원곡 MIDI ↓</a></div></div>
          <div className="result-metrics">
            <article><span>LOWEST NOTE</span><strong>{result.lowest}</strong><small>감지된 최저음</small></article>
            <article><span>HIGHEST NOTE</span><strong>{result.highest}</strong><small>감지된 최고음</small></article>
            <article><span>CORE RANGE</span><strong>{result.coreRange}</strong><small>5–95% 주 사용 음역</small></article>
            <article><span>TEMPO</span><strong className="tempo-strong"><TempoGauge bpm={result.tempoBpm} />{result.tempoBpm}<em>BPM</em></strong><small>{result.timeSignature} 박자 · 음원에서 자동 추정</small></article>
            <article><span>CONFIDENCE</span><strong>{result.confidence}%</strong><small>F0 confidence 평균</small></article>
          </div>
          <RangeMap result={result} />
          {qualityFlag(result) && <div className="quality-flag" role="note"><b>분석 품질 낮음 · 전문 검수 권장</b><span>두 경로 F0 합의 {formatPercent(result.f0AgreementRatio)} · 옥타브 불일치율 {formatPercent(result.octaveMismatchRatio)} — 분리 트랙에 반주·코러스가 남았거나 이펙트가 강할 수 있습니다.</span></div>}
          {result.register && !result.register.error && result.register.ratios && (
            <div className="register-panel" aria-label="성구(진성·가성) 분석">
              <div className="register-head">
                <div><p className="eyebrow">REGISTER · 성구</p><h3>{result.register.bimodal ? `가성 우세 구간 ${(result.register.seconds?.falsetto ?? 0).toFixed(1)}초 검출 — 상위 음역의 가벼운 발성, 청취 검수 권장` : result.register.singleRegisterLabel === 'modal' ? '가성 우세 구간 미검출 — 진성 위주 가창' : '성구를 확정하기 어려운 가창'}</h3></div>
                <small>배음 구조(H1−H2 · 배음 풍부도 · 스펙트럼 기울기 · CPP)를 같은 음높이의 평소 발성과 비교 + 절대 기준(H1−H2 ≥ 8 dB, 상위 배음 ≤ H1) · 가성은 가창 음역 상위 40 %에서만 판정 · 확정이 아닌 우세/신뢰도 표현{result.register.breathyRatio ? ` · 숨소리 섞인 진성 ${formatPercent(result.register.breathyRatio)}` : ''}</small>
              </div>
              <div className="register-bar" role="img" aria-label="성구별 발성 시간 비율">
                {REGISTER_ORDER.map((label) => {
                  const ratio = result.register?.ratios?.[label] ?? 0;
                  return ratio > 0 ? <i key={label} style={{ width: `${ratio * 100}%`, background: REGISTER_COLORS[label] }} title={`${REGISTER_LABELS[label]} ${formatPercent(ratio)}`} /> : null;
                })}
              </div>
              <div className="register-metrics">
                {REGISTER_ORDER.map((label) => (
                  <article key={label}><span><b style={{ background: REGISTER_COLORS[label] }} />{REGISTER_LABELS[label].toUpperCase()}</span><strong>{formatPercent(result.register?.ratios?.[label] ?? 0)}</strong><small>{(result.register?.seconds?.[label] ?? 0).toFixed(1)}s</small></article>
                ))}
                <article><span>진성 우세 음역</span><strong>{result.register.modalRange ?? '—'}</strong><small>진성 우세로 판정된 음표의 최저–최고</small></article>
                <article><span>가성 우세 음역</span><strong>{result.register.falsettoRange ?? '—'}</strong><small>{result.register.bimodal ? '가성 우세 음표의 최저–최고' : '가성 우세 구간 미검출'}</small></article>
              </div>
              {result.register.lightnessHistogram && result.register.lightnessMean != null && (
                <div className="lightness-panel">
                  <div className="lightness-head"><span>발성 가벼움 분포 (0 밀도 있는 진성 · 50 두성/믹스 · 100 가벼운 가성)</span><b style={{ color: lightnessColor(result.register.lightnessMean) }}>평균 {result.register.lightnessMean}</b></div>
                  <div className="lightness-bars" role="img" aria-label="발성 가벼움 10구간별 발성 시간">
                    {(() => {
                      const max = Math.max(1e-6, ...(result.register?.lightnessHistogram ?? []));
                      return (result.register?.lightnessHistogram ?? []).map((seconds, index) => (
                        <i key={index} style={{ height: `${Math.max(2, (seconds / max) * 100)}%`, background: lightnessColor(index * 10 + 5) }} title={`${index * 10}–${index * 10 + 9}: ${seconds.toFixed(1)}s`} />
                      ));
                    })()}
                  </div>
                  <div className="lightness-scale" aria-hidden="true"><span>0 진성</span><span>50 두성·믹스</span><span>100 가성</span></div>
                </div>
              )}
              {result.register.caveats?.length ? <ul className="register-caveats">{result.register.caveats.map((caveat) => <li key={caveat}>{caveat}</li>)}</ul> : null}
              <p className="register-note">규칙 기반 1단계 판정입니다. 피아노롤에서 <b style={{ color: REGISTER_COLORS.falsetto }}>보라색</b>이 가성 우세, <b style={{ color: REGISTER_COLORS.mixed }}>황색</b>이 혼합 음표이며 청취 검수를 권장합니다.</p>
            </div>
          )}
          <div className="precision-strip" aria-label="분석 품질 지표">
            <article><span>FIRST VOCAL</span><strong>{formatTime(result.vocalStart)}</strong><small>보컬 활동 감지(VAD) 이전 구간 제외</small></article>
            <article><span>VOICED RATIO</span><strong>{formatPercent(result.voicedRatio)}</strong><small>유성 비율 · {result.voicedSeconds}s 유효 가창</small></article>
            <article><span>F0 AGREEMENT</span><strong>{formatPercent(result.f0AgreementRatio)}</strong><small>두 경로 F0 합의 (CREPE·pYIN)</small></article>
            <article><span>OCTAVE MISMATCH</span><strong>{formatPercent(result.octaveMismatchRatio)}</strong><small>옥타브 불일치율</small></article>
            <article><span>ESTIMATED KEY</span><strong>{result.key?.name ?? '—'}</strong><small>곡 원키 추정 · 조표 기준 표기</small></article>
            <article><span>PITCH OFFSET</span><strong>{result.tuningCents > 0 ? '+' : ''}{result.tuningCents}¢</strong><small>기준 피치 편차 (반올림 전 보정)</small></article>
            <article><span>PITCH TRACKER</span><strong>CREPE FULL</strong><small>세그먼트 Viterbi + pYIN 교차 검증</small></article>
            <article><span>SEPARATION</span><strong>{result.separation?.backend === 'melband' ? 'MEL-BAND ROFORMER' : result.separation?.backend === 'htdemucs' ? 'HTDEMUCS' : '생략'}</strong><small>{result.separation?.fallbackReason ? `요청 모델 실패 → HTDemucs 폴백` : result.separation?.seconds ? `보컬 분리 ${result.separation.seconds}s` : '보컬 파일 직접 분석'}</small></article>
            <article><span>RHYTHM GRID</span><strong>{result.timeSignature} · {result.tempoBpm}</strong><small>{result.tempoCandidates?.length ? `BPM 후보 ${result.tempoCandidates.join(' / ')} · 박자표 신뢰도 ${formatPercent(result.meterConfidence)}` : 'BPM과 첫 박자 자동 정렬'}</small></article>
          </div>
          <div className="stem-players">
            <article><div><span>VOCAL STEM</span><small>Demucs 분리 보컬</small></div><audio controls src={artifactUrl('vocals')} preload="metadata" /><a href={artifactUrl('vocals')} download>WAV ↓</a></article>
            {result.artifacts.instrumental && <KeyShiftPlayer modelApi={MODEL_API} jobId={jobId} keyInfo={result.key} />}
          </div>
          <ScoreSection key={jobId} result={result} jobId={jobId} onResult={setResult} />
          <LiveScoring key={`live-${jobId}`} modelApi={MODEL_API} jobId={jobId} reference={result} onPreciseJob={setLiveJob} />
          <PerformanceCompare key={`compare-${jobId}-${(liveJob as { id?: string } | null)?.id ?? 'none'}`} modelApi={MODEL_API} referenceJobId={jobId} referenceName={resultName || file?.name || '원곡'} reference={result} initialJob={liveJob as never} />
          <div className="range-breakdown"><div className="range-copy"><p className="eyebrow">RANGE DISTRIBUTION</p><h3>어느 높이에서 가장 오래 불렀나요?</h3><p>감지된 전체 발성 시간을 음역의 낮은·중간·높은 세 구간으로 나눈 비율입니다.</p></div><div className="ratio-list"><div><span><b>LOW</b><em>{result.ratios.low}%</em></span><i><b style={{ width: `${result.ratios.low}%` }} /></i></div><div><span><b>MIDDLE</b><em>{result.ratios.middle}%</em></span><i><b style={{ width: `${result.ratios.middle}%` }} /></i></div><div><span><b>HIGH</b><em>{result.ratios.high}%</em></span><i><b style={{ width: `${result.ratios.high}%` }} /></i></div></div></div>
          <p className="accuracy-note">※ 악보는 보컬 활동이 처음 감지된 {formatTime(result.vocalStart)}부터 생성했습니다. 자동 채보는 100% 일치를 보장하지 않으며, 코러스·더블링이 겹치거나 분리 트랙에 반주가 남으면 전문 검수가 필요할 수 있습니다.</p>
        </section>
      )}

      <section className="signal-panel" id="analysis" aria-label="분석 항목">
        <div className="signal-heading"><span className="section-number">01</span><div><p className="eyebrow">MODEL PIPELINE</p><h2>분리부터 악보까지, 실제 AI 모델로</h2></div></div>
        <div className="waveform" aria-hidden="true">{bars.map((height, index) => <i key={`${height}-${index}`} style={{ height: `${height}%` }} />)}</div>
        <div className="metrics"><article><span>SEPARATION</span><strong>Demucs</strong><small>보컬·반주 WAV</small></article><article><span>PITCH TRACKING</span><strong>CREPE</strong><small>F0 추적·pYIN 교차 검증</small></article><article><span>RANGE</span><strong>{result?.coreRange || '—'}</strong><small>주 사용 음역</small></article><article><span>DEVICE</span><strong>{engineInfo?.device?.toUpperCase() || '—'}</strong><small>로컬 연산 장치</small></article></div>
      </section>

      <section className="workflow" id="workflow"><p className="eyebrow">THREE MODEL STEPS, THEN SING & COMPARE</p><div className="workflow-grid"><article><b>01</b><h3>보컬 분리·보컬 활동 감지</h3><p>HTDemucs로 보컬과 반주를 나눈 뒤 녹음 레벨, 믹스 대비 에너지 비율, 주기성을 함께 검사해 노래가 시작되기 전 구간을 제외합니다.</p></article><article><b>02</b><h3>가창 기본주파수(F0) 추적</h3><p>CREPE Full의 세그먼트 Viterbi 추적과 pYIN 교차 검증으로 옥타브 오류와 비브라토를 정리한 단선율 F0를 계산하고 기준 피치 편차를 보정합니다.</p></article><article><b>03</b><h3>박자 정렬 악보화</h3><p>첫 박자와 BPM, 곡 원키를 추정하고 음표를 16분음표·셋잇단음표 격자에 맞춰 조표가 있는 MIDI와 MusicXML을 생성합니다.</p></article></div></section>
    </main>
  );
}
