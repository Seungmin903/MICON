'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { midiName } from './karaoke-editor';
import { PITCH_COLORS, PITCH_LABELS, scoreColor } from './performance-compare';
import { KEY_RANGE, shiftedKeyName } from './key-shift-player';

/** 라이브 채점 (퍼펙트 스코어식): 브라우저가 반주(MR)를 재생하고 마이크 입력을 AudioWorklet(YIN)으로
 *  20 ms마다 추적한다. 재생 위치를 알기 때문에 DTW·보컬 분리 없이 음표가 끝나는 즉시 채점되고,
 *  종료 후 녹음본(webm)을 기존 /compare 파이프라인에 올려 정밀 리포트를 만들 수 있다.
 *  판정 기준·점수식은 compare_engine.py와 동일한 상수를 사용한다. */

// --- compare_engine.py와 동일한 판정 상수 ---
const PITCH_GOOD_CENTS = 45;
const PITCH_WRONG_CENTS = 150;
const PITCH_FULL_CENTS = 30;
const PITCH_ZERO_CENTS = 150;
const TIMING_GOOD = 0.12;
const TIMING_FULL = 0.06;
const TIMING_ZERO = 0.4;
const WEIGHT_PITCH = 0.65;
const WEIGHT_TIMING = 0.35;
const MISSED_VOICED_RATIO = 0.35;
const GRADES: [number, string][] = [[95, 'S'], [88, 'A'], [78, 'B'], [65, 'C'], [50, 'D']];
// --- 라이브 전용 ---
const CLARITY_MIN = 0.6;          // YIN 명료도(주기성) 게이트
const RMS_MIN = 0.004;            // ≈ −48 dBFS
const ONSET_NEAR_SEMITONES = 0.6;
const ONSET_SEARCH = 0.3;         // 음표 시작 ± 탐색(초)
const CALIBRATION_LIMIT = 0.4;    // 자동 지연 보정 한계(초)
const RUNWAY_SECONDS_AHEAD = 5;   // 러너웨이 오른쪽(다가올) 구간
const RUNWAY_SECONDS_BEHIND = 3;
const LYRIC_STRIP = 44;           // 캔버스 하단 가사 스트립 높이(px): 현재 마디 + 다음 마디 미리보기

const fold = (delta: number) => delta - 12 * Math.round(delta / 12);
const median = (values: number[]) => {
  if (!values.length) return 0;
  const sorted = values.slice().sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};
const gradeOf = (score: number) => GRADES.find(([threshold]) => score >= threshold)?.[1] ?? 'F';
const formatTime = (seconds: number) => {
  const total = Math.max(0, Math.round(seconds));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
};

type RefNoteInput = { midi: number; lyric?: string | null; scoreStartBeat?: number | null; sourceStart?: number; sourceEnd?: number; start?: number; end?: number };
type LiveNote = {
  index: number;
  start: number;
  end: number;
  midi: number;        // 원곡 기준 (키 이동 전)
  lyric: string | null;
  measure: number;
  weight: number;
  status: 'upcoming' | 'active' | 'good' | 'sharp' | 'flat' | 'wrong' | 'missed';
  cents?: number;
  deviation?: number;  // 초, +늦음
  pitchScore?: number;
  timingScore?: number;
};
type Frame = { t: number; midi: number | null }; // t = MR(원곡) 초
type Totals = { pitch: number; timing: number; overall: number; judged: number; good: number; sharp: number; flat: number; wrong: number; missed: number; early: number; late: number; centsSum: number; devSum: number; sung: number; calibration: number };
type MeasureRow = { measure: number; score: number; pitch: number; timing: number; notes: number };

type Phase = 'idle' | 'mic' | 'countdown' | 'running' | 'finished';
type ReportPhase = 'none' | 'uploading' | 'analyzing' | 'done' | 'error';

type Props = {
  modelApi: string;
  jobId: string;
  reference: {
    tempoBpm: number;
    timeSignature: string;
    vocalStart: number;
    key?: { name?: string; tonic?: number; mode?: string };
    beatGrid?: { beatTimes: number[]; offsetBeats: number };
    notes?: RefNoteInput[];
    karaokeNotes?: RefNoteInput[];
    artifacts?: Record<string, string>;
    lyrics?: { text?: string | null } | null;
  };
  /** 정밀 리포트(job)가 완성되면 페이지로 전달 → SING & COMPARE 카드에 표시 */
  onPreciseJob?: (job: unknown) => void;
};

/** 음표별 라이브 판정. UI와 분리된 순수 로직 (프레임은 MR 시간축). */
class LiveScorer {
  notes: LiveNote[];
  frames: Frame[] = [];
  private rawDeviations: number[] = [];
  calibration = 0;
  autoCalibrate = true;
  keyShift = 0;
  private cursor = 0;

  constructor(notes: LiveNote[]) {
    this.notes = notes;
  }

