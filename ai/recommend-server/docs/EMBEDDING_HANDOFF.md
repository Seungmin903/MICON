# 음색 임베딩 붙이기 — 악보 추출 파이프라인 담당자용

악보 추출이 만든 **분리 보컬을 버리기 전에** 명령 한 줄을 끼워 넣어 주세요. 곡당 1~3초, 결과 60 KB입니다.
분리 보컬을 다시 만들려면 곡당 20초 이상 걸리므로, 이 한 줄이 있고 없고가 나중에 몇 시간 차이가 됩니다.

담당: 추천(지유림). 문제 생기면 바로 알려 주세요.

---

## 1. 한 번만 하는 설치

### 먼저 알아둘 것 — 경로는 PC마다 다릅니다

`config.json` 은 **설치한 PC의 절대경로**를 담고 있어 복사해서 쓸 수 없습니다. 아래는 유림 PC 기준 값이고, 참고용입니다.

| 항목 | 유림 PC 경로 |
|---|---|
| venv 파이썬 | `C:\Users\SSAFY\Desktop\S15P21B110-server\ai\recommend-server\.venv\Scripts\python.exe` (Python 3.11.9) |
| config.json | `…\ai\recommend-server\workspace\recommender\config.json` |
| ECAPA 체크포인트 | `…\ai\recommend-server\models\recommender\ecapa\embedding_model.ckpt` |
| Singer-BYOL 코드 | `…\ai\recommend-server\third_party\ssl-singer-identity` |
| Singer-BYOL 체크포인트 | `…\ai\recommend-server\models\recommender\singer-identity` |
| 기존 임베딩 결과 | `…\ai\recommend-server\workspace\embeddings` (855곡 중 483곡분, 1451파일 28 MB) |

`setup.ps1` 이 그 PC 기준으로 `config.json` 을 **새로 만들어 줍니다.** 경로를 손으로 고치지 마세요.

### 방법 A — 별도 venv (권장, 기존 환경 안 건드림)

```powershell
# uv 가 없으면 먼저 (1분)
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"

cd <받은 폴더>\recommend-server
powershell -ExecutionPolicy Bypass -File setup.ps1
```

`setup.ps1`: `.venv` 생성 → 의존성 설치 → ECAPA·Singer-BYOL 가중치 116 MB 다운로드 + SHA-256 검증 → `config.json` 생성.
디스크 약 3.5 GB(대부분 torch), 10~15분. GPU 있으면 자동으로 CUDA 사용.

### 방법 B — 기존 악보 추출 venv에 얹기 (가볍게, 약 100 MB)

악보 추출 환경에 이미 torch가 있으니 임베딩용 패키지 네 개만 추가하면 됩니다.

```powershell
<기존 venv>\Scripts\pip install speechbrain==1.0.3 nnAudio==0.3.2 hyperpyyaml huggingface_hub==0.33.0
<기존 venv>\Scripts\python.exe setup_recommender.py       # 가중치 다운로드 + config.json 생성
```

torch를 건드리지 않으므로 기존 파이프라인은 그대로 동작합니다. 충돌이 나면 방법 A로 가세요.

### 설치 확인

```powershell
<venv>\Scripts\python.exe -c "import json;c=json.load(open('workspace/recommender/config.json'));print(c['device'], c['ecapa_checkpoint'])"
```

---

## 2. 곡마다 실행할 한 줄

분리 보컬 경로와 곡 id만 넣으면 됩니다.

```powershell
<recommend-server>\.venv\Scripts\python.exe -m timbre_recommender embed-vocal `
  --audio  <분리보컬 wav 경로> `
  --output-prefix <출력폴더>\<song_id> `
  --ecapa-checkpoint        <recommend-server>\models\recommender\ecapa\embedding_model.ckpt `
  --singer-repo             <recommend-server>\third_party\ssl-singer-identity `
  --singer-checkpoint-root  <recommend-server>\models\recommender\singer-identity `
  --device cuda
```

세 경로는 `workspace/recommender/config.json` 에 그대로 들어 있으니 스크립트에서 읽어 쓰셔도 됩니다.

**폴더 단위로 몰아서 돌릴 때**는 이쪽이 훨씬 빠릅니다. 모델을 한 번만 올려서 곡당 1~2초입니다.

```powershell
.\.venv\Scripts\python.exe scripts\embed_vocals_folder.py `
  --vocals-root <분리보컬 루트> --pattern "*/vocals.wav" --out <출력폴더> --device cuda
