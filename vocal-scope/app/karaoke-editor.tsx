'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

export type KaraokeNote = {
  midi: number;
  name: string;
  scoreStartBeat: number;
  scoreEndBeat: number;
  scoreStart: number;
  scoreEnd: number;
  sourceStart: number;
  sourceEnd: number;
  lyric: string | null;
  confidence: number;
  actualMidi: number;
  cents: number;
  duration: number;
  lyricScore?: number | null;
  lyricExtend?: boolean;
  lyricStart?: number;
  lyricEnd?: number;
  lyricStatus?: 'aligned' | 'low-confidence' | 'unknown-language';
  explicitExtend?: boolean;
  quantizationAdjusted?: boolean;
  register?: 'modal' | 'falsetto' | 'mixed' | 'unclear';
  registerConfidence?: number;
  registerScore?: number | null;
  lightness?: number | null;
  breathy?: boolean;
  h1h2Db?: number | null;
  hrfDb?: number | null;
  cppDb?: number | null;
};

// 발성 가벼움 0 → 100: dense modal ink → falsetto purple (same hue as REGISTER_COLORS.falsetto)
const LIGHT_FROM: [number, number, number] = [0x17, 0x22, 0x1d];
const LIGHT_TO: [number, number, number] = [0x8d, 0x4b, 0xd6];
export const lightnessColor = (lightness: number, alpha = 1) => {
  const t = Math.max(0, Math.min(1, lightness / 100));
  const mix = LIGHT_FROM.map((from, index) => Math.round(from + (LIGHT_TO[index] - from) * t));
  return `rgba(${mix[0]},${mix[1]},${mix[2]},${alpha})`;
};

export const REGISTER_COLORS: Record<NonNullable<KaraokeNote['register']>, string> = {
  modal: '#17221d',
  falsetto: '#8d4bd6',
  mixed: '#c98a2e',
  unclear: '#5d6a63',
};
export const REGISTER_LABELS: Record<NonNullable<KaraokeNote['register']>, string> = {
  modal: '진성 우세',
  falsetto: '가성 우세',
  mixed: '혼합',
  unclear: '불명확',
};

const NOTE_NAMES = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
export const midiName = (midi: number) => `${NOTE_NAMES[((midi % 12) + 12) % 12]}${Math.floor(midi / 12) - 1}`;
const BLACK_KEYS = new Set([1, 3, 6, 8, 10]);
const PX_PER_BEAT = 56;
const ROW = 16;
const TOP = 24;
const KEYS_WIDTH = 54;
const GRID = 0.25;
const MIN_MIDI = 36;
const MAX_MIDI = 95;
const snap = (value: number) => Math.round(value / GRID) * GRID;
const clampMidi = (midi: number) => Math.max(MIN_MIDI, Math.min(MAX_MIDI, midi));

type Drag = { index: number; mode: 'move' | 'resize'; startX: number; startY: number; origin: KaraokeNote; moved: boolean };

function withTimes(note: KaraokeNote, tempoBpm: number): KaraokeNote {
  const secondsPerBeat = 60 / tempoBpm;
  return {
    ...note,
    name: midiName(note.midi),
    scoreStart: note.scoreStartBeat * secondsPerBeat,
    scoreEnd: note.scoreEndBeat * secondsPerBeat,
  };
}

function normalize(notes: KaraokeNote[], tempoBpm: number): KaraokeNote[] {
  const sorted = [...notes].sort((a, b) => a.scoreStartBeat - b.scoreStartBeat || a.midi - b.midi);
  for (let index = 0; index < sorted.length - 1; index += 1) {
    if (sorted[index].scoreEndBeat > sorted[index + 1].scoreStartBeat) {
      sorted[index] = { ...sorted[index], scoreEndBeat: sorted[index + 1].scoreStartBeat };
    }
  }
  return sorted.filter((note) => note.scoreEndBeat > note.scoreStartBeat).map((note) => withTimes(note, tempoBpm));
}

