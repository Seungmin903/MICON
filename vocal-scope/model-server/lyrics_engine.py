"""Lyrics timing: optional Whisper ASR for the text, wav2vec2-CTC forced alignment for syllables.

- Text comes from the user (preferred) or from Whisper ("turbo") run on the separated vocal stem.
- Alignment uses ``kresnik/wav2vec2-large-xlsr-korean`` whose vocabulary is 1,202 Hangul syllables plus
  the word delimiter ``|``; every Hangul syllable therefore becomes one CTC token with its own time span.
  Non-Hangul runs (Latin, digits) are aligned as a single ``[UNK]`` token so the surrounding syllables stay
  in order; their timing is approximate.
- The whole stem is encoded in ~20 s chunks cut at energy minima, the log-probabilities are concatenated
  on the shared 20 ms frame grid, and one global forced alignment is run on CPU.
"""

from __future__ import annotations

import gc
import re
from pathlib import Path
from typing import Any

import librosa
import numpy as np
import torch

ALIGN_MODEL = "kresnik/wav2vec2-large-xlsr-korean"
WHISPER_MODEL = "turbo"
SAMPLE_RATE = 16000
CHUNK_SECONDS = 20.0
CHUNK_SEARCH_SECONDS = 4.0
FRAME_STRIDE = 320  # wav2vec2 conv stack: 20 ms
FRAME_RECEPTIVE = 400
HANGUL = re.compile(r"[가-힣]")
LATIN_DIGIT = re.compile(r"[A-Za-z0-9]+")
CONTINUATION_MARKS = {"~", "～", "_", "—", "–"}

_ALIGNER: tuple[Any, Any, Any] | None = None


# ----------------------------------------------------------------------------- text

def normalize_lyrics(text: str) -> list[list[str]]:
    """Lines → words. Keep alignable text plus explicit held-syllable markers."""
    lines: list[list[str]] = []
    for raw_line in text.replace("\r", "").split("\n"):
        words = [word for word in re.split(r"\s+", raw_line.strip()) if word]
        cleaned: list[str] = []
        for word in words:
            kept = "".join(
                "~" if ch in CONTINUATION_MARKS else ch
                for ch in word
                if HANGUL.match(ch) or LATIN_DIGIT.match(ch) or ch in CONTINUATION_MARKS or ch in {"-", "'", "’"}
            )
            if kept and any(HANGUL.match(ch) or LATIN_DIGIT.match(ch) for ch in kept):
                cleaned.append(kept)
        if cleaned:
            lines.append(cleaned)
    return lines


def _tokenize(lines: list[list[str]], vocab: dict[str, int], unk_id: int, delimiter_id: int) -> tuple[list[int], list[dict[str, Any]]]:
    """Returns CTC target ids and, for each non-delimiter token, its display metadata."""
    targets: list[int] = []
    tokens: list[dict[str, Any]] = []
    for line_index, words in enumerate(lines):
        for word_index, word in enumerate(words):
            if targets:
                targets.append(delimiter_id)
            position = 0
            word_token_start = len(tokens)
            while position < len(word):
                ch = word[position]
                if ch in CONTINUATION_MARKS or (ch == "-" and position == len(word) - 1):
                    if len(tokens) > word_token_start:
                        tokens[-1]["explicitExtend"] = True
                    position += 1
                    continue
                if HANGUL.match(ch):
                    targets.append(vocab.get(ch, unk_id))
                    tokens.append({"text": ch, "line": line_index, "wordStart": len(tokens) == word_token_start, "known": ch in vocab, "explicitExtend": False})
                    position += 1
                    continue
                match = LATIN_DIGIT.match(word, position)
                if match:
                    run = match.group(0)
                    targets.append(unk_id)
                    tokens.append({"text": run, "line": line_index, "wordStart": len(tokens) == word_token_start, "known": False, "explicitExtend": False})
                    position += len(run)
                    continue
                # Apostrophes and internal hyphens are visual separators, not CTC targets.
                position += 1
    return targets, tokens


# ----------------------------------------------------------------------------- models

def _load_aligner() -> tuple[Any, Any, Any]:
    global _ALIGNER
    if _ALIGNER is None:
        from transformers import Wav2Vec2CTCTokenizer, Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC

        tokenizer = Wav2Vec2CTCTokenizer.from_pretrained(ALIGN_MODEL)
        extractor = Wav2Vec2FeatureExtractor.from_pretrained(ALIGN_MODEL)
        model = Wav2Vec2ForCTC.from_pretrained(ALIGN_MODEL).eval()
        _ALIGNER = (tokenizer, extractor, model)
    return _ALIGNER


def _release(device: str) -> None:
    gc.collect()
    if device == "cuda" and torch.cuda.is_available():
        torch.cuda.empty_cache()


def transcribe_lyrics(vocals_path: Path, device: str) -> dict[str, Any]:
    """Whisper on the vocal stem → lyric lines. Segments that look hallucinated are dropped."""
    import whisper

    audio = librosa.load(str(vocals_path), sr=SAMPLE_RATE, mono=True)[0].astype(np.float32)
    model = whisper.load_model(WHISPER_MODEL, device=device)
    try:
        result = model.transcribe(
            audio,
            language="ko",
            task="transcribe",
            fp16=device == "cuda",
            temperature=0.0,
            beam_size=5,
            condition_on_previous_text=False,
            no_speech_threshold=0.6,
            compression_ratio_threshold=2.4,
        )
    finally:
        del model
        _release(device)
    lines: list[str] = []
    for segment in result.get("segments", []):
        text = segment.get("text", "").strip()
        if not text or not HANGUL.search(text):
            continue
        if segment.get("no_speech_prob", 0.0) > 0.7 or segment.get("avg_logprob", 0.0) < -1.2:
            continue
        if lines and lines[-1] == text:  # repeated hallucination
            continue
        lines.append(text)
    return {"text": "\n".join(lines), "segments": len(lines)}


