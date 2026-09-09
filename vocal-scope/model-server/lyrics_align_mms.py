"""Multilingual forced alignment for non-Korean lyrics (Japanese, English, mixed): torchaudio ``MMS_FA`` + ``uroman``.

`lyrics_engine.align_lyrics` uses a Korean wav2vec2 whose vocabulary is Hangul syllables, so Japanese/English text
collapses into ``[UNK]`` runs with meaningless timing.  Meta's MMS forced aligner (1,100+ languages) works on romanized
text instead: every unit — one kana/kanji/Hangul character or one Latin word — is romanized with uroman, lowercased and
reduced to ``a-z'``, then aligned as one CTC "word".  The unit's character span is what the app fills as one syllable.

The whole stem is encoded in ~20 s chunks (same energy-minimum cuts as lyrics_engine), the per-chunk log-probabilities
are concatenated on the shared 20 ms frame grid and one global forced alignment is run on CPU.
Returns the same ``{"syllables": [...], "lines": [...], "score", "tokenCount", "unknownTokenCount", "explicitExtensions"}``
shape as `align_lyrics` so `karaoke.build_karaoke_notes` consumes it unchanged.
"""

from __future__ import annotations

import gc
import re
import unicodedata
from pathlib import Path
from typing import Any

import librosa
import numpy as np
import torch

from lyrics_engine import CONTINUATION_MARKS, SAMPLE_RATE, _chunk_bounds

FRAME_STRIDE = 320      # 20 ms @ 16 kHz (wav2vec2 conv stack)
FRAME_RECEPTIVE = 400
ROMAN_KEEP = re.compile(r"[^a-z']+")
_STATE: tuple[Any, Any, Any] | None = None
_UROMAN: Any = None


def is_cjk(ch: str) -> bool:
    code = ord(ch)
    return (
        0xAC00 <= code <= 0xD7A3 or 0x1100 <= code <= 0x11FF or 0x3130 <= code <= 0x318F      # Hangul
        or 0x3040 <= code <= 0x30FF or 0x31F0 <= code <= 0x31FF or 0xFF66 <= code <= 0xFF9F   # kana
        or 0x4E00 <= code <= 0x9FFF or 0x3400 <= code <= 0x4DBF                                # kanji / CJK ideographs
    )


def _is_word_char(ch: str) -> bool:
    return ch.isalnum() or ch in {"'", "’"}


def _load(device: str) -> tuple[Any, Any, Any]:
    global _STATE
    if _STATE is None:
        from torchaudio.pipelines import MMS_FA as bundle

        model = bundle.get_model(with_star=False).eval()
        _STATE = (model, bundle.get_tokenizer(), bundle.get_aligner())
    model, tokenizer, aligner = _STATE
    model.to(device)
    return model, tokenizer, aligner


def _romanize(text: str) -> str:
    global _UROMAN
    if _UROMAN is None:
        import uroman

        _UROMAN = uroman.Uroman()
    romanized = _UROMAN.romanize_string(text)
    romanized = unicodedata.normalize("NFKD", romanized).encode("ascii", "ignore").decode("ascii").lower()
    return ROMAN_KEEP.sub("", romanized.replace("’", "'"))


def tokenize_units(text: str) -> tuple[list[dict[str, Any]], list[str]]:
    """Lines → alignment units. CJK: one unit per character; Latin/digits: one unit per word. Returns (units, romanized)."""
    units: list[dict[str, Any]] = []
    romanized: list[str] = []
    line_index = -1
    for raw_line in text.replace("\r", "").split("\n"):
        words = [w for w in re.split(r"\s+", raw_line.strip()) if w]
        if not words:
            continue
        line_index += 1
        for word in words:
            position = 0
            first_in_word = True
            while position < len(word):
                ch = word[position]
                if ch in CONTINUATION_MARKS:
                    if units and not first_in_word:
                        units[-1]["explicitExtend"] = True
                    position += 1
                    continue
                if is_cjk(ch):
                    unit_text, position = ch, position + 1
                elif _is_word_char(ch):
                    end = position
                    while end < len(word) and _is_word_char(word[end]) and not is_cjk(word[end]):
                        end += 1
                    unit_text, position = word[position:end], end
                else:
                    position += 1  # punctuation
                    continue
                roman = _romanize(unit_text)
                if not roman:
                    continue
                units.append({"text": unit_text, "line": line_index, "wordStart": first_in_word, "known": True, "explicitExtend": False})
                romanized.append(roman)
                first_in_word = False
    return units, romanized


