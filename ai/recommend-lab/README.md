# MICON 추천 실험실

운영 AI와 별도 프로세스로 실행되는 읽기 전용 내부 도구입니다. 변경 파일은 모두 `ai/recommend-lab/`에 있습니다. 운영 추천 점수 계산과 기본값 선택은 `../recommend-server/timbre_recommender`를 직접 사용합니다. 운영 API 모듈은 import하지 않습니다.

## 기능

- ECAPA와 Singer-BYOL 곡별 2차원 지도, 가수·곡 hover, 같은 가수 강조, 확대·이동
- 새 요청 목록 10초 갱신, 요청 점 표시 선택, 요청별 상세 상태
- 모델별 전체 후보 음색 순위, 게이트 탈락 회색 표시, 복수 탈락 조건과 실제 수치
- 고정 입력 기본값과 후보 설정 비교, 최종 추천과 Top K 교집합, 벡터 없는 결과 JSON 저장
- job-id 조회, 컴포넌트 분석 상태와 콜백 수락·HTTP 상태 분리, 실패 코드·사유 표시
- 실패 원문이 만료된 경우 명시하고, 보존된 메타데이터로 확인한 조건 위반은 별도 진단 후보로 표시

지도는 정규화된 구간 평균의 PCA입니다. 이전 t-SNE 그림과 좌표가 같지 않습니다. 새 요청을 같은 좌표계에 transform할 수 있도록 PCA를 선택했습니다. 지도 거리와 실제 구간 비교 추천 점수는 다릅니다. 같은 가수 강조는 display artist 문자열 기준이며 canonical artist 매핑이나 정확도 지표가 아닙니다.

## 설치와 합성 데모

Python 3.11 이상. 저장소 루트에서 실행합니다. 아래 데이터는 실제 사용자 목소리가 아닙니다.

```powershell
python -m venv ai/recommend-lab/.venv
ai/recommend-lab/.venv/Scripts/python.exe -m pip install -r ai/recommend-lab/requirements.txt
ai/recommend-lab/.venv/Scripts/python.exe ai/recommend-lab/demo.py --output ai/recommend-lab/data/demo
$env:LAB_DEV_MODE = '1'
$env:LAB_INDEX_DIR = (Resolve-Path 'ai/recommend-lab/data/demo/catalog').Path
$env:LAB_ANALYSIS_DIR = (Resolve-Path 'ai/recommend-lab/data/demo/analysis').Path
$env:LAB_CASES_DIR = (Resolve-Path 'ai/recommend-lab/data/demo/cases').Path
ai/recommend-lab/.venv/Scripts/python.exe ai/recommend-lab/app.py
```

접속: http://127.0.0.1:8790/ . 개발 모드는 localhost만 허용하고 forwarded 헤더도 거절합니다. 앱 runner는 개발 모드에서 loopback에만 bind합니다. 개발 모드로 프록시 연결 또는 팀 배포하지 마세요. `/health`의 OK는 실험실 프로세스 생존이며 운영 AI 또는 모델·콜백의 정상 상태를 보장하지 않습니다.

## 실제 카탈로그와 요청 데이터 연결

환경변수는 **실험실 프로세스에만** 설정합니다. 운영 AI 설정을 바꾸거나 재시작할 필요가 없습니다.

| 환경변수 | 용도 |
| --- | --- |
| LAB_INDEX_DIR | index.json, ecapa.npy, singer_byol.npy, range_profiles.json이 있는 현재 배치 디렉터리 |
| LAB_ANALYSIS_DIR | 분석 job별 job.json 및 아직 만료되지 않은 callback 파일의 읽기 전용 디렉터리 |
| LAB_RECOMMEND_JOBS_DIR | 추천 job별 job.json 읽기 전용 디렉터리 |
| LAB_CASES_DIR | 동의받은 고정 입력 case JSON 디렉터리 |
| LAB_PORT | 기본 8790 |
| LAB_HOST | 인증 모드 bind 주소 기본 127.0.0.1 |
| LAB_USERNAME, LAB_PASSWORD | 팀 인증 사용자와 12자 이상 비밀번호 |
| LAB_DEV_MODE | 로컬 전용 1 인증 우회 개발 모드 |

카탈로그 array hash와 range provenance hash를 시작 시 확인합니다. index 및 추천 core fingerprint를 화면과 보고서에 표시합니다. 배치 변경 후에는 실험실 프로세스를 재시작해 새 지도 좌표계를 생성해야 합니다. 사용자 임베딩 두 모델은 기존 모델 계약에 맞춰 생성된 자료만 연결하세요. 차원만 같다고 새 모델과 기존 모델을 혼용할 수는 없습니다.

일반 TIMBRE 분석의 진단 재생은 동일 sessionId의 RANGE 콜백만 결합합니다. 시간상 가까운 요청이나 다른 session의 음역을 섞지 않습니다. 실제 추천 당시 BE voiceProfile, 취향, 제외 원곡이 동일하다는 보장은 없으므로 **당시 추천의 정확한 재현이라고 표시하지 않습니다**. 동일 session에 RANGE가 복수이면 자동 결합하지 않습니다. 음역 입력이 없거나 불신뢰이면 기본값은 운영과 같은 음색 단독 fallback입니다.

