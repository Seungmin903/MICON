'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { midiName } from './karaoke-editor';
import { shiftedKeyName } from './key-shift-player';

/** 원곡(분석 완료 job) vs 사용자가 부른 노래 비교 패널.
 *  부른 노래도 원곡과 같은 파이프라인(보컬 분리 → VAD → CREPE/pYIN)으로 분석한 뒤 DTW로 정렬해 음표별 음정·박자를 채점합니다. */

type PitchStatus = 'good' | 'sharp' | 'flat' | 'wrong' | 'missed' | 'unsung';
type TimingStatus = 'good' | 'early' | 'late' | 'off' | 'missed' | 'unsung';

export type ComparisonNote = {
  index: number;
  refStart: number;
  refEnd: number;
  midi: number;
  targetMidi?: number;
  name: string;
  lyric: string | null;
  beat?: number | null;
  covered: boolean;
  perfStart?: number;
  perfEnd?: number;
  sungMidi?: number;
  sungInKey?: number;
  sungName?: string;
  centsError: number | null;
  centsFromWritten?: number;
  octaveShift?: number;
  stabilityCents?: number | null;
  heldRatio?: number;
  onsetDeviation?: number;
  onsetRefined?: boolean;
  short?: boolean;
  pitchStatus: PitchStatus;
  timingStatus: TimingStatus;
  pitchScore?: number;
  timingScore?: number;
  weight?: number;
};

export type ComparisonMeasure = {
  measure: number; start: number; end: number; notes: number; pitch: number; timing: number; score: number;
  missed: number; sharp: number; flat: number; wrong: number; early: number; late: number; lyric: string;
};

export type ComparisonIssue = {
  start: number; end: number; perfStart?: number | null; noteIndices: number[]; lyric: string | null; notes: number; summary: string; severity: number;
};

export type Comparison = {
  referenceJobId: string;
  performanceJobId: string;
  options: { allowTranspose: boolean; octaveTolerant: boolean };
  transposeSemitones: number;
  transposition: { semitones: number; pitchClassShift: number; confidence: number; medianGap?: number };
  referenceKey?: string | null;
  performanceKey?: string | null;
  startOffsetSeconds: number;
  tempoRatio: number;
  coverage: { refStart: number; refEnd: number; perfStart: number; perfEnd: number; notesTotal: number; notesCovered: number; notesSung: number; notesMissed: number; sungRatio: number; coveredRatio: number };
  scores: { pitch: number; timing: number; overall: number; grade: string; stabilityCents: number | null };
  pitchStats: { good: number; sharp: number; flat: number; wrong: number; missed: number; meanCents: number | null; medianCents: number | null; octaveShiftedNotes: number; shortNotes: number };
  timingStats: { good: number; early: number; late: number; off: number; missed: number; meanDeviationMs: number | null; absDeviationMs: number | null };
  measures: ComparisonMeasure[];
  issues: ComparisonIssue[];
  notes: ComparisonNote[];
  curves: { hop: number; start: number; ref: (number | null)[]; user: (number | null)[]; perfTime: (number | null)[] };
  alignment: { method: string; hop: number; pathCost: number; matchedRatio?: number; voicedPairs?: number; reliable?: boolean; referenceFrames: string; noteSource: string; referenceNoteCount: number };
  thresholds: { pitchGoodCents: number; pitchWrongCents: number; timingGoodMs: number; timingOffMs: number; weights: { pitch: number; timing: number } };
};

type PerformanceJob = {
  id: string;
  status: 'queued' | 'processing' | 'complete' | 'failed';
  progress: number;
  message: string;
  error?: string | null;
  compareOptions?: { allowTranspose: boolean; octaveTolerant: boolean };
  result?: { comparison?: Comparison; separation?: { backend: string; seconds: number }; noteCount?: number; artifacts?: Record<string, string> } | null;
};

type Props = {
  modelApi: string;
  referenceJobId: string;
  referenceName: string;
  /** 라이브 채점에서 넘어온 완료된 정밀 채점 job (key 재마운트로 채택) */
  initialJob?: PerformanceJob | null;
  reference: {
    tempoBpm: number;
    timeSignature: string;
    vocalStart: number;
    key?: { name?: string; tonic?: number; mode?: string };
    beatGrid?: { beatTimes: number[]; offsetBeats: number };
  };
};

type Phase = 'idle' | 'uploading' | 'analyzing' | 'done' | 'error';

export const PITCH_COLORS: Record<PitchStatus, string> = {
  good: '#3f9d5a',
  sharp: '#d98b2b',
  flat: '#3f7fd9',
  wrong: '#c94a3d',
  missed: '#9aa19c',
  unsung: '#d8d4c9',
};
export const PITCH_LABELS: Record<PitchStatus, string> = {
  good: '정확',
  sharp: '높게',
  flat: '낮게',
  wrong: '다른 음',
  missed: '안 부름',
  unsung: '미포함',
};
const TIMING_LABELS: Record<TimingStatus, string> = { good: '정박', early: '빨리', late: '늦게', off: '박자 이탈', missed: '—', unsung: '—' };

