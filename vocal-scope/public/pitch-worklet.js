/* VocalScope 라이브 채점용 피치 워크릿.
 * 마이크 입력을 3:1로 데시메이트(≈16 kHz)한 뒤 20 ms마다 YIN(CMNDF)으로 F0를 추정해
 * { t(초, 캡처 기준), f0(Hz, 무성=0), clarity(0–1), rms }를 postMessage 한다.
 * 순수 함수는 globalThis.__vocalscopePitch로도 노출되어 Node 테스트에서 재사용된다. */

const DECIMATE = 3;      // 48 kHz → 16 kHz, 44.1 kHz → 14.7 kHz (rate는 실제 값 사용)
const WINDOW = 1024;     // 분석 창 (≈64 ms @16 kHz)
const HOP = 320;         // ≈20 ms @16 kHz
const FMIN = 70;         // ≈C#2
const FMAX = 1050;       // ≈C6
const YIN_THRESHOLD = 0.12;
const RMS_FLOOR = 1e-4;

/** YIN with cumulative-mean-normalized difference + parabolic interpolation. */
function yinPitch(buffer, rate) {
  const maxTau = Math.min(Math.floor(rate / FMIN), buffer.length - 2);
  const minTau = Math.max(2, Math.floor(rate / FMAX));
  const span = buffer.length - maxTau;
  const difference = new Float64Array(maxTau + 1);
  for (let tau = 1; tau <= maxTau; tau++) {
    let sum = 0;
    for (let i = 0; i < span; i++) {
      const delta = buffer[i] - buffer[i + tau];
      sum += delta * delta;
    }
    difference[tau] = sum;
  }
  const cmndf = new Float64Array(maxTau + 1).fill(1);
  let running = 0;
  for (let tau = 1; tau <= maxTau; tau++) {
    running += difference[tau];
    cmndf[tau] = running > 0 ? (difference[tau] * tau) / running : 1;
  }
  let tau = -1;
  for (let t = minTau; t <= maxTau; t++) {
    if (cmndf[t] < YIN_THRESHOLD) {
      while (t + 1 <= maxTau && cmndf[t + 1] < cmndf[t]) t++;
      tau = t;
      break;
    }
  }
  if (tau < 0) {
    let best = minTau;
    for (let t = minTau + 1; t <= maxTau; t++) if (cmndf[t] < cmndf[best]) best = t;
    tau = best;
  }
  // 배음 오검출 보정: 2·3배음이 강한 목소리(특히 저음)는 반주기·1/3주기 딥이 먼저 임계값을
  // 뚫어 옥타브/5도 위로 잡힌다. k·tau 근처에 "확실히 더 깊은" 국소 최솟값이 있으면 그것이
  // 진짜 주기다 (진짜 주기 tau의 배수 딥은 깊이가 비슷해서 -0.04 마진에 걸리지 않는다).
  for (const k of [2, 3]) {
    const center = tau * k;
    if (center > maxTau) continue;
    let local = center;
    for (let t = Math.max(minTau, center - 3); t <= Math.min(maxTau, center + 3); t++) {
      if (cmndf[t] < cmndf[local]) local = t;
    }
    if (cmndf[local] < cmndf[tau] - 0.04) tau = local;
  }
  let refined = tau;
  if (tau > 1 && tau < maxTau) {
    const s0 = cmndf[tau - 1];
    const s1 = cmndf[tau];
    const s2 = cmndf[tau + 1];
    const denominator = 2 * (2 * s1 - s2 - s0);
    if (Math.abs(denominator) > 1e-12) {
      const shift = (s2 - s0) / denominator;
      if (Math.abs(shift) <= 1) refined = tau + shift;
    }
  }
  return { f0: rate / refined, clarity: Math.max(0, Math.min(1, 1 - cmndf[tau])) };
}

function frameRms(buffer, from = 0, to = buffer.length) {
  let sum = 0;
  for (let i = from; i < to; i++) sum += buffer[i] * buffer[i];
  return Math.sqrt(sum / Math.max(1, to - from));
}

if (typeof globalThis !== 'undefined') {
  globalThis.__vocalscopePitch = { yinPitch, frameRms, WINDOW, HOP, DECIMATE, FMIN, FMAX };
}

if (typeof registerProcessor === 'function') {
  class VocalScopePitchProcessor extends AudioWorkletProcessor {
    constructor() {
      super();
      this.rate = sampleRate / DECIMATE;
      this.ring = new Float32Array(8192);
      this.write = 0;
      this.decimated = 0; // total decimated samples so far
      this.accumulator = 0;
      this.accumulated = 0;
      this.sinceHop = 0;
      this.scratch = new Float32Array(WINDOW);
    }

    process(inputs) {
      const channel = inputs[0] && inputs[0][0];
      if (!channel) return true;
      const length = this.ring.length;
      for (let i = 0; i < channel.length; i++) {
        this.accumulator += channel[i];
        if (++this.accumulated < DECIMATE) continue;
        this.ring[this.write] = this.accumulator / DECIMATE;
        this.write = (this.write + 1) % length;
        this.accumulator = 0;
        this.accumulated = 0;
        this.decimated++;
        if (++this.sinceHop < HOP || this.decimated < WINDOW) continue;
        this.sinceHop = 0;
        for (let k = 0; k < WINDOW; k++) {
          this.scratch[k] = this.ring[(((this.write - WINDOW + k) % length) + length) % length];
        }
        const rms = frameRms(this.scratch);
        // 창 앞/뒤 절반 중 작은 RMS: 소리가 켜지거나 꺼지는 경계 프레임(반쪽만 소리)을 걸러낸다.
        const rmsLow = Math.min(frameRms(this.scratch, 0, WINDOW / 2), frameRms(this.scratch, WINDOW / 2, WINDOW));
        const t = (this.decimated - WINDOW / 2) / this.rate; // capture-stream seconds at window centre
        if (rms < RMS_FLOOR || rmsLow < RMS_FLOOR * 0.5) {
          this.port.postMessage({ t, f0: 0, clarity: 0, rms, rmsLow });
        } else {
          const { f0, clarity } = yinPitch(this.scratch, this.rate);
          this.port.postMessage({ t, f0, clarity, rms, rmsLow });
        }
      }
      return true;
    }
  }
  registerProcessor('vocalscope-pitch', VocalScopePitchProcessor);
}
