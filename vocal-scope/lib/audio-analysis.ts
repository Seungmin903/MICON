export type DetectedNote = {
  midi: number;
  name: string;
  start: number;
  duration: number;
  confidence: number;
};

export type RangeAnalysis = {
  lowest: string;
  highest: string;
  coreRange: string;
  lowestMidi: number;
  highestMidi: number;
  ratios: { low: number; middle: number; high: number };
  confidence: number;
  notes: DetectedNote[];
  duration: number;
};

const NOTE_NAMES = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];

export function midiToName(midi: number) {
  const rounded = Math.round(midi);
  return `${NOTE_NAMES[((rounded % 12) + 12) % 12]}${Math.floor(rounded / 12) - 1}`;
}

function detectFrequency(samples: Float32Array, offset: number, size: number, sampleRate: number) {
  let mean = 0;
  let energy = 0;
  for (let index = 0; index < size; index += 1) mean += samples[offset + index];
  mean /= size;
  for (let index = 0; index < size; index += 1) {
    const value = samples[offset + index] - mean;
    energy += value * value;
  }
  const rms = Math.sqrt(energy / size);
  if (rms < 0.012) return null;

  const minLag = Math.max(8, Math.floor(sampleRate / 1000));
  const maxLag = Math.min(size - 8, Math.ceil(sampleRate / 65));
  let bestLag = 0;
  let bestCorrelation = 0;

  for (let lag = minLag; lag <= maxLag; lag += 1) {
    let cross = 0;
    let leftEnergy = 0;
    let rightEnergy = 0;
    for (let index = 0; index < size - lag; index += 4) {
      const left = samples[offset + index] - mean;
      const right = samples[offset + index + lag] - mean;
      cross += left * right;
      leftEnergy += left * left;
      rightEnergy += right * right;
    }
    const correlation = cross / Math.sqrt(leftEnergy * rightEnergy + 1e-9);
    if (correlation > bestCorrelation) {
      bestCorrelation = correlation;
      bestLag = lag;
    }
  }

  if (bestCorrelation < 0.62 || bestLag === 0) return null;
  return { frequency: sampleRate / bestLag, confidence: bestCorrelation };
}

function weightedPercentile(notes: DetectedNote[], percentile: number) {
  const sorted = [...notes].sort((a, b) => a.midi - b.midi);
  const total = sorted.reduce((sum, note) => sum + note.duration, 0);
  const target = total * percentile;
  let cursor = 0;
  for (const note of sorted) {
    cursor += note.duration;
    if (cursor >= target) return note.midi;
  }
  return sorted.at(-1)?.midi ?? 60;
}

export async function analyzeVocalFile(
  file: File,
  onProgress: (progress: number) => void,
): Promise<RangeAnalysis> {
  const context = new AudioContext();
  try {
    const buffer = await context.decodeAudioData(await file.arrayBuffer());
    const source = buffer.getChannelData(0);
    const targetRate = Math.min(11025, buffer.sampleRate);
    const ratio = buffer.sampleRate / targetRate;
    const downsampled = new Float32Array(Math.floor(source.length / ratio));
    for (let index = 0; index < downsampled.length; index += 1) {
      downsampled[index] = source[Math.floor(index * ratio)];
    }

    const windowSize = 1024;
    const hop = Math.max(Math.floor(targetRate * 0.09), Math.ceil(downsampled.length / 2400));
    const frames: Array<{ midi: number; start: number; confidence: number }> = [];
    const frameCount = Math.max(1, Math.floor((downsampled.length - windowSize) / hop));

    for (let frame = 0; frame < frameCount; frame += 1) {
      const offset = frame * hop;
      const pitch = detectFrequency(downsampled, offset, windowSize, targetRate);
      if (pitch) {
        const midi = 69 + 12 * Math.log2(pitch.frequency / 440);
        if (midi >= 36 && midi <= 96) {
          frames.push({ midi: Math.round(midi), start: offset / targetRate, confidence: pitch.confidence });
        }
      }
      if (frame % 40 === 0) {
        onProgress(Math.round((frame / frameCount) * 100));
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
    }

    const frameDuration = hop / targetRate;
    const notes: DetectedNote[] = [];
    let current: DetectedNote | null = null;
    for (const frame of frames) {
      if (current && Math.abs(current.midi - frame.midi) <= 1 && frame.start <= current.start + current.duration + frameDuration * 1.6) {
        current.duration = frame.start + frameDuration - current.start;
        current.confidence = (current.confidence + frame.confidence) / 2;
      } else {
        if (current && current.duration >= 0.12) notes.push(current);
        current = {
          midi: frame.midi,
          name: midiToName(frame.midi),
          start: frame.start,
          duration: frameDuration,
          confidence: frame.confidence,
        };
      }
    }
    if (current && current.duration >= 0.12) notes.push(current);
    if (notes.length < 2) throw new Error('음정을 충분히 감지하지 못했습니다. 반주가 적은 보컬 파일을 사용해주세요.');

    const lowestMidi = Math.min(...notes.map((note) => note.midi));
    const highestMidi = Math.max(...notes.map((note) => note.midi));
    const coreLow = weightedPercentile(notes, 0.05);
    const coreHigh = weightedPercentile(notes, 0.95);
    const span = Math.max(3, highestMidi - lowestMidi + 1);
    const lowEnd = lowestMidi + span / 3;
    const middleEnd = lowestMidi + (span * 2) / 3;
    const totals = { low: 0, middle: 0, high: 0 };
    let totalDuration = 0;
    let weightedConfidence = 0;
    for (const note of notes) {
      const zone = note.midi < lowEnd ? 'low' : note.midi < middleEnd ? 'middle' : 'high';
      totals[zone] += note.duration;
      totalDuration += note.duration;
      weightedConfidence += note.confidence * note.duration;
    }

    onProgress(100);
    return {
      lowest: midiToName(lowestMidi),
      highest: midiToName(highestMidi),
      coreRange: `${midiToName(coreLow)}–${midiToName(coreHigh)}`,
      lowestMidi,
      highestMidi,
      ratios: {
        low: Math.round((totals.low / totalDuration) * 100),
        middle: Math.round((totals.middle / totalDuration) * 100),
        high: Math.round((totals.high / totalDuration) * 100),
      },
      confidence: Math.round((weightedConfidence / totalDuration) * 100),
      notes,
      duration: buffer.duration,
    };
  } finally {
    await context.close();
  }
}

function xmlEscape(value: string) {
  return value.replace(/[<>&'\"]/g, (character) => ({
    '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;',
  })[character] || character);
}

export function createMusicXml(title: string, analysis: RangeAnalysis) {
  const noteXml = analysis.notes.slice(0, 400).map((note, index) => {
    const rounded = Math.round(note.midi);
    const step = NOTE_NAMES[((rounded % 12) + 12) % 12].replace('♯', '');
    const alter = NOTE_NAMES[((rounded % 12) + 12) % 12].includes('♯') ? '<alter>1</alter>' : '';
    const octave = Math.floor(rounded / 12) - 1;
    const duration = Math.max(1, Math.min(16, Math.round(note.duration * 8)));
    return `<measure number="${index + 1}"><note><pitch><step>${step}</step>${alter}<octave>${octave}</octave></pitch><duration>${duration}</duration><voice>1</voice></note></measure>`;
  }).join('');

  return `<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
<score-partwise version="4.0"><work><work-title>${xmlEscape(title)}</work-title></work><part-list><score-part id="P1"><part-name>Vocal</part-name></score-part></part-list><part id="P1"><measure number="0"><attributes><divisions>4</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes></measure>${noteXml}</part></score-partwise>`;
}
