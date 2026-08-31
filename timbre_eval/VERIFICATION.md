# 음색 임베딩 실험 검증 가이드

목적: 이 폴더의 실험이 틀리지 않았는지 **독립적으로** 확인한다.
보고서(주장 목록): 아티팩트 링크는 지유림에게 요청.

## 0. 환경

- Python 3.11, NVIDIA GPU(8GB+) 권장. GPU 없으면 3단계(분석층 검증)만 가능.
- 설치:
  ```
  python -m venv .venv
  .venv\Scripts\pip install torch torchaudio torchvision --index-url https://download.pytorch.org/whl/cu126
  .venv\Scripts\pip install demucs speechbrain transformers librosa soundfile matplotlib nnAudio muq ns3codec flask imageio-ffmpeg
  git clone --depth 1 https://github.com/SonyCSLParis/ssl-singer-identity timbre_eval/ssl-singer-identity
  ```

## 1. 전체 재현 (GPU 필요, ~1시간)

실행 순서 (프로젝트 루트에서):
```
python timbre_eval/prepare.py                      # mp3 -> 44.1kHz wav
python -m demucs --two-stems=vocals -n htdemucs -o timbre_eval/separated timbre_eval/wav/*.wav
python timbre_eval/evaluate.py --models ecapa      # 모델별 1회씩 (별도 프로세스 권장)
python timbre_eval/evaluate.py --models singer_id
python timbre_eval/evaluate.py --models wavlm
python timbre_eval/evaluate.py --models mert
python timbre_eval/analyze.py                      # 트랙쌍·동성·역전 분석
python timbre_eval/env_analysis.py                 # 환경 분해 (아이유 4버전)
python timbre_eval/b_analysis.py                   # 스타일/체인 분석 (제이플라·솔라)
python timbre_eval/domain_gap.py                   # 폰 녹음 랭킹
python timbre_eval/sensitivity.py                  # 전처리 v2 민감도
python timbre_eval/muq30.py                        # MuQ 30초 반론 검증
```
주의: 폰 녹음(지유림)은 m4a라 prepare가 건너뜀 — imageio-ffmpeg로 wav 변환 후 timbre_eval/wav/지유림__밤편지.wav로 두고 demucs에 포함시킬 것.

## 2. 대조할 기대값 (재현 성공 기준)

소수 둘째 자리까지 일치하면 재현 성공 (청크 경계·라이브러리 버전에 따라 ±0.01 허용):

| 항목 | 기대값 |
|---|---|
| ECAPA 커버 벤치마크 | same_singer≈0.415, cover≈0.288, EER≈0.287 |
| MERT 커버 벤치마크 | 간격 ≈ +0.010 (구분 못함) |
| 환경 분해(ECAPA) | 환경쌍≈0.466 > 아이유 곡간≈0.412 > 타인≈0.320 |
| B실험(ECAPA) | 제이플라 곡간≈0.469, 솔라 곡간≈0.307, 여-여 타인≈0.266 |
| 폰 녹음 랭킹(ECAPA) | 경서 > 아이유 4버전 > 웬디·설윤 > 남성 3인 |
| singer_id 환경쌍 | 라이브1↔콘서트 ≈0.31 vs 타인 설윤 ≈0.77 (환경이 정체성 압도 = 탈락 근거) |
| MuQ 30초 | 간격 음수(≈ -0.03) |

## 3. GPU 없이: 분석층만 검증

`emb_cache/`(v1)·`emb_cache_v2/`(최종사양)에 임베딩이 저장돼 있음.
analyze.py, b_analysis.py, env_analysis.py, domain_gap.py는 캐시만 읽으므로 CPU로 수 초.
이 경로는 "임베딩이 주어졌을 때 지표 계산이 맞는가"만 검증함 (전처리·임베딩 자체는 검증 안 됨).

## 4. 코드 리뷰 포인트 (수치 재현과 별개로 로직을 의심할 곳)

- evaluate.py `load_chunks()`: 5초 비중첩 절단, 문턱(상위10% RMS×0.15), 창 합격 기준 60% — v1은 "앞 24개" 편향 있음(알려진 이슈, sensitivity.py의 v2가 수정판이며 결론 불변 확인됨)
- evaluate.py `eer()`: 문턱 스윕 구현이 맞는지
- analyze.py 역전 정의: 가수별 기준선(각자 곡간 최솟값) 방식이 타당한지
- 유사도 = 청크쌍 코사인 평균 — 트랙 평균 벡터 코사인과 다른 값임 (보고서 수치는 전자)

## 5. 사람만 할 수 있는 검증 (코드 밖 — 가장 중요)

- [ ] data/ 파일 라벨 검증: 폴더의 가수가 실제 그 가수 맞는지, AI 커버/리마스터 아닌지 (실험자는 검증 못 했음)
- [ ] 웬디 '그런일은'·'이 밤이 지나면' 출처: 스튜디오 음원인지 라이브 클립인지 (같은가수 기준선 0.297의 원인 추정과 직결)
- [ ] separated/*/vocals.wav 몇 개 청취: 원곡 스템에 화음·더블링 잔류 여부 (미해결 반론 #4)
- [ ] 블라인드 청취 3종: ①지유림 폰 녹음이 경서·아이유 계열로 들리는가 ②이영현이 아이유계와 다른 계열로 들리는가 ③솔라 커버 3곡의 창법이 실제로 곡마다 다른가

## 알려진 한계 (검증자가 재발견할 필요 없는 것)

- 표본 소규모: 개별 소수값은 신뢰구간 없음, 방향·순위만 결론 근거
- EER: 청크 상관 때문에 절대값 인용 금지, 모델 간 상대 비교 전용
- 피치 조작 실험: 단순 시프트라 포먼트 동반 이동 — 모델 간 상대 비교만 유효
- ECAPA 교차 음역대 유사도는 피치와 상관 높음(ρ≈-0.95) — 동성/동음역 비교 위주 설계로 관리
- ECAPA 가중치는 VoxCeleb 학습 — 상용 배포 전 라이선스 검토 필요