추천 job.json에는 원래 query 벡터·histogram이 저장되지 않으므로 과거 추천 job을 ID만으로 정확히 재생할 수 없습니다. 당시 저장된 추천 목록은 상태 상세에 표시합니다. 원본이 만료됐으면 임의로 복구하거나 새 데이터로 과거 결과를 대체하지 않습니다. 운영의 음성 자동 삭제 정책은 변경하지 않습니다.

## 동의받은 고정 입력 case 계약

`LAB_CASES_DIR/<안전한영문ID>.json`을 읽습니다. 예시는 합성 데모 생성 파일을 참고하세요.

```text
schemaVersion: 1
consented: true
catalogFingerprint: 화면 /api/status의 정확한 catalogFingerprint
inputKind: saved-case 또는 synthetic-demo
label: 입력 설명
capturedAt: 기록 시각
query: {ecapa: 1~12개 192차원 구간, singer_byol: 1~12개 1000차원 구간}
pitchProfile: 운영 추천에 사용한 schemaVersion 1 전체 프로파일 또는 null
genres: 당시 언어 및 취향 선택 목록
excludedTrackIds: 당시 제외 원곡 및 제외 곡 ID 목록
mode: timbre 또는 range
topK: 당시 요청 값 1~50
```

이 값들은 백엔드가 AI에 보냈던 동일 입력을 테스트 환경에서 명시적으로 확보한 뒤 변환해야 합니다. case를 자동 수집하거나 운영 DB에 저장하지 않습니다. 모델 버전·preprocessing, profile 버전, 원 추천 job-id와 code revision도 별도 기록해 주세요. 동의·보관 기간·삭제 담당자를 정하고 보존하세요. 원본 case, 녹음, 임베딩, 계정 비밀은 Git에 commit하지 않습니다. 브라우저에는 2D 좌표와 점수·사유만 전송합니다. case schema version과 입력 model provenance 자동 대조는 추가 강화 대상입니다.

## 실험 설정 범위

현재 후보 설정은 ECAPA 혼합 비중, 평균·구간 비중, 음색·음역 최종 비중, 최대 키 변화, 핵심 음역 초과 허용, 최소 음역 점수, 곡 프로파일 신뢰도, 음역 게이트 활성화, Top K입니다. 기본값은 production `build_shelves`를 사용하고 후보는 같은 `score_all_tracks` + `compose_two_axis`에 검증된 별도 gate 정책을 적용합니다. 전역 상수를 monkeypatch하지 않습니다. 후보 정책이 맞다고 판단되면 **별도의 운영 코드 변경과 회귀 테스트 및 MR**이 필요합니다. 실험 실행으로 운영 설정이 자동 적용되지 않습니다.

전체 모델 순위는 각 모델 음색 점수 내림차순입니다. 최종 추천은 게이트 후 두 축 quota 선택 + final_score 정렬입니다. 최적 키는 먼저 -6~+5 범위에서 계산한 값이며, 현재 후보 설정도 그 최적 키를 gate로 확인합니다. ±3 안에서 대체 키를 다시 최적화하는 기능은 아닙니다. 성별 필터나 정확도 자동 판정도 없습니다. 탈락 여부와 Top K 미선택을 혼동하지 마세요.

## 테스트

```powershell
ai/recommend-lab/.venv/Scripts/python.exe -m pip install httpx
ai/recommend-lab/.venv/Scripts/python.exe -m unittest discover -s ai/recommend-lab/tests -v
```

인증·경로 제한·설정 검증·동일 기본값의 production 일치·탈락 사유·상태와 콜백 구분·만료 사유·벡터 비노출·입력 파일 불변을 검사합니다. 합성 단위 테스트 통과는 실제 음색 정확도나 사람의 노래 적합도 검증이 아닙니다.

## 기존 EC2 배포 준비

Dockerfile과 compose 예시는 이 폴더 안에 있습니다. 저장소 루트를 build context로 사용해야 형제 production core가 포함됩니다. EC2 팀 배포 전에는 `LAB_DEV_MODE`를 반드시 끄고 HTTPS 및 팀 인증을 적용하세요. 기본 인증은 단일 내부 팀 계정용 초기 접근 제어이며 개인별 계정·권한·감사 추적·로그아웃 기능은 아직 없습니다. 운영 전 보안 정책에 맞춰 접근 제어를 결정하세요.

현재 운영 AI는 Windows 머신에 있습니다. EC2는 그 로컬 job 폴더를 직접 읽을 수 없습니다. 따라서 팀 배포에서는 승인된 읽기 전용 데이터 연결 또는 동의받은 테스트 snapshot 동기화를 별도로 구성해야 합니다. 이 버전은 공개 수집 API나 무단 원본 복제를 만들지 않습니다. EC2의 AI 도메인·프록시·인증 및 데이터 접근 정책을 확인한 후 연결하세요. Docker image에 원본 음성·case·token을 COPY하지 않으며 필요한 데이터만 read-only mount합니다.

이번 변경에는 Jenkins, 운영 AI, Android, Backend, EC2 nginx 설정 변경이 없습니다. 배포·공유·push·MR·merge는 별도 단계입니다.