  addFrame(frame: Frame) {
    this.frames.push(frame);
    if (this.frames.length > 3000) this.frames.splice(0, 1000); // 최근 ~60 s만 유지
  }

  /** now(MR 초)까지 끝난 음표를 확정. 확정된 음표 목록을 돌려준다. */
  tick(now: number): LiveNote[] {
    const done: LiveNote[] = [];
    while (this.cursor < this.notes.length) {
      const note = this.notes[this.cursor];
      if (note.start - 0.05 <= now && note.end + 0.2 > now && note.status === 'upcoming') note.status = 'active';
      if (note.end + this.calibration + 0.25 > now) break;
      this.judge(note);
      done.push(note);
      this.cursor++;
    }
    return done;
  }

  private framesBetween(from: number, to: number): Frame[] {
    const result: Frame[] = [];
    for (let i = this.frames.length - 1; i >= 0; i--) {
      const frame = this.frames[i];
      if (frame.t < from) break;
      if (frame.t <= to) result.push(frame);
    }
    return result.reverse();
  }

  private judge(note: LiveNote) {
    const target = note.midi + this.keyShift;
    const start = note.start + this.calibration;
    const end = note.end + this.calibration;
    const window = this.framesBetween(start, end);
    const voiced = window.filter((frame) => frame.midi != null);
    const expected = Math.max(1, Math.round((end - start) / 0.02));
    if (voiced.length < 3 || voiced.length / expected < MISSED_VOICED_RATIO) {
      note.status = 'missed';
      note.pitchScore = 0;
      note.timingScore = 0;
      return;
    }
    const skip = Math.floor(voiced.length * 0.15); // 어택 제외 (엔진과 동일)
    const body = voiced.length - skip >= 3 ? voiced.slice(skip) : voiced;
    const errors = body.map((frame) => fold((frame.midi as number) - target));
    const cents = median(errors) * 100;
    note.cents = Math.round(cents);
    note.status = Math.abs(cents) <= PITCH_GOOD_CENTS ? 'good' : Math.abs(cents) > PITCH_WRONG_CENTS ? 'wrong' : cents > 0 ? 'sharp' : 'flat';
    note.pitchScore = Math.max(0, Math.min(1, 1 - Math.max(0, Math.abs(cents) - PITCH_FULL_CENTS) / (PITCH_ZERO_CENTS - PITCH_FULL_CENTS)));

    // 온셋: 시작 ±0.3 s에서 목표 ±0.6 st에 3프레임 연속 진입한 첫 시각
    const search = this.framesBetween(start - ONSET_SEARCH, start + ONSET_SEARCH + 0.06);
    let onset: number | null = null;
    for (let i = 0; i + 2 < search.length; i++) {
      const near = (frame: Frame) => frame.midi != null && Math.abs(fold(frame.midi - target)) <= ONSET_NEAR_SEMITONES;
      if (near(search[i]) && near(search[i + 1]) && near(search[i + 2])) {
        onset = search[i].t;
        break;
      }
    }
    if (onset != null) {
      const raw = onset - (note.start + this.calibration);
      this.rawDeviations.push(raw + this.calibration); // 보정 전 기준으로 축적
      if (this.rawDeviations.length > 40) this.rawDeviations.shift();
      if (this.autoCalibrate && this.rawDeviations.length >= 6) {
        const value = median(this.rawDeviations);
        this.calibration = Math.max(-CALIBRATION_LIMIT, Math.min(CALIBRATION_LIMIT, value));
      }
      note.deviation = raw;
      note.timingScore = Math.max(0, Math.min(1, 1 - Math.max(0, Math.abs(raw) - TIMING_FULL) / (TIMING_ZERO - TIMING_FULL)));
    } else {
      note.deviation = undefined;
      note.timingScore = 0.5; // 온셋 불명(레가토 연속) — 중립
    }
  }

  totals(): Totals {
    let weight = 0;
    let pitch = 0;
    let timing = 0;
    const totals: Totals = { pitch: 0, timing: 0, overall: 0, judged: 0, good: 0, sharp: 0, flat: 0, wrong: 0, missed: 0, early: 0, late: 0, centsSum: 0, devSum: 0, sung: 0, calibration: this.calibration };
    for (const note of this.notes) {
      if (note.pitchScore === undefined) continue;
      totals.judged++;
      weight += note.weight;
      pitch += note.pitchScore * note.weight;
      timing += (note.timingScore ?? 0) * note.weight;
      if (note.status === 'missed') totals.missed++;
      else {
        totals.sung++;
        totals[note.status as 'good' | 'sharp' | 'flat' | 'wrong']++;
        totals.centsSum += note.cents ?? 0;
        if (note.deviation != null) {
          totals.devSum += note.deviation;
          if (note.deviation < -TIMING_GOOD) totals.early++;
          else if (note.deviation > TIMING_GOOD) totals.late++;
        }
      }
    }
    if (weight > 0) {
      totals.pitch = (pitch / weight) * 100;
      totals.timing = (timing / weight) * 100;
      totals.overall = WEIGHT_PITCH * totals.pitch + WEIGHT_TIMING * totals.timing;
    }
    return totals;
  }