```

`--pattern` 은 루트 기준 glob이고 트랙키는 상위 폴더명(파일명이 `vocals.wav` 가 아니면 파일 stem)입니다. 이미 결과가 있는 곡은 건너뛰므로 **중단 후 재실행하면 이어서** 합니다. 곡별 성공·실패가 `<출력폴더>/_log.csv` 에 남습니다.

---

## 3. 지켜야 할 규격

아래 값은 **인덱스에 기록되어 검증**됩니다. 다르면 그 곡은 카탈로그에 못 들어갑니다.
`embed-vocal` 명령을 그대로 쓰면 전부 자동으로 맞으니, 직접 전처리를 구현하지 마세요.

| 항목 | 값 | 비고 |
|---|---|---|
| 창 길이 | 4.0초 | |
| 홉 | 2.0초 (50% 겹침) | |
| 분석 샘플레이트 | 44.1 kHz 모노 | 입력이 다르면 자동 리샘플 |
| ECAPA | 192차원, 16 kHz 입력, 창별 RMS 0.1 정규화 → L2 | `speechbrain/spkrec-ecapa-voxceleb` rev `0f99f2d0…` |
| Singer-BYOL | 1000차원, 44.1 kHz 입력, 창별 peak 정규화 → L2 | `BernardoTorres/singer-identity` rev `55e09cdf…`, 코드 rev `24751d11…` |
| 곡당 조각 | 추출 시 최대 12개, 인덱스 빌드가 다시 ≤9개 균등 선택 | `--max-segments` 기본 12 유지 |
| 최소 조각 | 5개 미만이면 실패 처리 | 아래 "실패" 참고 |
| 체크포인트 | SHA-256 고정 | 다르면 추출 중단 |

**입력은 반드시 분리된 보컬입니다.** 원곡(반주 섞인 믹스)을 넣으면 반주·녹음 환경이 임베딩을 오염시킵니다.
실측으로 같은 곡의 무반주 대비 음색 점수가 0.61 → 0.49로 떨어졌습니다.

---

## 4. 출력물과 파일 이름

한 곡당 세 파일이 생깁니다.

```
<song_id>.ecapa.npy         (조각수, 192) float32
<song_id>.singer_byol.npy   (조각수, 1000) float32
<song_id>.segments.json     조각 시각·모델 리비전·체크포인트 해시 등 provenance
```

**파일 이름은 `song_id` 로 맞춰 주세요.** 악보 추출 job id(`tj-<TJ번호>`)와 같은 값이면 됩니다.
이 id로 음역 프로파일·곡 메타와 조인하므로, 다른 규칙을 쓰시면 매칭 작업이 따로 생깁니다.

---

## 5. 저와 함께 주셨으면 하는 것

1. 위 세 파일 (곡당 60 KB, 890곡이면 약 53 MB)
2. **`job.json`** — 이미 만드시는 산출물입니다. 이 안의 `result.notes`(midi·duration·confidence)로 제가 음역 프로파일을 GPU 없이 만듭니다. 따로 뽑아 주실 필요 없습니다.

분리 보컬 자체는 안 주셔도 됩니다. 위 두 개만 있으면 됩니다.

---

## 6. 실패했을 때

`min_segments 5` 를 못 채우면 그 곡은 실패로 기록되고 다음 곡으로 넘어갑니다(`--continue-on-error` 없이도 폴더 스크립트는 계속 진행).
실제로 483곡 중 2곡이 이 사유로 빠졌습니다. 듀엣이거나 보컬 구간이 짧은 곡입니다.

- 곡이 4초보다 짧음 → 정상적으로 불가
- 유성 구간이 부족 → 분리 품질 문제일 수 있음. 곡 id만 알려 주시면 제가 확인합니다

임계값을 임의로 낮추지 마세요. 조각이 부족한 임베딩은 추천 품질을 떨어뜨립니다.

---

## 7. 분리 모델 — 혼재해도 됩니다 (A/B 검증 완료, 2026-09-09)

기존 카탈로그 855곡은 **htdemucs** 보컬로, 악보 추출 파이프라인은 **Mel-Band RoFormer** (`separation.py` 기본값)로 분리합니다.
분리기가 다르면 임베딩이 이동할 수 있어 같은 곡을 두 분리기로 나눠 임베딩해 비교했습니다(6곡).

| | 같은 곡, 분리기만 다름 | 다른 곡끼리 (기준선) |
|---|---|---|
| ECAPA | 평균 0.952 (0.878~0.986) | 평균 0.581 |
| Singer-BYOL | 평균 0.967 (0.946~0.995) | 평균 0.519 |

같은 곡은 분리기가 달라도 0.95 안팎으로 붙고, 다른 곡과의 거리(0.5~0.6)보다 훨씬 가깝습니다.
**분리기를 통일하지 않아도 순위가 뒤집히지 않습니다. 파이프라인 기본값(Mel-Band RoFormer)을 그대로 쓰세요.**

주의할 점 하나: 윤하 '사건의 지평선'이 ECAPA 0.878로 가장 낮았습니다. 코러스·더블링이 많은 곡은 분리기 차이가 더 크게 남습니다.
추천 결과가 이상한 곡이 나오면 이 요인을 먼저 의심하시고 곡 id를 알려 주세요.