def _emissions(audio: np.ndarray, model: Any, device: str) -> tuple[torch.Tensor, np.ndarray]:
    chunks: list[torch.Tensor] = []
    frame_times: list[np.ndarray] = []
    try:
        with torch.inference_mode():
            for start, end in _chunk_bounds(audio):
                waveform = torch.from_numpy(audio[start:end]).unsqueeze(0).to(device)
                emission, _ = model(waveform)
                log_probs = torch.log_softmax(emission[0].float(), dim=-1).cpu()
                chunks.append(log_probs)
                frames = log_probs.shape[0]
                frame_times.append((start + np.arange(frames) * FRAME_STRIDE + FRAME_RECEPTIVE / 2) / SAMPLE_RATE)
    finally:
        model.to("cpu")
        gc.collect()
        if device == "cuda" and torch.cuda.is_available():
            torch.cuda.empty_cache()
    return torch.cat(chunks, dim=0), np.concatenate(frame_times)


def align_lyrics_mms(vocals_path: Path, text: str, device: str) -> dict[str, Any]:
    """Forced-align lyric text (any language) to the vocal stem. Same return shape as lyrics_engine.align_lyrics."""
    units, romanized = tokenize_units(text)
    empty = {"syllables": [], "lines": [], "score": 0.0, "tokenCount": 0, "unknownTokenCount": 0, "explicitExtensions": 0, "aligner": "mms-fa"}
    if not units:
        return empty
    model, tokenizer, aligner = _load(device)
    audio = librosa.load(str(vocals_path), sr=SAMPLE_RATE, mono=True)[0].astype(np.float32)
    log_probs, frame_times = _emissions(audio, model, device)
    token_ids = tokenizer(romanized)
    total_tokens = sum(len(t) for t in token_ids) + len(token_ids)
    if log_probs.shape[0] < total_tokens + 2:
        raise RuntimeError("가사가 음원 길이에 비해 너무 깁니다 (MMS).")
    spans = aligner(log_probs, token_ids)   # list (per unit) of TokenSpan lists
    if len(spans) != len(units):
        raise RuntimeError(f"MMS 정렬 단위 수 불일치: {len(spans)} != {len(units)}")

    syllables: list[dict[str, Any]] = []
    last_frame = len(frame_times) - 1
    for unit, unit_spans in zip(units, spans):
        if not unit_spans:
            continue
        start_frame = int(unit_spans[0].start)
        end_frame = int(unit_spans[-1].end)
        score = float(np.mean([float(s.score) for s in unit_spans]))
        syllables.append({
            "text": unit["text"],
            "line": unit["line"],
            "wordStart": unit["wordStart"],
            "known": True,
            "explicitExtend": unit["explicitExtend"],
            "start": round(float(frame_times[min(start_frame, last_frame)]), 3),
            "end": round(float(frame_times[min(end_frame, last_frame)]), 3),
            "score": round(score, 3),
        })
    lines: list[str] = []
    for unit in units:
        while len(lines) <= unit["line"]:
            lines.append("")
        lines[unit["line"]] = (lines[unit["line"]] + (" " if unit["wordStart"] and lines[unit["line"]] else "") + unit["text"])
    mean_score = float(np.mean([s["score"] for s in syllables])) if syllables else 0.0
    return {
        "syllables": syllables,
        "lines": lines,
        "score": round(mean_score, 3),
        "tokenCount": len(syllables),
        "unknownTokenCount": 0,
        "explicitExtensions": sum(1 for u in units if u["explicitExtend"]),
        "aligner": "mms-fa",
    }