const sleep = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const formatTime = (seconds: number) => {
  const tenths = Math.max(0, Math.round(seconds * 10));
  return `${Math.floor(tenths / 600)}:${String(Math.floor((tenths % 600) / 10)).padStart(2, '0')}.${tenths % 10}`;
};
const signed = (value: number, unit: string, digits = 0) => `${value > 0 ? '+' : value < 0 ? '−' : ''}${Math.abs(value).toFixed(digits)}${unit}`;

export function scoreColor(score: number): string {
  // 0 → red, 60 → amber, 100 → green (matches PITCH_COLORS.good)
  const stops: [number, [number, number, number]][] = [[0, [201, 74, 61]], [60, [217, 139, 43]], [100, [63, 157, 90]]];
  const clamped = Math.max(0, Math.min(100, score));
  for (let index = 0; index < stops.length - 1; index += 1) {
    const [from, fromColor] = stops[index];
    const [to, toColor] = stops[index + 1];
    if (clamped <= to) {
      const t = (clamped - from) / (to - from);
      const mix = fromColor.map((value, channel) => Math.round(value + (toColor[channel] - value) * t));
      return `rgb(${mix[0]},${mix[1]},${mix[2]})`;
    }
  }
  return 'rgb(63,157,90)';
}

const gradeMessage = (grade: string) => ({
  S: '원곡과 거의 같은 음정·박자입니다.',
  A: '아주 안정적인 가창이에요. 표시된 몇 구간만 다듬으면 완벽합니다.',
  B: '전반적으로 잘 맞지만 흔들린 구간이 있습니다. 아래 구간을 다시 들어보세요.',
  C: '음정이나 박자가 자주 어긋납니다. 붉게 표시된 마디를 집중 연습해 보세요.',
  D: '원곡 멜로디와 차이가 큽니다. 반주와 함께 천천히 따라 부르며 다듬어 보세요.',
  F: '정렬은 되었지만 대부분의 음이 어긋났습니다. 같은 곡을 부른 녹음인지 확인해 주세요.',
}[grade] ?? '');

// ----------------------------------------------------------------------------- pitch overlay chart

const ROW_PX = 14;          // pixels per semitone
const TOP_PAD = 26;         // measure numbers
const BOTTOM_PAD = 22;      // time axis
const KEYS_WIDTH = 46;
const MAX_CANVAS_WIDTH = 12000;