# ----------------------------------------------------------------------------- alignment

def _chunk_bounds(audio: np.ndarray) -> list[tuple[int, int]]:
    """~20 s chunks whose cut points sit on the quietest 10 ms frame within ±4 s of the nominal boundary."""
    total = len(audio)
    hop = 160
    rms = librosa.feature.rms(y=audio, frame_length=640, hop_length=hop, center=True)[0]
    bounds: list[int] = [0]
    nominal = int(CHUNK_SECONDS * SAMPLE_RATE)
    search = int(CHUNK_SEARCH_SECONDS * SAMPLE_RATE)
    cursor = nominal
    while cursor < total - nominal // 2:
        lo = max(bounds[-1] + nominal // 2, cursor - search) // hop
        hi = min(total, cursor + search) // hop
        frame = int(lo + np.argmin(rms[lo:hi])) if hi > lo else cursor // hop
        cut = frame * hop
        bounds.append(cut)
        cursor = cut + nominal
    bounds.append(total)
    return [(a, b) for a, b in zip(bounds, bounds[1:]) if b - a > FRAME_RECEPTIVE]


def _emissions(audio: np.ndarray, device: str) -> tuple[torch.Tensor, np.ndarray, int]:
    tokenizer, extractor, model = _load_aligner()
    model.to(device)
    chunks: list[torch.Tensor] = []
    frame_times: list[np.ndarray] = []
    try:
        with torch.inference_mode():
            for start, end in _chunk_bounds(audio):
                features = extractor(audio[start:end], sampling_rate=SAMPLE_RATE, return_tensors="pt")
                logits = model(features.input_values.to(device)).logits[0]
                log_probs = torch.log_softmax(logits.float(), dim=-1).cpu()
                chunks.append(log_probs)
                frames = log_probs.shape[0]
                frame_times.append((start + np.arange(frames) * FRAME_STRIDE + FRAME_RECEPTIVE / 2) / SAMPLE_RATE)
    finally:
        model.to("cpu")
        _release(device)
    return torch.cat(chunks, dim=0), np.concatenate(frame_times), int(tokenizer.pad_token_id)


def _spans_from_alignment(alignment: np.ndarray, scores: np.ndarray, blank: int, expected: int) -> list[tuple[int, int, float]]:
    """Collapse frame labels into (start_frame, end_frame_exclusive, mean_prob) per emitted non-blank token."""
    spans: list[tuple[int, int, float]] = []
    index = 0
    length = len(alignment)
    while index < length:
        token = alignment[index]
        if token == blank:
            index += 1
            continue
        end = index
        while end < length and alignment[end] == token:
            end += 1
        spans.append((index, end, float(np.mean(scores[index:end]))))
        index = end
    if len(spans) != expected:
        raise RuntimeError(f"가사 정렬 토큰 수 불일치: {len(spans)} != {expected}")
    return spans


def align_lyrics(vocals_path: Path, text: str, device: str) -> dict[str, Any]:
    """Forced-align lyric text to the vocal stem. Returns syllables with seconds and a mean confidence."""
    import torchaudio.functional as F

    lines = normalize_lyrics(text)
    if not lines:
        return {"syllables": [], "lines": [], "score": 0.0, "tokenCount": 0, "unknownTokenCount": 0, "explicitExtensions": 0}

    tokenizer, _, _ = _load_aligner()
    vocab = tokenizer.get_vocab()
    unk_id = int(tokenizer.unk_token_id)
    delimiter_id = int(vocab.get(tokenizer.word_delimiter_token, unk_id))
    targets, tokens = _tokenize(lines, vocab, unk_id, delimiter_id)
    if not tokens:
        return {"syllables": [], "lines": [], "score": 0.0, "tokenCount": 0, "unknownTokenCount": 0, "explicitExtensions": 0}

    audio = librosa.load(str(vocals_path), sr=SAMPLE_RATE, mono=True)[0].astype(np.float32)
    log_probs, frame_times, blank = _emissions(audio, device)
    if log_probs.shape[0] < len(targets) * 2 + 2:
        raise RuntimeError("가사가 음원 길이에 비해 너무 깁니다.")

    target_tensor = torch.tensor(targets, dtype=torch.int32).unsqueeze(0)
    alignment, frame_scores = F.forced_align(log_probs.unsqueeze(0), target_tensor, blank=blank)
    spans = _spans_from_alignment(alignment[0].numpy(), frame_scores[0].exp().numpy(), blank, len(targets))

    syllables: list[dict[str, Any]] = []
    token_cursor = 0
    for target, (start, end, score) in zip(targets, spans):
        if target == delimiter_id:  # word boundary token: no display
            continue
        meta = tokens[token_cursor]
        token_cursor += 1
        syllables.append({
            "text": meta["text"],
            "line": meta["line"],
            "wordStart": meta["wordStart"],
            "known": meta["known"],
            "explicitExtend": meta["explicitExtend"],
            "start": round(float(frame_times[start]), 3),
            "end": round(float(frame_times[min(end, len(frame_times) - 1)]), 3),
            "score": round(score, 3),
        })
    mean_score = float(np.mean([s["score"] for s in syllables])) if syllables else 0.0
    return {
        "syllables": syllables,
        "lines": [" ".join(words) for words in lines],
        "score": round(mean_score, 3),
        "tokenCount": len(syllables),
        "unknownTokenCount": sum(1 for item in tokens if not item["known"]),
        "explicitExtensions": sum(1 for item in tokens if item["explicitExtend"]),
    }
