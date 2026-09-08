# 피아노 기준음 샘플 (app/src/main/res/raw/piano_*.ogg)

`piano_<midi>.ogg` — 간편 분석 기준음용 피아노 샘플 13개 (C3=48 … C6=84, 3반음 간격).

- 출처: University of Iowa Electronic Music Studios, *Musical Instrument Samples* (Piano.ff.*.aiff),
  무료 공개 샘플. https://theremin.music.uiowa.edu/MISpiano.html
- 가공: 앞 무음 제거 → 2.0초로 자름(마지막 0.4초 페이드아웃) → 모노 44.1 kHz → OGG Vorbis q3. 개당 약 30–50 KB.
- 사용: `audio/PianoPlayer.kt` 가 가장 가까운 샘플을 SoundPool 재생 속도(±1반음)로 보간해 모든 음을 낸다.
- 재생성: `vocal-scope/docs/STEM_PITCH_IMPORT.md` 와 무관. 필요하면 위 사이트에서 같은 음을 받아 ffmpeg 로
  `-af "silenceremove=start_periods=1:start_threshold=-45dB,afade=t=out:st=1.6:d=0.4" -t 2.0 -ac 1 -ar 44100 -c:a libvorbis -q:a 3`.