function PitchOverlay({ comparison, reference, perfAudio, onSeek }: { comparison: Comparison; reference: Props['reference']; perfAudio: React.RefObject<HTMLAudioElement | null>; onSeek: (perfSeconds: number) => void }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const keysRef = useRef<HTMLCanvasElement>(null);
  const [hover, setHover] = useState<{ x: number; y: number; note: ComparisonNote | null; time: number; viewport: number } | null>(null);
  const [playhead, setPlayhead] = useState<number | null>(null);

  const { curves, notes } = comparison;
  const duration = Math.max(1, curves.ref.length * curves.hop);
  const pxPerSecond = Math.min(60, MAX_CANVAS_WIDTH / duration);
  const width = Math.ceil(duration * pxPerSecond);
  const covered = useMemo(() => notes.filter((note) => note.covered), [notes]);
  const [lowMidi, highMidi] = useMemo(() => {
    const values: number[] = [];
    covered.forEach((note) => { values.push(note.targetMidi ?? note.midi); if (note.sungInKey != null) values.push(note.sungInKey); });
    curves.user.forEach((value) => { if (value != null) values.push(value); });
    curves.ref.forEach((value) => { if (value != null) values.push(value); });
    if (!values.length) return [55, 79];
    const sorted = values.slice().sort((a, b) => a - b);
    const low = Math.floor(sorted[Math.floor(sorted.length * 0.01)]) - 2;
    const high = Math.ceil(sorted[Math.floor(sorted.length * 0.99)]) + 2;
    return [Math.min(low, high - 12), high];
  }, [covered, curves]);
  const height = TOP_PAD + (highMidi - lowMidi + 1) * ROW_PX + BOTTOM_PAD;
  const timeToX = useCallback((seconds: number) => (seconds - curves.start) * pxPerSecond, [curves.start, pxPerSecond]);
  const midiToY = useCallback((midi: number) => TOP_PAD + (highMidi - midi + 0.5) * ROW_PX, [highMidi]);

  const measureLines = useMemo(() => {
    const grid = reference.beatGrid;
    const meter = Number(reference.timeSignature.split('/')[0]) || 4;
    if (!grid?.beatTimes?.length) return [] as { time: number; label: number }[];
    const lines: { time: number; label: number }[] = [];
    grid.beatTimes.forEach((time, index) => {
      const beat = index - grid.offsetBeats;
      if (beat >= 0 && Math.abs(beat % meter) < 1e-6 && time >= curves.start - 0.01 && time <= curves.start + duration) {
        lines.push({ time, label: Math.round(beat / meter) + 1 });
      }
    });
    return lines;
  }, [reference.beatGrid, reference.timeSignature, curves.start, duration]);

  // static drawing (once per comparison)
  useEffect(() => {
    const canvas = canvasRef.current;
    const keys = keysRef.current;
    if (!canvas || !keys) return;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = width * ratio; canvas.height = height * ratio;
    canvas.style.width = `${width}px`; canvas.style.height = `${height}px`;
    keys.width = KEYS_WIDTH * ratio; keys.height = height * ratio;
    keys.style.width = `${KEYS_WIDTH}px`; keys.style.height = `${height}px`;
    const context = canvas.getContext('2d');
    const keyContext = keys.getContext('2d');
    if (!context || !keyContext) return;
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    keyContext.setTransform(ratio, 0, 0, ratio, 0, 0);

    context.fillStyle = '#fbfaf5';
    context.fillRect(0, 0, width, height);
    keyContext.fillStyle = '#efede5';
    keyContext.fillRect(0, 0, KEYS_WIDTH, height);
    // semitone rows
    for (let midi = lowMidi; midi <= highMidi; midi += 1) {
      const y = midiToY(midi);
      const black = [1, 3, 6, 8, 10].includes(((midi % 12) + 12) % 12);
      context.fillStyle = black ? 'rgba(23,34,29,.045)' : 'transparent';
      if (black) context.fillRect(0, y - ROW_PX / 2, width, ROW_PX);
      context.strokeStyle = midi % 12 === 0 ? 'rgba(23,34,29,.25)' : 'rgba(23,34,29,.08)';
      context.lineWidth = 1;
      context.beginPath(); context.moveTo(0, y + ROW_PX / 2); context.lineTo(width, y + ROW_PX / 2); context.stroke();
      if (midi % 12 === 0 || midi % 12 === 7) {
        keyContext.fillStyle = midi % 12 === 0 ? '#17221d' : '#7a837d';
        keyContext.font = `${midi % 12 === 0 ? 'bold ' : ''}9px Arial`;
        keyContext.textBaseline = 'middle';
        keyContext.fillText(midiName(midi), 6, y);
      }
    }
    // measure lines + numbers
    context.font = '9px Arial';
    context.textBaseline = 'top';
    measureLines.forEach(({ time, label }) => {
      const x = timeToX(time);
      context.strokeStyle = 'rgba(23,34,29,.28)';
      context.beginPath(); context.moveTo(x, TOP_PAD - 6); context.lineTo(x, height - BOTTOM_PAD); context.stroke();
      context.fillStyle = '#69736c';
      context.fillText(String(label), x + 3, 4);
    });
    // time axis
    context.fillStyle = '#69736c';
    context.textBaseline = 'bottom';
    const step = pxPerSecond >= 40 ? 2 : pxPerSecond >= 20 ? 5 : 10;
    for (let second = Math.ceil(curves.start / step) * step; second <= curves.start + duration; second += step) {
      const x = timeToX(second);
      context.fillRect(x, height - BOTTOM_PAD, 1, 4);
      context.fillText(formatTime(second).slice(0, -2), x + 3, height - 4);
    }
    // reference notes coloured by judgement
    covered.forEach((note) => {
      const x0 = timeToX(note.refStart);
      const x1 = Math.max(x0 + 2, timeToX(note.refEnd) - 1);
      const y = midiToY(note.targetMidi ?? note.midi);
      context.fillStyle = PITCH_COLORS[note.pitchStatus];
      context.globalAlpha = note.pitchStatus === 'missed' ? 0.55 : 0.85;
      context.fillRect(x0, y - ROW_PX * 0.42, x1 - x0, ROW_PX * 0.84);
      context.globalAlpha = 1;
      if (note.pitchStatus === 'missed') {
        context.strokeStyle = '#5d6a63'; context.setLineDash([3, 3]);
        context.strokeRect(x0 + 0.5, y - ROW_PX * 0.42 + 0.5, x1 - x0 - 1, ROW_PX * 0.84 - 1);
        context.setLineDash([]);
      }
      // timing marker: triangle at the note start (◄ early, ► late)
      if (note.timingStatus === 'early' || note.timingStatus === 'late' || note.timingStatus === 'off') {
        const direction = (note.onsetDeviation ?? 0) > 0 ? 1 : -1;
        context.fillStyle = note.timingStatus === 'off' ? '#c94a3d' : '#17221d';
        context.beginPath();
        context.moveTo(x0, y - ROW_PX * 0.95);
        context.lineTo(x0 + direction * 6, y - ROW_PX * 0.72);
        context.lineTo(x0, y - ROW_PX * 0.5);
        context.closePath(); context.fill();
      }
      if (note.lyric && x1 - x0 >= 12) {
        context.fillStyle = '#17221d';
        context.font = '10px Arial';
        context.textBaseline = 'top';
        context.fillText(note.lyric, x0 + 1, y + ROW_PX * 0.5 + 2, x1 - x0);
      }
    });
    // not sung part of the song (outside the covered range) is simply not drawn — curves only cover the sung range.
    // user pitch curve
    context.strokeStyle = '#17221d';
    context.lineWidth = 2;
    context.lineJoin = 'round';
    let open = false;
    curves.user.forEach((value, index) => {
      if (value == null) { if (open) { context.stroke(); open = false; } return; }
      const x = timeToX(curves.start + index * curves.hop + curves.hop / 2);
      const y = midiToY(value);
      if (!open) { context.beginPath(); context.moveTo(x, y); open = true; } else context.lineTo(x, y);
    });
    if (open) context.stroke();
  }, [comparison, covered, curves, duration, height, highMidi, lowMidi, measureLines, midiToY, pxPerSecond, timeToX, width]);

  // playhead follows the user's recording; perfTime (per reference bin) maps recording seconds → reference seconds
  useEffect(() => {
    const audio = perfAudio.current;
    if (!audio) return;
    const handle = () => {
      const time = audio.currentTime;
      const values = curves.perfTime;
      let best = -1; let bestGap = Infinity;
      for (let index = 0; index < values.length; index += 1) {
        const value = values[index];
        if (value == null) continue;
        const gap = Math.abs(value - time);
        if (gap < bestGap) { bestGap = gap; best = index; }
      }
      if (best < 0 || bestGap > 1.5) { setPlayhead(null); return; }
      const refTime = curves.start + best * curves.hop;
      setPlayhead(refTime);
      const scroller = scrollRef.current;
      if (scroller && !audio.paused) {
        const x = timeToX(refTime) + KEYS_WIDTH;
        if (x < scroller.scrollLeft + KEYS_WIDTH + 20 || x > scroller.scrollLeft + scroller.clientWidth - 40) {
          scroller.scrollLeft = Math.max(0, x - scroller.clientWidth * 0.3);
        }
      }
    };
    audio.addEventListener('timeupdate', handle);
    audio.addEventListener('seeked', handle);
    return () => { audio.removeEventListener('timeupdate', handle); audio.removeEventListener('seeked', handle); };
  }, [curves, perfAudio, timeToX]);

  const noteAt = (seconds: number) => covered.find((note) => note.refStart - 0.03 <= seconds && seconds <= note.refEnd + 0.03) ?? null;
  const handleMove = (event: React.MouseEvent<HTMLCanvasElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const seconds = curves.start + x / pxPerSecond;
    const scroller = scrollRef.current;
    const box = scroller?.getBoundingClientRect();
    setHover({ x: event.clientX - (box?.left ?? 0), y: event.clientY - (box?.top ?? 0), note: noteAt(seconds), time: seconds, viewport: scroller?.clientWidth ?? 600 });
  };
  const handleClick = (event: React.MouseEvent<HTMLCanvasElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const seconds = curves.start + (event.clientX - rect.left) / pxPerSecond;
    const index = Math.max(0, Math.min(curves.perfTime.length - 1, Math.round((seconds - curves.start) / curves.hop)));
    let value = curves.perfTime[index];
    for (let offset = 1; value == null && offset < 40; offset += 1) value = curves.perfTime[index + offset] ?? curves.perfTime[index - offset] ?? null;
    if (value != null) onSeek(value);
  };

  return (
    <div className="compare-chart">
      <div className="compare-chart-scroll" ref={scrollRef} onMouseLeave={() => setHover(null)}>
        <canvas ref={keysRef} className="compare-keys" aria-hidden="true" />
        <div className="compare-canvas-wrap" style={{ width, height }}>
          <canvas ref={canvasRef} className="compare-canvas" onMouseMove={handleMove} onClick={handleClick} role="img" aria-label="원곡 음표(색 = 음정 판정)와 내 목소리 음높이 곡선(검은 선)" />
          {playhead != null && <i className="compare-playhead" style={{ left: timeToX(playhead) }} />}
        </div>
        {hover && (
          <div className="compare-tip" style={{ left: Math.min(hover.x + 12, hover.viewport - 220), top: Math.max(4, hover.y - 8) }}>
            <b>{formatTime(hover.time)}</b>
            {hover.note ? (
              <>
                <span>{hover.note.lyric ? `“${hover.note.lyric}” · ` : ''}{hover.note.name} {hover.note.sungName && hover.note.sungName !== hover.note.name ? `→ ${hover.note.sungName}` : ''}</span>
                <span style={{ color: PITCH_COLORS[hover.note.pitchStatus] }}>음정 {PITCH_LABELS[hover.note.pitchStatus]}{hover.note.centsError != null ? ` (${signed(hover.note.centsError, '¢')})` : ''}{hover.note.octaveShift ? ` · 옥타브 ${hover.note.octaveShift > 0 ? '위' : '아래'}로 부름` : ''}</span>
                <span>박자 {TIMING_LABELS[hover.note.timingStatus]}{hover.note.onsetDeviation != null ? ` (${signed(hover.note.onsetDeviation * 1000, ' ms')})` : ''}{hover.note.short ? ' · 짧게 끊음' : ''}</span>
              </>
            ) : <span>클릭하면 내 녹음의 이 지점을 재생합니다</span>}
          </div>
        )}
      </div>
      <div className="compare-legend">
        {(['good', 'sharp', 'flat', 'wrong', 'missed'] as PitchStatus[]).map((status) => <span key={status}><i style={{ background: PITCH_COLORS[status] }} />{PITCH_LABELS[status]}</span>)}
        <span><i className="line" />내 목소리 음높이</span>
        <span><i className="tri" />▲ 표시 = 박자 빨리(◄)·늦게(►)</span>
      </div>
    </div>
  );
}