  measures(): MeasureRow[] {
    const buckets = new Map<number, { pitch: number; timing: number; weight: number; notes: number }>();
    for (const note of this.notes) {
      if (note.pitchScore === undefined) continue;
      const bucket = buckets.get(note.measure) ?? { pitch: 0, timing: 0, weight: 0, notes: 0 };
      bucket.pitch += note.pitchScore * note.weight;
      bucket.timing += (note.timingScore ?? 0) * note.weight;
      bucket.weight += note.weight;
      bucket.notes++;
      buckets.set(note.measure, bucket);
    }
    return [...buckets.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([measure, bucket]) => {
        const pitch = Math.round((bucket.pitch / bucket.weight) * 100);
        const timing = Math.round((bucket.timing / bucket.weight) * 100);
        return { measure: measure + 1, pitch, timing, notes: bucket.notes, score: Math.round(WEIGHT_PITCH * pitch + WEIGHT_TIMING * timing) };
      });
  }
}

export function LiveScoring({ modelApi, jobId, reference, onPreciseJob }: Props) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [semitones, setSemitones] = useState(0);
  const [keyBusy, setKeyBusy] = useState(false);
  const [skipIntro, setSkipIntro] = useState(true);
  const [autoCalibrate, setAutoCalibrate] = useState(true);
  const [micLevel, setMicLevel] = useState<{ rms: number; name: string | null }>({ rms: 0, name: null });
  const [totals, setTotals] = useState<Totals | null>(null);
  const [measures, setMeasures] = useState<MeasureRow[]>([]);
  const [lastJudged, setLastJudged] = useState<LiveNote | null>(null);
  const [report, setReport] = useState<{ phase: ReportPhase; message: string; progress: number }>({ phase: 'none', message: '', progress: 0 });

  const audioRef = useRef<HTMLAudioElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const contextRef = useRef<AudioContext | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const recordingRef = useRef<Blob | null>(null);
  const scorerRef = useRef<LiveScorer | null>(null);
  const rafRef = useRef<number>(0);
  // 마이크 캡처 시간(t) → MR 시간 매핑: mr = t - syncT + syncMr (2초마다 재동기화)
  const syncRef = useRef<{ t: number; mr: number; latestT: number } | null>(null);
  const phaseRef = useRef<Phase>('idle');
  phaseRef.current = phase;

  const refNotes = useMemo<LiveNote[]>(() => {
    const source = (reference.karaokeNotes?.length ?? 0) >= 10 ? reference.karaokeNotes! : reference.notes ?? [];
    const meter = Number(reference.timeSignature.split('/')[0]) || 4;
    const startOf = (note: RefNoteInput) => note.sourceStart ?? note.start ?? 0;
    const endOf = (note: RefNoteInput) => note.sourceEnd ?? note.end ?? startOf(note);
    return source
      .slice()
      .sort((a, b) => startOf(a) - startOf(b))
      .map((note, index) => ({
        index,
        start: startOf(note),
        end: endOf(note),
        midi: note.midi,
        lyric: note.lyric ?? null,
        measure: note.scoreStartBeat != null ? Math.floor(note.scoreStartBeat / meter) : Math.floor(startOf(note) / 2),
        weight: Math.max(0.1, Math.min(1.5, endOf(note) - startOf(note))),
        status: 'upcoming' as const,
      }));
  }, [reference]);

  const hasInstrumental = Boolean(reference.artifacts?.instrumental);
  const instrumentalUrl = (value: number) => `${modelApi}/jobs/${jobId}/files/instrumental${value ? `?semitones=${value}` : ''}`;

  const changeKey = async (next: number) => {
    const target = Math.max(-KEY_RANGE, Math.min(KEY_RANGE, next));
    if (target === semitones || keyBusy || phase === 'running' || phase === 'countdown') return;
    setKeyBusy(true);
    setError('');
    try {
      if (target !== 0) {
        const response = await fetch(`${modelApi}/jobs/${jobId}/instrumental-key`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ semitones: target }) });
        if (!response.ok) throw new Error(((await response.json().catch(() => ({}))) as { detail?: string }).detail || '키 변경에 실패했습니다.');
      }
      setSemitones(target);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '키 변경에 실패했습니다.');
    } finally {
      setKeyBusy(false);
    }
  };

  const enableMic = async () => {
    setError('');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
      });
      const context = new AudioContext();
      await context.audioWorklet.addModule('/pitch-worklet.js');
      const sourceNode = context.createMediaStreamSource(stream);
      const worklet = new AudioWorkletNode(context, 'vocalscope-pitch', { numberOfOutputs: 0 });
      sourceNode.connect(worklet);
      worklet.port.onmessage = (event) => {
        const { t, f0, clarity, rms, rmsLow } = event.data as { t: number; f0: number; clarity: number; rms: number; rmsLow?: number };
        // rmsLow(창 앞/뒤 절반 중 작은 RMS): 소리가 꺼지는 경계 프레임이 유성으로 새는 것을 차단
        const voiced = (rmsLow ?? rms) >= RMS_MIN && clarity >= CLARITY_MIN && f0 > 0;
        const midi = voiced ? 69 + 12 * Math.log2(f0 / 440) : null;
        if (syncRef.current) {
          syncRef.current.latestT = t;
          if (phaseRef.current === 'running' && scorerRef.current) {
            scorerRef.current.addFrame({ t: t - syncRef.current.t + syncRef.current.mr, midi });
          }
        } else {
          syncRef.current = { t, mr: 0, latestT: t };
        }
        lastFrameRef.current = { midi, rms, clarity };
      };
      contextRef.current = context;
      streamRef.current = stream;
      setPhase('mic');
    } catch (caught) {
      setError(caught instanceof Error ? `마이크를 사용할 수 없습니다: ${caught.message}` : '마이크 권한이 필요합니다.');
    }
  };

  const lastFrameRef = useRef<{ midi: number | null; rms: number; clarity: number }>({ midi: null, rms: 0, clarity: 0 });

  // 마이크 켠 뒤 대기 상태에서 레벨/음이름 미리보기
  useEffect(() => {
    if (phase !== 'mic') return;
    const timer = setInterval(() => {
      const frame = lastFrameRef.current;
      setMicLevel({ rms: frame.rms, name: frame.midi != null ? midiName(Math.round(frame.midi)) : null });
    }, 200);
    return () => clearInterval(timer);
  }, [phase]);

  const start = async () => {
    const audio = audioRef.current;
    if (!audio || phase !== 'mic') return;
    setError('');
    const scorer = new LiveScorer(refNotes.map((note) => ({ ...note, status: 'upcoming' as const })));
    scorer.keyShift = semitones;
    scorer.autoCalibrate = autoCalibrate;
    scorerRef.current = scorer;
    setTotals(null);
    setMeasures([]);
    setLastJudged(null);
    setReport({ phase: 'none', message: '', progress: 0 });
    recordingRef.current = null;
    chunksRef.current = [];
    if (streamRef.current) {
      try {
        const recorder = new MediaRecorder(streamRef.current, { mimeType: MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm' });
        recorder.ondataavailable = (event) => { if (event.data.size) chunksRef.current.push(event.data); };
        recorder.onstop = () => { recordingRef.current = new Blob(chunksRef.current, { type: 'audio/webm' }); };
        recorderRef.current = recorder;
      } catch {
        recorderRef.current = null; // 녹음 불가여도 라이브 채점은 진행
      }
    }
    const firstNote = refNotes[0]?.start ?? reference.vocalStart;
    audio.currentTime = skipIntro ? Math.max(0, firstNote - 4) : 0;
    setPhase('countdown');
    for (let step = 3; step >= 1; step--) {
      setCountdown(step);
      await new Promise((resolve) => setTimeout(resolve, 800));
    }
    setCountdown(0);
    try {
      await contextRef.current?.resume();
      await audio.play();
    } catch {
      setPhase('mic');
      setError('반주를 재생하지 못했습니다. 다시 시도해주세요.');
      return;
    }
    recorderRef.current?.start(1000);
    setPhase('running');
  };

  const stop = useCallback((finished: boolean) => {
    audioRef.current?.pause();
    recorderRef.current?.stop();
    setPhase(finished || (scorerRef.current?.totals().judged ?? 0) > 0 ? 'finished' : 'mic');
    if (scorerRef.current) {
      setTotals(scorerRef.current.totals());
      setMeasures(scorerRef.current.measures());
    }
  }, []);

  // 러너웨이 렌더링 + 채점 tick (실행 중 rAF)
  useEffect(() => {
    if (phase !== 'running') return;
    const audio = audioRef.current;
    const canvas = canvasRef.current;
    const scorer = scorerRef.current;
    if (!audio || !canvas || !scorer) return;
    const midis = scorer.notes.map((note) => note.midi + semitones);
    const low = Math.min(...midis) - 3;
    const high = Math.max(...midis) + 3;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    let judgedCount = 0;
    // 가사 스트립용: 원 가사 텍스트의 줄바꿈·띄어쓰기를 음절에 복원해 노래방처럼 줄 단위로 묶는다.
    // 텍스트가 없으면(휘스퍼 미사용 등) 음절 사이 시간 간격으로 근사한다.
    type LyricToken = { note: LiveNote; sep: '' | ' ' };
    const lyricLines: { tokens: LyricToken[]; start: number; end: number }[] = [];
    {
      const syllables = scorer.notes.filter((note) => note.lyric);
      const text = reference.lyrics?.text ?? '';
      let pos = 0;
      let line: LyricToken[] = [];
      const pushLine = () => {
        if (line.length) lyricLines.push({ tokens: line, start: line[0].note.start, end: line[line.length - 1].note.end });
        line = [];
      };
      for (let i = 0; i < syllables.length; i++) {
        const note = syllables[i];
        const gap = i > 0 ? note.start - syllables[i - 1].end : 0;
        let sep: '' | ' ' = '';
        let newline = false;
        const idx = text ? text.indexOf(note.lyric!, pos) : -1;
        if (idx >= 0 && idx - pos <= 24) {
          const between = text.slice(pos, idx);
          if (between.includes('\n')) newline = true;
          else if (/\s/.test(between)) sep = ' ';
          pos = idx + note.lyric!.length;
        } else {
          // 텍스트 매칭 실패(멜리스마 연장·편집된 가사 등) — 시간 간격으로 근사
          newline = gap > 1.2;
          sep = !newline && gap > 0.35 ? ' ' : '';
        }
        const lineChars = line.reduce((sum, token) => sum + token.note.lyric!.length + (token.sep ? 1 : 0), 0);
        if (i > 0 && (newline || gap > 2.5 || (lineChars >= 22 && sep === ' '))) pushLine();
        if (!line.length) sep = '';
        line.push({ note, sep });
      }
      pushLine();
    }
    const lineText = (tokens: LyricToken[]) => tokens.map((token) => (token.sep ? ' ' : '') + token.note.lyric).join('');

    const draw = () => {
      if (phaseRef.current !== 'running') return;
      const now = audio.currentTime;
      // 캡처 시계 ↔ MR 시계 재동기화 (2 s마다)
      const sync = syncRef.current;
      if (sync && (Math.abs(sync.latestT - sync.t) > 2 || sync.mr === 0)) {
        syncRef.current = { t: sync.latestT, mr: now, latestT: sync.latestT };
      }
      const done = scorer.tick(now);
      if (done.length) {
        judgedCount += done.length;
        setLastJudged(done[done.length - 1]);
        setTotals(scorer.totals());
        if (judgedCount % 2 === 0) setMeasures(scorer.measures());
      }
      if (audio.ended || now >= (scorer.notes[scorer.notes.length - 1]?.end ?? 0) + 2) {
        stop(true);
        return;
      }

      const width = canvas.clientWidth;
      const height = canvas.clientHeight;
      if (canvas.width !== width * ratio) { canvas.width = width * ratio; canvas.height = height * ratio; }
      const context = canvas.getContext('2d');
      if (!context) return;
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      const nowX = width * 0.3;
      const pxPerSecond = width / (RUNWAY_SECONDS_AHEAD + RUNWAY_SECONDS_BEHIND);
      const timeToX = (seconds: number) => nowX + (seconds - now) * pxPerSecond;
      const plotBottom = height - LYRIC_STRIP; // 이 아래는 가사 스트립
      const rowHeight = (plotBottom - 26) / (high - low + 1);
      const midiToY = (midi: number) => 8 + (high - midi) * rowHeight;

      context.fillStyle = '#fbfaf5';
      context.fillRect(0, 0, width, height);
      for (let midi = low; midi <= high; midi++) {
        if (midi % 12 === 0) {
          context.strokeStyle = 'rgba(23,34,29,.18)';
          context.beginPath(); context.moveTo(0, midiToY(midi) + rowHeight / 2); context.lineTo(width, midiToY(midi) + rowHeight / 2); context.stroke();
          context.fillStyle = '#9aa19c'; context.font = '9px Arial'; context.textBaseline = 'middle';
          context.fillText(midiName(midi), 4, midiToY(midi) + rowHeight / 2 - 6);
        }
      }
      // 마디선
      const grid = reference.beatGrid;
      const meter = Number(reference.timeSignature.split('/')[0]) || 4;
      if (grid?.beatTimes) {
        context.font = '9px Arial'; context.textBaseline = 'top';
        for (let index = 0; index < grid.beatTimes.length; index++) {
          const beat = index - grid.offsetBeats;
          const time = grid.beatTimes[index];
          if (beat < 0 || Math.abs(beat % meter) > 1e-6) continue;
          if (time < now - RUNWAY_SECONDS_BEHIND || time > now + RUNWAY_SECONDS_AHEAD) continue;
          const x = timeToX(time);
          context.strokeStyle = 'rgba(23,34,29,.15)';
          context.beginPath(); context.moveTo(x, 0); context.lineTo(x, plotBottom - 14); context.stroke();
          context.fillStyle = '#9aa19c';
          context.fillText(String(Math.round(beat / meter) + 1), x + 3, 2);
        }
      }
      // 음표
      for (const note of scorer.notes) {
        if (note.end < now - RUNWAY_SECONDS_BEHIND || note.start > now + RUNWAY_SECONDS_AHEAD) continue;
        const x0 = timeToX(note.start);
        const x1 = Math.max(x0 + 3, timeToX(note.end) - 1);
        const y = midiToY(note.midi + semitones);
        if (note.status === 'upcoming' || note.status === 'active') {
          context.fillStyle = note.status === 'active' ? '#17221d' : '#d8d4c9';
        } else {
          context.fillStyle = PITCH_COLORS[note.status];
        }
        context.fillRect(x0, y - rowHeight * 0.42, x1 - x0, rowHeight * 0.84);
      }
      // 내 목소리 궤적 (옥타브는 목표 근처로 폴딩해서 표시)
      context.strokeStyle = '#17221d';
      context.lineWidth = 2;
      let open = false;
      let anchor = (low + high) / 2;
      const frames = scorer.frames;
      for (let i = Math.max(0, frames.length - 500); i < frames.length; i++) {
        const frame = frames[i];
        if (frame.t < now - RUNWAY_SECONDS_BEHIND) continue;
        if (frame.midi == null) { if (open) { context.stroke(); open = false; } continue; }
        const active = scorer.notes.find((note) => note.start - 0.1 <= frame.t && frame.t <= note.end + 0.1);
        if (active) anchor = active.midi + semitones;
        const displayed = anchor + fold(frame.midi - anchor);
        const x = timeToX(frame.t);
        const y = midiToY(displayed);
        if (!open) { context.beginPath(); context.moveTo(x, y); open = true; } else context.lineTo(x, y);
      }
      if (open) context.stroke();
      // 현재 위치선
      context.fillStyle = '#d7ff43';
      context.fillRect(nowX - 1, 0, 2, plotBottom - 14);
      context.strokeStyle = '#17221d';
      context.strokeRect(nowX - 1, 0, 2, plotBottom - 14);
      // 시간
      context.fillStyle = '#69736c'; context.font = '10px Arial'; context.textBaseline = 'bottom';
      context.fillText(formatTime(now), nowX + 6, plotBottom - 1);

      // 가사 스트립 (노래방식): 현재 가사 줄을 통째로 가운데에 띄우고 부른 만큼 색을 채운다.
      // 간주 중에는 다가올 줄이 미리 보인다.
      const stripTop = plotBottom;
      context.fillStyle = '#f1eee3';
      context.fillRect(0, stripTop, width, LYRIC_STRIP);
      context.strokeStyle = 'rgba(23,34,29,.15)';
      context.beginPath(); context.moveTo(0, stripTop + 0.5); context.lineTo(width, stripTop + 0.5); context.stroke();
      const lineIndex = lyricLines.findIndex((entry) => entry.end >= now - 0.15);
      const currentLine = lineIndex >= 0 ? lyricLines[lineIndex] : undefined;
      if (currentLine) {
        // 줄 전체가 화면 폭에 들어가도록 폰트 크기를 맞춘다
        let fontSize = 19;
        context.font = `600 ${fontSize}px Arial`;
        context.textBaseline = 'middle';
        const full = lineText(currentLine.tokens);
        const maxWidth = width - 32;
        let total = context.measureText(full).width;
        if (total > maxWidth) {
          fontSize = Math.max(12, Math.floor((fontSize * maxWidth) / total));
          context.font = `600 ${fontSize}px Arial`;
          total = context.measureText(full).width;
        }
        const spaceWidth = context.measureText(' ').width;
        let x = (width - total) / 2;
        const y = stripTop + 16;
        for (const token of currentLine.tokens) {
          if (token.sep) x += spaceWidth;
          const note = token.note;
          const textWidth = context.measureText(note.lyric!).width;
          const active = note.start - 0.05 <= now && now <= note.end + 0.05;
          if (active) {
            context.fillStyle = '#d7ff43';
            context.fillRect(x - 2, y - fontSize * 0.62, textWidth + 4, fontSize * 1.24);
          }
          context.fillStyle = active ? '#17221d'
            : note.status !== 'upcoming' && note.status !== 'active' ? PITCH_COLORS[note.status]
            : note.end < now ? '#17221d' : '#9aa19c';
          context.fillText(note.lyric!, x, y);
          x += textWidth;
        }
        const nextLine = lyricLines[lineIndex + 1];
        if (nextLine) {
          context.font = '12px Arial';
          context.fillStyle = '#a9a294';
          const preview = lineText(nextLine.tokens);
          context.fillText(preview, (width - context.measureText(preview).width) / 2, stripTop + LYRIC_STRIP - 9);
        }
      }

      rafRef.current = requestAnimationFrame(draw);
    };
    rafRef.current = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(rafRef.current);
  }, [phase, semitones, reference, stop]);

  // 정리
  useEffect(() => () => {
    cancelAnimationFrame(rafRef.current);
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    streamRef.current?.getTracks().forEach((track) => track.stop());
    void contextRef.current?.close();
  }, []);

  const makePreciseReport = async () => {
    const blob = recordingRef.current ?? (chunksRef.current.length ? new Blob(chunksRef.current, { type: 'audio/webm' }) : null);
    if (!blob || blob.size < 20000) { setReport({ phase: 'error', message: '녹음 데이터가 없습니다. 다시 한 번 불러주세요.', progress: 0 }); return; }
    setReport({ phase: 'uploading', message: '녹음을 AI 엔진으로 전송하고 있습니다.', progress: 2 });
    try {
      const form = new FormData();
      form.append('audio', new File([blob], 'live-take.webm', { type: 'audio/webm' }));
      form.append('sourceType', 'vocal'); // 헤드폰 전제 무반주 녹음 → 분리 생략
      form.append('allowTranspose', 'true');
      form.append('octaveTolerant', 'true');
      const response = await fetch(`${modelApi}/jobs/${jobId}/compare`, { method: 'POST', body: form });
      const payload = await response.json() as { id: string; detail?: string };
      if (!response.ok) throw new Error(payload.detail || '전송에 실패했습니다.');
      setReport({ phase: 'analyzing', message: '정밀 분석 중…', progress: 5 });
      for (;;) {
        await new Promise((resolve) => setTimeout(resolve, 1400));
        const statusResponse = await fetch(`${modelApi}/jobs/${payload.id}`, { cache: 'no-store' });
        const job = await statusResponse.json() as { status: string; progress: number; message: string; error?: string | null; result?: { comparison?: unknown } | null };
        setReport({ phase: 'analyzing', message: job.message, progress: job.progress });
        if (job.status === 'failed') throw new Error(job.error || '정밀 분석에 실패했습니다.');
        if (job.status === 'complete' && job.result?.comparison) {
          setReport({ phase: 'done', message: '정밀 리포트 완성 — 아래 SING & COMPARE 카드에서 확인하세요.', progress: 100 });
          onPreciseJob?.(job);
          break;
        }
      }
    } catch (caught) {
      setReport({ phase: 'error', message: caught instanceof Error ? caught.message : '정밀 분석에 실패했습니다.', progress: 0 });
    }
  };

  if (!hasInstrumental) return null;
  const running = phase === 'running' || phase === 'countdown';
  const keyLabel = semitones === 0 ? '원키' : `${semitones > 0 ? '+' : '−'}${Math.abs(semitones)}`;
  const currentKey = shiftedKeyName(reference.key, semitones);

  return (
    <div className="live-card" aria-label="라이브 채점">
      <div className="score-head">
        <div><span>LIVE SCORING · 라이브 채점</span><small>반주를 들으며 마이크로 부르면 음표가 끝날 때마다 바로 채점됩니다 · 🎧 헤드폰/이어폰 권장 (스피커 소리가 마이크에 섞이면 판정이 흐려집니다)</small></div>
        <span aria-hidden="true">🎧</span>
      </div>

      <div className="live-controls">
        {phase === 'idle' && <button type="button" className="live-primary" onClick={enableMic}>① 마이크 켜기</button>}
        {phase !== 'idle' && (
          <div className="live-mic" role="status">
            <span>MIC</span>
            <i><b style={{ width: `${Math.min(100, Math.round((micLevel.rms / 0.12) * 100))}%` }} /></i>
            <em>{phase === 'running' ? 'ON AIR' : micLevel.name ?? (micLevel.rms > RMS_MIN ? '…' : '무음')}</em>
          </div>
        )}
        <div className="key-shift-controls" role="group" aria-label="반주 키">
          <button type="button" onClick={() => void changeKey(semitones - 1)} disabled={keyBusy || running || semitones <= -KEY_RANGE} aria-label="키 내리기">−</button>
          <b className={keyBusy ? 'busy' : ''}>{keyLabel}</b>
          <button type="button" onClick={() => void changeKey(semitones + 1)} disabled={keyBusy || running || semitones >= KEY_RANGE} aria-label="키 올리기">+</button>
          {currentKey && <small>{currentKey}</small>}
        </div>
        <label className="live-check"><input type="checkbox" checked={skipIntro} onChange={(event) => setSkipIntro(event.target.checked)} disabled={running} />전주 건너뛰기</label>
        <label className="live-check" title="내 장비의 입력·출력 지연을 부른 박자의 중앙값으로 자동 상쇄합니다"><input type="checkbox" checked={autoCalibrate} onChange={(event) => { setAutoCalibrate(event.target.checked); if (scorerRef.current) scorerRef.current.autoCalibrate = event.target.checked; }} />지연 자동 보정{totals && totals.calibration !== 0 ? ` (${Math.round(totals.calibration * 1000)} ms)` : ''}</label>
        {phase === 'mic' && <button type="button" className="live-primary" onClick={() => void start()}>▶ 시작</button>}
        {phase === 'finished' && <button type="button" className="live-primary" onClick={() => void start()}>↻ 다시 부르기</button>}
        {running && <button type="button" className="live-stop" onClick={() => stop(false)}>■ 종료</button>}
      </div>
      {error && <div className="quality-flag light" role="alert"><b>{error}</b></div>}

      {(running || phase === 'finished') && (
        <>
          <div className="live-scoreboard">
            <div className="live-total" style={{ borderColor: scoreColor(totals?.overall ?? 0) }}>
              <span>SCORE</span>
              <strong style={{ color: scoreColor(totals?.overall ?? 0) }}>{totals ? Math.round(totals.overall) : '—'}</strong>
              <b>{totals ? gradeOf(totals.overall) : ''}</b>
            </div>
            <div className="live-stats">
              <article><span>음정</span><strong>{totals ? Math.round(totals.pitch) : '—'}</strong></article>
              <article><span>박자</span><strong>{totals ? Math.round(totals.timing) : '—'}</strong></article>
              <article><span>판정</span><strong>{totals?.judged ?? 0}<em>음</em></strong></article>
              <article><span>정확</span><strong style={{ color: PITCH_COLORS.good }}>{totals?.good ?? 0}</strong></article>
              <article><span>높게/낮게</span><strong>{(totals?.sharp ?? 0)}/{(totals?.flat ?? 0)}</strong></article>
              <article><span>놓침</span><strong style={{ color: PITCH_COLORS.missed }}>{totals?.missed ?? 0}</strong></article>
            </div>
            {lastJudged && lastJudged.status !== 'upcoming' && lastJudged.status !== 'active' && (
              <div className="live-feedback" style={{ background: PITCH_COLORS[lastJudged.status] }}>
                <b>{PITCH_LABELS[lastJudged.status]}</b>
                <small>{lastJudged.lyric ?? midiName(lastJudged.midi + semitones)}{lastJudged.cents != null ? ` ${lastJudged.cents > 0 ? '+' : ''}${lastJudged.cents}¢` : ''}{lastJudged.deviation != null && Math.abs(lastJudged.deviation) > TIMING_GOOD ? ` · ${lastJudged.deviation > 0 ? '늦음' : '빠름'}` : ''}</small>
              </div>
            )}
          </div>

          <div className="live-runway-wrap">
            <canvas ref={canvasRef} className="live-runway" role="img" aria-label="다가오는 원곡 음표와 내 목소리 궤적" />
            {phase === 'countdown' && <div className="live-countdown">{countdown}</div>}
          </div>

          {measures.length > 0 && (
            <div className="compare-measures live" role="list" aria-label="마디별 점수">
              {measures.map((measure) => (
                <span role="listitem" key={measure.measure} className="compare-measure" style={{ background: scoreColor(measure.score) }} title={`${measure.measure}마디 · 음정 ${measure.pitch} · 박자 ${measure.timing} · ${measure.notes}음`}>
                  <b>{measure.measure}</b><i>{measure.score}</i>
                </span>
              ))}
            </div>
          )}
        </>
      )}

      {phase === 'finished' && totals && (
        <div className="live-summary">
          <p>
            {totals.judged}음 판정 · 정확 {totals.good} · 높게 {totals.sharp} · 낮게 {totals.flat} · 다른 음 {totals.wrong} · 놓침 {totals.missed}
            {totals.sung > 0 ? ` · 평균 ${Math.round(totals.centsSum / totals.sung)}¢ ${totals.centsSum < 0 ? '(낮게 부르는 경향)' : totals.centsSum > 0 ? '(높게 부르는 경향)' : ''}` : ''}
            {totals.calibration !== 0 ? ` · 장비 지연 ${Math.round(totals.calibration * 1000)} ms 자동 보정됨` : ''}
          </p>
          <div className="live-report">
            {report.phase === 'none' && <button type="button" className="live-primary" onClick={() => void makePreciseReport()}>정밀 리포트 만들기 (녹음본 전체 파이프라인 분석 · 20–40초)</button>}
            {(report.phase === 'uploading' || report.phase === 'analyzing') && <div className="process-status light"><div><span>{report.message}</span><b>{report.progress}%</b></div><i><em style={{ width: `${report.progress}%` }} /></i></div>}
            {report.phase === 'done' && <div className="quality-flag light" role="status"><b>{report.message}</b></div>}
            {report.phase === 'error' && <div className="quality-flag light" role="alert"><b>{report.message}</b><button type="button" onClick={() => void makePreciseReport()}>다시 시도</button></div>}
          </div>
        </div>
      )}

      <audio ref={audioRef} src={instrumentalUrl(semitones)} preload="auto" />
      <p className="compare-note">라이브 판정은 재생 위치 기준의 근사치입니다(±45¢ 정확 · ±120 ms 정박, 정밀 채점과 동일 기준). 레가토로 이어지는 음의 박자는 중립 처리되며, 정확한 음정 곡선·구간 분석은 종료 후 「정밀 리포트」에서 확인하세요.</p>
    </div>
  );
}
