'use client';

import { useEffect, useRef, useState } from 'react';

export const KEY_RANGE = 6; // 노래방 키 조절 범위: −6 ~ +6 반음

const TONIC_NAMES = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];

export function shiftedKeyName(key: { tonic?: number; mode?: string } | undefined, semitones: number): string | null {
  if (key?.tonic === undefined || key.tonic === null) return null;
  const tonic = (((key.tonic + semitones) % 12) + 12) % 12;
  return `${TONIC_NAMES[tonic]} ${key.mode === 'minor' ? 'minor' : 'major'}`;
}

type Props = {
  modelApi: string;
  jobId: string;
  keyInfo?: { tonic?: number; mode?: string; name?: string };
};

/** 반주(MR) 플레이어 + 노래방식 키(음정) 조절. 템포는 유지되고 음높이만 반음 단위로 이동합니다. */
export function KeyShiftPlayer({ modelApi, jobId, keyInfo }: Props) {
  const [semitones, setSemitones] = useState(0); // 현재 재생 중인 키
  const [pending, setPending] = useState<number | null>(null); // 렌더링 대기 중인 키
  const [error, setError] = useState('');
  const audioRef = useRef<HTMLAudioElement>(null);
  const resumeRef = useRef<{ time: number; playing: boolean } | null>(null);

  const fileUrl = (value: number) => `${modelApi}/jobs/${jobId}/files/instrumental${value ? `?semitones=${value}` : ''}`;

  const changeKey = async (next: number) => {
    const target = Math.max(-KEY_RANGE, Math.min(KEY_RANGE, next));
    if (target === semitones || pending !== null) return;
    setError('');
    setPending(target);
    try {
      const response = await fetch(`${modelApi}/jobs/${jobId}/instrumental-key`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ semitones: target }),
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => ({})) as { detail?: string };
        throw new Error(payload.detail || '키 변경에 실패했습니다.');
      }
      const audio = audioRef.current;
      resumeRef.current = audio ? { time: audio.currentTime, playing: !audio.paused } : null;
      setSemitones(target);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '키 변경에 실패했습니다.');
    } finally {
      setPending(null);
    }
  };

  // src가 바뀐 뒤 이전 재생 위치·재생 상태를 복원 (노래방처럼 끊김 없이 키만 바뀐 느낌)
  useEffect(() => {
    const audio = audioRef.current;
    const resume = resumeRef.current;
    if (!audio || !resume) return;
    resumeRef.current = null;
    const restore = () => {
      audio.currentTime = resume.time;
      if (resume.playing) void audio.play().catch(() => undefined);
    };
    audio.addEventListener('loadedmetadata', restore, { once: true });
    return () => audio.removeEventListener('loadedmetadata', restore);
  }, [semitones]);

  const label = semitones === 0 ? '원키' : `${semitones > 0 ? '+' : '−'}${Math.abs(semitones)}`;
  const originalKey = keyInfo?.name ?? shiftedKeyName(keyInfo, 0);
  const currentKey = shiftedKeyName(keyInfo, semitones);
  const busy = pending !== null;

  return (
    <article className="key-shift-card">
      <div>
        <span>INSTRUMENTAL</span>
        <small>보컬 제거 반주 · {busy ? `키 ${pending! > 0 ? '+' : ''}${pending} 렌더링 중…` : currentKey && semitones !== 0 ? `${originalKey} → ${currentKey}` : originalKey ?? '원키'}</small>
      </div>
      <audio ref={audioRef} controls src={fileUrl(semitones)} preload="metadata" />
      <div className="key-shift-controls" role="group" aria-label="반주 키 조절">
        <button type="button" onClick={() => changeKey(semitones - 1)} disabled={busy || semitones <= -KEY_RANGE} aria-label="키 내리기">−</button>
        <b className={busy ? 'busy' : ''} title="반음 단위 · 템포 유지">{label}</b>
        <button type="button" onClick={() => changeKey(semitones + 1)} disabled={busy || semitones >= KEY_RANGE} aria-label="키 올리기">+</button>
        {semitones !== 0 && <button type="button" className="reset" onClick={() => changeKey(0)} disabled={busy}>원키</button>}
      </div>
      <a href={fileUrl(semitones)} download>WAV{semitones ? ` (${label})` : ''} ↓</a>
      {error && <p className="key-shift-error">{error}</p>}
    </article>
  );
}