// ----------------------------------------------------------------------------- panel

export function PerformanceCompare({ modelApi, referenceJobId, referenceName, reference, initialJob }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const perfAudioRef = useRef<HTMLAudioElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [sourceType, setSourceType] = useState<'mix' | 'vocal'>('mix');
  const [allowTranspose, setAllowTranspose] = useState(true);
  const [octaveTolerant, setOctaveTolerant] = useState(true);
  const [phase, setPhase] = useState<Phase>(initialJob?.result?.comparison ? 'done' : 'idle');
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState('');
  const [job, setJob] = useState<PerformanceJob | null>(initialJob?.result?.comparison ? initialJob : null);
  const [rescoring, setRescoring] = useState(false);
  const [rescoreError, setRescoreError] = useState('');

  const comparison = job?.result?.comparison ?? null;
  const working = phase === 'uploading' || phase === 'analyzing';

  const start = async () => {
    if (!file || working) return;
    try {
      setPhase('uploading'); setProgress(1); setMessage('부른 노래를 로컬 AI 엔진으로 전송하고 있습니다.');
      const form = new FormData();
      form.append('audio', file);
      form.append('sourceType', sourceType);
      form.append('allowTranspose', String(allowTranspose));
      form.append('octaveTolerant', String(octaveTolerant));
      const response = await fetch(`${modelApi}/jobs/${referenceJobId}/compare`, { method: 'POST', body: form });
      const payload = await response.json() as PerformanceJob & { detail?: string };
      if (!response.ok) throw new Error(payload.detail || '부른 노래를 전달하지 못했습니다.');
      setPhase('analyzing');
      for (;;) {
        await sleep(1400);
        const statusResponse = await fetch(`${modelApi}/jobs/${payload.id}`, { cache: 'no-store' });
        if (!statusResponse.ok) throw new Error('비교 상태를 확인하지 못했습니다.');
        const next = await statusResponse.json() as PerformanceJob;
        setProgress(next.progress); setMessage(next.message);
        if (next.status === 'failed') throw new Error(next.error || '비교에 실패했습니다.');
        if (next.status === 'complete' && next.result?.comparison) { setJob(next); setPhase('done'); break; }
      }
    } catch (error) {
      setPhase('error');
      setMessage(error instanceof Error ? error.message : '비교 중 오류가 발생했습니다.');
    }
  };

  const rescore = async (options: { allowTranspose: boolean; octaveTolerant: boolean }) => {
    if (!job) return;
    setRescoring(true); setRescoreError('');
    try {
      const response = await fetch(`${modelApi}/jobs/${job.id}/comparison`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(options) });
      const payload = await response.json() as PerformanceJob & { detail?: string };
      if (!response.ok || !payload.result?.comparison) throw new Error(payload.detail || '다시 채점하지 못했습니다.');
      setJob(payload);
    } catch (error) {
      setRescoreError(error instanceof Error ? error.message : '다시 채점하지 못했습니다.');
    } finally {
      setRescoring(false);
    }
  };

  /** 옵션 토글: 결과가 이미 있으면 오디오 재분석 없이 서버에서 다시 채점만 한다. */
  const changeOption = (key: 'allowTranspose' | 'octaveTolerant', value: boolean) => {
    const next = { allowTranspose: key === 'allowTranspose' ? value : allowTranspose, octaveTolerant: key === 'octaveTolerant' ? value : octaveTolerant };
    if (key === 'allowTranspose') setAllowTranspose(value); else setOctaveTolerant(value);
    if (comparison) void rescore(next);
  };

  const seek = (perfSeconds: number) => {
    const audio = perfAudioRef.current;
    if (!audio) return;
    audio.currentTime = Math.max(0, perfSeconds);
    void audio.play().catch(() => undefined);
  };

  const chooseFile = (next?: File) => { if (!next) return; setFile(next); setPhase('idle'); setJob(null); setMessage(''); };

  const transposeLabel = (() => {
    if (!comparison) return '';
    const semitones = comparison.transposeSemitones;
    if (semitones === 0) return '원키';
    const octaves = Math.trunc(semitones / 12);
    const rest = semitones - octaves * 12;
    const parts: string[] = [];
    if (octaves) parts.push(`${Math.abs(octaves)}옥타브 ${octaves > 0 ? '위' : '아래'}`);
    if (rest) parts.push(`${rest > 0 ? '+' : '−'}${Math.abs(rest)}키`);
    return parts.join(' ');
  })();
  const userKey = comparison ? shiftedKeyName(reference.key, comparison.transposeSemitones) : null;

  const tendencies: string[] = [];
  if (comparison) {
    const { pitchStats, timingStats, coverage, tempoRatio, alignment } = comparison;
    if (pitchStats.meanCents != null && Math.abs(pitchStats.meanCents) >= 15) tendencies.push(`전반적으로 원곡보다 평균 ${Math.abs(pitchStats.meanCents)}¢ ${pitchStats.meanCents < 0 ? '낮게(flat)' : '높게(sharp)'} 부르는 경향이 있습니다.`);
    else if (pitchStats.meanCents != null) tendencies.push('음정의 전체 평균은 원곡과 거의 같습니다(치우침 없음).');
    if (timingStats.meanDeviationMs != null && Math.abs(timingStats.meanDeviationMs) >= 60) tendencies.push(`박자는 평균 ${Math.abs(timingStats.meanDeviationMs)} ms ${timingStats.meanDeviationMs > 0 ? '늦게' : '빨리'} 들어가는 편입니다.`);
    if (Math.abs(tempoRatio - 1) >= 0.03) tendencies.push(`부른 속도가 원곡보다 약 ${Math.round(Math.abs(tempoRatio - 1) * 100)}% ${tempoRatio > 1 ? '느립니다' : '빠릅니다'} (무반주 녹음이면 자연스러운 차이).`);
    if (pitchStats.octaveShiftedNotes) tendencies.push(`${pitchStats.octaveShiftedNotes}개 음은 옥타브를 바꿔 불렀습니다 (옥타브 차이는 허용되어 감점하지 않았습니다).`);
    if (pitchStats.shortNotes) tendencies.push(`${pitchStats.shortNotes}개의 긴 음을 원곡보다 짧게 끊었습니다.`);
    if (coverage.coveredRatio < 0.9) tendencies.push(`원곡의 ${formatTime(coverage.refStart)}–${formatTime(coverage.refEnd)} 구간(전체 음표의 ${Math.round(coverage.coveredRatio * 100)}%)을 부른 것으로 정렬되었습니다. 점수는 부른 구간만으로 계산했습니다.`);
    if (alignment.reliable === false) tendencies.unshift('⚠ 원곡 멜로디와 일치하는 구간이 적어 정렬 신뢰도가 낮습니다. 다른 곡이거나 녹음에 목소리가 거의 없을 수 있어 점수가 의미 없을 수 있습니다.');
  }

  return (
    <div className="compare-card" aria-label="부른 노래 비교">
      <div className="score-head">
        <div><span>SING & COMPARE · 부른 노래 채점</span><small>「{referenceName}」 원곡 멜로디 기준 · 부른 노래도 같은 파이프라인(보컬 분리 → VAD → CREPE·pYIN)으로 분석 후 DTW 정렬</small></div>
        <span aria-hidden="true">🎤</span>
      </div>

      <div className="compare-upload">
        <div className="compare-options">
          <div className="source-switch light" role="radiogroup" aria-label="녹음 종류">
            <button type="button" className={sourceType === 'mix' ? 'active' : ''} role="radio" aria-checked={sourceType === 'mix'} onClick={() => setSourceType('mix')} disabled={working}><b>반주와 함께 녹음 · 권장</b><small>노래방·MR 위에 부른 파일 → 보컬 분리 후 평가</small></button>
            <button type="button" className={sourceType === 'vocal' ? 'active' : ''} role="radio" aria-checked={sourceType === 'vocal'} onClick={() => setSourceType('vocal')} disabled={working}><b>목소리만 녹음</b><small>무반주 녹음 → 분리 생략</small></button>
          </div>
          <label className="compare-check"><input type="checkbox" checked={allowTranspose} onChange={(event) => changeOption('allowTranspose', event.target.checked)} disabled={working || rescoring} /><span><b>키 차이 자동 보정</b><small>노래방에서 키를 올리거나 내려 부른 경우 자동으로 맞춰 비교</small></span></label>
          <label className="compare-check"><input type="checkbox" checked={octaveTolerant} onChange={(event) => changeOption('octaveTolerant', event.target.checked)} disabled={working || rescoring} /><span><b>옥타브 차이 허용</b><small>여성곡을 한 옥타브 낮춰 부르는 등 옥타브만 다른 음은 정확으로 처리</small></span></label>
        </div>
        <div
          className={`drop-zone light ${dragging ? 'is-dragging' : ''} ${file ? 'has-file' : ''}`}
          onDragOver={(event) => { event.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFile(event.dataTransfer.files[0]); }}
          onClick={() => inputRef.current?.click()}
          role="button" tabIndex={0}
          onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') inputRef.current?.click(); }}
        >
          <input ref={inputRef} type="file" accept="audio/*,.mp3,.wav,.flac,.m4a,.ogg" onChange={(event) => chooseFile(event.target.files?.[0])} />
          <div className="upload-icon" aria-hidden="true">🎙</div>
          {file ? <><strong>{file.name}</strong><span>{(file.size / 1024 / 1024).toFixed(1)} MB · 비교 준비 완료</span></> : <><strong>내가 부른 녹음 파일을 놓아주세요</strong><span>MP3, WAV, M4A · 일부 구간만 불러도 됩니다</span></>}
          <button type="button" className="compare-start" disabled={!file || working} onClick={(event) => { event.stopPropagation(); void start(); }}>{working ? '분석 · 채점 중…' : '내 노래 채점하기 →'}</button>
        </div>
      </div>
      {phase !== 'idle' && phase !== 'done' && (
        <div className={`process-status light ${phase}`} role="status"><div><span>{message}</span><b>{progress}%</b></div><i><em style={{ width: `${progress}%` }} /></i></div>
      )}

      {comparison && job && (
        <div className="compare-result">
          <div className="compare-score-row">
            <div className="compare-overall" style={{ borderColor: scoreColor(comparison.scores.overall) }}>
              <span>TOTAL SCORE</span>
              <strong style={{ color: scoreColor(comparison.scores.overall) }}>{Math.round(comparison.scores.overall)}</strong>
              <b>{comparison.scores.grade}</b>
              <small>{gradeMessage(comparison.scores.grade)}</small>
            </div>
            <div className="compare-metrics">
              <article><span>PITCH · 음정</span><strong style={{ color: scoreColor(comparison.scores.pitch) }}>{Math.round(comparison.scores.pitch)}</strong><small>정확 {comparison.pitchStats.good} · 높게 {comparison.pitchStats.sharp} · 낮게 {comparison.pitchStats.flat} · 다른 음 {comparison.pitchStats.wrong}</small></article>
              <article><span>TIMING · 박자</span><strong style={{ color: scoreColor(comparison.scores.timing) }}>{Math.round(comparison.scores.timing)}</strong><small>정박 {comparison.timingStats.good} · 빨리 {comparison.timingStats.early} · 늦게 {comparison.timingStats.late} · 이탈 {comparison.timingStats.off}</small></article>
              <article><span>SUNG NOTES · 소화</span><strong>{comparison.coverage.notesSung}<em>/{comparison.coverage.notesCovered}</em></strong><small>부른 구간 안에서 실제로 낸 음 · 안 부른 음 {comparison.coverage.notesMissed}개</small></article>
              <article><span>STABILITY · 안정도</span><strong>{comparison.scores.stabilityCents != null ? `${Math.round(comparison.scores.stabilityCents)}¢` : '—'}</strong><small>음 안에서의 음높이 흔들림(표준편차) · 낮을수록 안정</small></article>
              <article><span>KEY · 키</span><strong>{transposeLabel}</strong><small>{comparison.transposeSemitones ? `${reference.key?.name ?? '원곡'} → ${userKey ?? '?'} 로 부름 · 보정 후 비교` : '원곡과 같은 키로 불렀습니다'}</small></article>
              <article><span>RANGE SUNG · 구간</span><strong>{formatTime(comparison.coverage.refStart)}–{formatTime(comparison.coverage.refEnd)}</strong><small>원곡 기준 · 녹음의 {formatTime(comparison.coverage.perfStart)}부터 정렬{Math.abs(comparison.tempoRatio - 1) >= 0.01 ? ` · 속도 ${(comparison.tempoRatio * 100).toFixed(0)}%` : ''}</small></article>
            </div>
          </div>
          {(rescoring || rescoreError) && <div className="quality-flag light" role="status"><b>{rescoring ? '옵션을 바꿔 다시 채점하고 있습니다…' : rescoreError}</b></div>}
          {tendencies.length > 0 && <ul className="compare-tendency">{tendencies.map((text) => <li key={text}>{text}</li>)}</ul>}

          <div className="compare-players">
            <article><div><span>MY VOICE</span><small>내 녹음에서 분리한 목소리 · 재생하면 차트에 위치 표시</small></div><audio ref={perfAudioRef} controls src={`${modelApi}/jobs/${job.id}/files/vocals`} preload="metadata" /></article>
            <article><div><span>ORIGINAL VOCAL</span><small>원곡 분리 보컬 · 비교 기준</small></div><audio controls src={`${modelApi}/jobs/${referenceJobId}/files/vocals`} preload="metadata" /></article>
          </div>

          <div className="compare-section-head"><span>PITCH OVERLAY · 어디서 틀렸나</span><small>색 막대 = 원곡 음표(색은 내 음정 판정), 검은 선 = 내 목소리 음높이(키 보정 후). 막대 위 ▲는 박자 어긋남. 차트를 클릭하면 내 녹음의 그 지점을 재생합니다.</small></div>
          <PitchOverlay comparison={comparison} reference={reference} perfAudio={perfAudioRef} onSeek={seek} />

          <div className="compare-section-head"><span>BY MEASURE · 마디별 점수</span><small>마디 번호는 원곡 채보 악보와 같습니다. 클릭하면 해당 마디로 이동해 재생합니다.</small></div>
          <div className="compare-measures" role="list">
            {comparison.measures.map((measure) => {
              const note = comparison.notes.find((item) => item.covered && item.refStart >= measure.start - 0.01 && item.perfStart != null);
              return (
                <button type="button" role="listitem" key={measure.measure} className="compare-measure" style={{ background: scoreColor(measure.score) }} title={`${measure.measure}마디 ${formatTime(measure.start)} · 음정 ${measure.pitch} · 박자 ${measure.timing}${measure.lyric ? ` · “${measure.lyric}”` : ''}${measure.missed ? ` · 안 부른 음 ${measure.missed}` : ''}`} onClick={() => { if (note?.perfStart != null) seek(note.perfStart); }}>
                  <b>{measure.measure}</b><i>{measure.score}</i>
                </button>
              );
            })}
          </div>

          {comparison.issues.length > 0 && (
            <>
              <div className="compare-section-head"><span>NEEDS WORK · 다시 들어볼 구간</span><small>어긋난 음이 이어진 구간을 심한 순서로 정리했습니다. ▶ 를 누르면 내 녹음에서 그 부분이 재생됩니다.</small></div>
              <ol className="compare-issues">
                {comparison.issues.map((issue) => (
                  <li key={`${issue.start}-${issue.end}`}>
                    <button type="button" onClick={() => { if (issue.perfStart != null) seek(Math.max(0, issue.perfStart - 0.5)); }} aria-label="이 구간 재생">▶</button>
                    <div>
                      <b>{formatTime(issue.start)}–{formatTime(issue.end)}{issue.lyric ? <em> “{issue.lyric}”</em> : null}</b>
                      <span>{issue.summary} · {issue.notes}음</span>
                    </div>
                  </li>
                ))}
              </ol>
            </>
          )}

          <p className="compare-note">
            판정 기준: 원가수가 실제로 낸 음높이 대비 ±{comparison.thresholds.pitchGoodCents}¢ 이내면 정확, {comparison.thresholds.pitchWrongCents}¢ 초과는 다른 음 · 박자는 ±{comparison.thresholds.timingGoodMs} ms 이내 정박, {comparison.thresholds.timingOffMs} ms 초과는 이탈.
            점수 = 음정 {Math.round(comparison.thresholds.weights.pitch * 100)}% + 박자 {Math.round(comparison.thresholds.weights.timing * 100)}% (음 길이 가중, 안 부른 음은 0점). 정렬: {comparison.alignment.method} · 일치 프레임 {Math.round((comparison.alignment.matchedRatio ?? 0) * 100)}%
            {job.result?.separation?.backend && job.result.separation.backend !== 'none' ? ` · 내 녹음 보컬 분리 ${job.result.separation.backend} ${job.result.separation.seconds}s` : ''}. 자동 채점은 참고용이며, 특히 랩·말하듯 부르는 구간이나 애드리브는 원곡과 달라도 감점될 수 있습니다.
          </p>
        </div>
      )}
    </div>
  );
}