/** Mount with `key={version}` so a regenerated score resets the editor state. */
export function KaraokeEditor({
  initialNotes,
  meter,
  tempoBpm,
  saving,
  onSave,
}: {
  initialNotes: KaraokeNote[];
  meter: number;
  tempoBpm: number;
  saving: boolean;
  onSave: (notes: KaraokeNote[]) => Promise<void>;
}) {
  const [notes, setNotes] = useState<KaraokeNote[]>(initialNotes);
  const [history, setHistory] = useState<KaraokeNote[][]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [drag, setDrag] = useState<Drag | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const keysRef = useRef<HTMLCanvasElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const audioRef = useRef<AudioContext | null>(null);

  const range = useMemo(() => {
    const midis = notes.map((note) => note.midi);
    const low = Math.max(MIN_MIDI, (midis.length ? Math.min(...midis) : 55) - 3);
    const high = Math.min(MAX_MIDI, (midis.length ? Math.max(...midis) : 72) + 3);
    const lastBeat = notes.length ? Math.max(...notes.map((note) => note.scoreEndBeat)) : meter * 4;
    const measures = Math.ceil(lastBeat / meter) + 1;
    return { low, high, beats: measures * meter, measures };
  }, [notes, meter]);

  const width = range.beats * PX_PER_BEAT;
  const height = TOP + (range.high - range.low + 1) * ROW;
  const noteY = useCallback((midi: number) => TOP + (range.high - midi) * ROW, [range.high]);

  const commit = useCallback((next: KaraokeNote[], record = true) => {
    if (record) setHistory((stack) => [...stack.slice(-49), notes]);
    setNotes(normalize(next, tempoBpm));
    setDirty(true);
  }, [notes, tempoBpm]);

  // ------------------------------------------------------------------ drawing
  useEffect(() => {
    const canvas = canvasRef.current;
    const keys = keysRef.current;
    if (!canvas || !keys) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
    keys.width = KEYS_WIDTH * ratio;
    keys.height = height * ratio;
    keys.style.width = `${KEYS_WIDTH}px`;
    keys.style.height = `${height}px`;
    const context = canvas.getContext('2d');
    const keyContext = keys.getContext('2d');
    if (!context || !keyContext) return;
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    keyContext.setTransform(ratio, 0, 0, ratio, 0, 0);

    context.fillStyle = '#fbfaf5';
    context.fillRect(0, 0, width, height);
    keyContext.fillStyle = '#ece9df';
    keyContext.fillRect(0, 0, KEYS_WIDTH, height);
    keyContext.font = '700 9px Arial';
    keyContext.textBaseline = 'middle';
    for (let midi = range.low; midi <= range.high; midi += 1) {
      const y = noteY(midi);
      if (BLACK_KEYS.has(midi % 12)) {
        context.fillStyle = 'rgba(23,34,29,.05)';
        context.fillRect(0, y, width, ROW);
        keyContext.fillStyle = '#d9d5c8';
        keyContext.fillRect(0, y, KEYS_WIDTH, ROW);
      }
      keyContext.fillStyle = midi % 12 === 0 ? '#17221d' : '#6a736c';
      keyContext.fillText(midiName(midi), 6, y + ROW / 2);
      if (midi % 12 === 0) {
        context.strokeStyle = 'rgba(23,34,29,.18)';
        context.beginPath();
        context.moveTo(0, y + ROW);
        context.lineTo(width, y + ROW);
        context.stroke();
      }
    }
    for (let beat = 0; beat <= range.beats; beat += 0.5) {
      const x = beat * PX_PER_BEAT;
      const isMeasure = beat % meter === 0;
      const isBeat = Number.isInteger(beat);
      context.strokeStyle = isMeasure ? 'rgba(23,34,29,.55)' : isBeat ? 'rgba(23,34,29,.18)' : 'rgba(23,34,29,.07)';
      context.lineWidth = isMeasure ? 1.5 : 1;
      context.beginPath();
      context.moveTo(x, isMeasure ? 0 : TOP);
      context.lineTo(x, height);
      context.stroke();
      if (isMeasure && beat < range.beats) {
        context.fillStyle = '#17221d';
        context.font = '900 10px Arial';
        context.textBaseline = 'top';
        context.fillText(String(beat / meter + 1), x + 5, 6);
      }
    }
    notes.forEach((note, index) => {
      const x = note.scoreStartBeat * PX_PER_BEAT;
      const w = Math.max(3, (note.scoreEndBeat - note.scoreStartBeat) * PX_PER_BEAT - 1);
      const y = noteY(note.midi) + 1.5;
      const isSelected = index === selected;
      // colour = continuous 발성 가벼움 (0 dense modal → 100 falsetto); lyric-less notes are translucent
      const lightColor = typeof note.lightness === 'number' ? lightnessColor(note.lightness, note.lyric ? 1 : 0.55) : null;
      context.fillStyle = isSelected ? '#d7ff43' : lightColor ?? (note.lyric ? '#17221d' : '#5d6a63');
      context.fillRect(x, y, w, ROW - 3);
      if (!isSelected && note.register === 'falsetto') {  // discrete label as an outline so it stays visible on the gradient
        context.strokeStyle = REGISTER_COLORS.falsetto;
        context.lineWidth = 2;
        context.strokeRect(x + 1, y + 1, Math.max(1, w - 2), ROW - 5);
      }
      if (isSelected) {
        context.strokeStyle = '#17221d';
        context.lineWidth = 1.5;
        context.strokeRect(x + 0.5, y + 0.5, w - 1, ROW - 4);
      }
      if (note.lyric && w > 14) {
        context.fillStyle = isSelected ? '#17221d' : '#f4f2e9';
        context.font = '700 10px Arial';
        context.textBaseline = 'middle';
        context.fillText(note.lyric, x + 4, y + (ROW - 3) / 2, w - 6);
      }
    });
  }, [notes, selected, width, height, range, meter, noteY]);

  // ------------------------------------------------------------------ pointer
  const hitTest = (x: number, y: number) => {
    for (let index = notes.length - 1; index >= 0; index -= 1) {
      const note = notes[index];
      const left = note.scoreStartBeat * PX_PER_BEAT;
      const right = note.scoreEndBeat * PX_PER_BEAT;
      const top = noteY(note.midi);
      if (x >= left && x <= right && y >= top && y <= top + ROW) {
        return { index, mode: (right - x <= 7 ? 'resize' : 'move') as Drag['mode'] };
      }
    }
    return null;
  };

  const localPoint = (event: React.MouseEvent) => {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  };

  const onMouseDown = (event: React.MouseEvent) => {
    const point = localPoint(event);
    const hit = hitTest(point.x, point.y);
    if (!hit) {
      setSelected(null);
      return;
    }
    setSelected(hit.index);
    setDrag({ index: hit.index, mode: hit.mode, startX: point.x, startY: point.y, origin: notes[hit.index], moved: false });
  };

  const onMouseMove = (event: React.MouseEvent) => {
    if (!drag) return;
    const point = localPoint(event);
    const deltaBeats = snap((point.x - drag.startX) / PX_PER_BEAT);
    const deltaRows = Math.round((point.y - drag.startY) / ROW);
    if (!drag.moved) {
      if (deltaBeats === 0 && deltaRows === 0) return;
      setHistory((stack) => [...stack.slice(-49), notes]);
      setDrag({ ...drag, moved: true });
    }
    const origin = drag.origin;
    const next = [...notes];
    if (drag.mode === 'move') {
      const start = Math.max(0, snap(origin.scoreStartBeat + deltaBeats));
      next[drag.index] = withTimes({
        ...origin,
        scoreStartBeat: start,
        scoreEndBeat: start + (origin.scoreEndBeat - origin.scoreStartBeat),
        midi: clampMidi(origin.midi - deltaRows),
      }, tempoBpm);
    } else {
      next[drag.index] = withTimes({
        ...origin,
        scoreEndBeat: Math.max(origin.scoreStartBeat + GRID, snap(origin.scoreEndBeat + deltaBeats)),
      }, tempoBpm);
    }
    setNotes(next);
    setDirty(true);
  };

  const onMouseUp = () => {
    if (drag?.moved) {
      const edited = notes[drag.index];
      const normalized = normalize(notes, tempoBpm);
      setNotes(normalized);
      setSelected(normalized.findIndex((note) => note === edited || (note.scoreStartBeat === edited.scoreStartBeat && note.midi === edited.midi)));
    }
    setDrag(null);
  };

  // ------------------------------------------------------------------ actions
  const current = selected !== null ? notes[selected] : null;

  const updateSelected = (patch: Partial<KaraokeNote>) => {
    if (selected === null) return;
    const next = [...notes];
    next[selected] = withTimes({ ...next[selected], ...patch }, tempoBpm);
    setHistory((stack) => [...stack.slice(-49), notes]);
    setNotes(next);
    setDirty(true);
  };

  const shiftPitch = (delta: number) => {
    if (!current) return;
    updateSelected({ midi: clampMidi(current.midi + delta) });
  };

  const splitSelected = () => {
    if (!current || selected === null) return;
    const length = current.scoreEndBeat - current.scoreStartBeat;
    if (length < GRID * 2) return;
    const middle = current.scoreStartBeat + snap(length / 2);
    const first = { ...current, scoreEndBeat: middle };
    const second = { ...current, scoreStartBeat: middle, lyric: null, lyricScore: null };
    const next = [...notes];
    next.splice(selected, 1, first, second);
    commit(next);
  };

  const mergeWithNext = () => {
    if (!current || selected === null || selected >= notes.length - 1) return;
    const following = notes[selected + 1];
    const merged = {
      ...current,
      scoreEndBeat: following.scoreEndBeat,
      lyric: [current.lyric, following.lyric].filter(Boolean).join('') || null,
    };
    const next = [...notes];
    next.splice(selected, 2, merged);
    commit(next);
  };

  const deleteSelected = () => {
    if (selected === null) return;
    commit(notes.filter((_, index) => index !== selected));
    setSelected(null);
  };

  const undo = () => {
    if (!history.length) return;
    const previous = history[history.length - 1];
    setHistory(history.slice(0, -1));
    setNotes(previous);
    setSelected(null);
    setDirty(true);
  };

  const reset = () => {
    setNotes(initialNotes);
    setHistory([]);
    setSelected(null);
    setDirty(false);
  };

  const preview = () => {
    if (!current) return;
    try {
      audioRef.current ??= new AudioContext();
      const context = audioRef.current;
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      const seconds = Math.min(0.9, Math.max(0.25, current.scoreEnd - current.scoreStart));
      oscillator.type = 'triangle';
      oscillator.frequency.value = 440 * 2 ** ((current.midi - 69) / 12);
      gain.gain.setValueAtTime(0.0001, context.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.25, context.currentTime + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + seconds);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start();
      oscillator.stop(context.currentTime + seconds + 0.05);
    } catch {
      // audio preview is best-effort
    }
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if ((event.target as HTMLElement).tagName === 'INPUT' || (event.target as HTMLElement).tagName === 'TEXTAREA') return;
    if (selected === null) return;
    if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); deleteSelected(); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); shiftPitch(1); }
    else if (event.key === 'ArrowDown') { event.preventDefault(); shiftPitch(-1); }
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      const delta = event.key === 'ArrowLeft' ? -GRID : GRID;
      if (!current) return;
      const start = Math.max(0, current.scoreStartBeat + delta);
      updateSelected({ scoreStartBeat: start, scoreEndBeat: start + (current.scoreEndBeat - current.scoreStartBeat) });
    } else if (event.key === ' ') { event.preventDefault(); preview(); }
  };

  useEffect(() => {
    if (selected === null || !scrollRef.current || !current) return;
    const container = scrollRef.current;
    const x = current.scoreStartBeat * PX_PER_BEAT;
    if (x < container.scrollLeft + KEYS_WIDTH || x > container.scrollLeft + container.clientWidth - 80) {
      container.scrollLeft = Math.max(0, x - container.clientWidth / 3);
    }
  }, [selected, current]);

  const withLyric = notes.filter((note) => note.lyric).length;
  const measureOf = (beat: number) => `${Math.floor(beat / meter) + 1}마디 ${(beat % meter) + 1}박`;

  return (
    <div className="karaoke-editor" onKeyDown={onKeyDown} tabIndex={0} aria-label="노래방 악보 편집기">
      <div className="editor-toolbar">
        <div><span>REVIEW &amp; EDIT</span><small>{notes.length}개 음표 · 가사 {withLyric}개 · 드래그로 음높이·위치, 오른쪽 끝을 끌어 길이 조절 · ↑↓←→ Delete 스페이스(듣기)</small></div>
        <div className="editor-actions">
          <button type="button" onClick={undo} disabled={!history.length}>되돌리기</button>
          <button type="button" onClick={reset} disabled={!dirty}>초기화</button>
          <button type="button" className="primary" onClick={() => void onSave(notes)} disabled={!dirty || saving}>{saving ? '악보 생성 중…' : '수정 반영 · 악보 다시 생성'}</button>
        </div>
      </div>
      <div className="editor-body">
        <div className="roll-scroll" ref={scrollRef}>
          <canvas ref={keysRef} className="roll-keys" aria-hidden="true" />
          <canvas
            ref={canvasRef}
            className={`roll-canvas ${drag ? (drag.mode === 'resize' ? 'resizing' : 'moving') : ''}`}
            onMouseDown={onMouseDown}
            onMouseMove={onMouseMove}
            onMouseUp={onMouseUp}
            onMouseLeave={onMouseUp}
          />
        </div>
        <aside className="editor-side">
          {current ? (
            <>
              <p className="eyebrow">SELECTED NOTE</p>
              <strong>{current.name}</strong>
              <small>{measureOf(current.scoreStartBeat)} · {(current.scoreEndBeat - current.scoreStartBeat).toFixed(2)}박 · 원곡 {current.sourceStart.toFixed(2)}s</small>
              {typeof current.lightness === 'number' && (
                <div className="note-register">
                  <div className="lightness-meter" aria-label="발성 가벼움"><i style={{ left: `${current.lightness}%` }} /></div>
                  <small><b style={{ color: lightnessColor(current.lightness) }}>가벼움 {current.lightness}</b> · {REGISTER_LABELS[current.register ?? 'unclear']}{current.breathy ? ' · 숨소리' : ''}{typeof current.registerConfidence === 'number' && current.register !== 'unclear' ? ` · 신뢰도 ${Math.round(current.registerConfidence * 100)}%` : ''}{typeof current.h1h2Db === 'number' ? ` · H1−H2 ${current.h1h2Db > 0 ? '+' : ''}${current.h1h2Db.toFixed(1)} dB` : ''}</small>
                </div>
              )}
              <label>가사 음절<input value={current.lyric ?? ''} onChange={(event) => updateSelected({ lyric: event.target.value.trim() || null })} placeholder="(멜리스마 · 비움)" maxLength={12} /></label>
              <div className="pitch-row"><button type="button" onClick={() => shiftPitch(1)}>♯ 반음 올림</button><button type="button" onClick={() => shiftPitch(-1)}>♭ 반음 내림</button></div>
              <div className="pitch-row"><button type="button" onClick={splitSelected}>반으로 나누기</button><button type="button" onClick={mergeWithNext} disabled={selected === notes.length - 1}>다음 음과 병합</button></div>
              <div className="pitch-row"><button type="button" onClick={preview}>▶ 음 듣기</button><button type="button" className="danger" onClick={deleteSelected}>삭제</button></div>
            </>
          ) : (
            <>
              <p className="eyebrow">HOW TO REVIEW</p>
              <small>음표를 클릭해 선택하세요. 색은 발성 가벼움(짙은 색 = 밀도 있는 진성 → 보라 = 가벼운 가성)이고 보라 테두리는 가성 우세 판정, 반투명은 가사 없는 이음(멜리스마)입니다. 수정 후 “악보 다시 생성”을 누르면 오선 악보와 MIDI·MusicXML이 다시 만들어집니다.</small>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
