# MICON 추천 실험실

운영 AI와 별도 프로세스로 실행되는 읽기 전용 내부 도구입니다. 변경 파일은 모두 `ai/recommend-lab/`에 있습니다. 운영 추천 점수 계산과 기본값 선택은 `../recommend-server/timbre_recommender`를 직접 사용합니다. 운영 API 모듈은 import하지 않습니다.

## 기능

- ECAPA와 Singer-BYOL 곡별 2차원 지도, 가수·곡 hover, 같은 가수 강조, 확대·이동
- 새 요청 목록 10초 갱신, 요청 점 표시 선택, 요청별 상세 상태
- 모델별 전체 후보 음색 순위, 게이트 탈락 회색 표시, 복수 탈락 조건과 실제 수치
- 고정 입력 기본값과 후보 설정 비교, 최종 추천과 Top K 교집합, 벡터 없는 결과 JSON 저장
- 장르 선택(DB 라벨)과 **선택 안 한 장르 밀어내기 슬라이더**, 현행 언어 게이트 on/off. Top K에 장르 태그와 선택 장르 곡 수를 나란히 표시하고, 장르가 다른데 음색으로 뚫고 들어온 곡을 따로 보여 준다
- job-id 조회, 컴포넌트 분석 상태와 콜백 수락·HTTP 상태 분리, 실패 코드·사유 표시
- 요청 목록·job 상세·요청 지도 hover의 가입 ID(로그인 이메일), 내부 회원번호 보조 표시, 가입 ID 검색 및 `회원:5` 정확한 번호 검색
- 선택한 job 회원의 등록 전체 음역(음이름·MIDI), 닮은 가수 순위와 모델별 점수: 실제 DB 저장 결과와 AI 계산·콜백 전달 상태 분리
- 실패 원문이 만료된 경우 명시하고, 보존된 메타데이터로 확인한 조건 위반은 별도 진단 후보로 표시
- AI 서버가 `mrDetection`에 남긴 `MR 의심` 판정을 요청 목록 카드와 job 상세에 표시: 판정 근거, 바닥 비율·동적 범위, 보컬 분리본/원본 중 어느 쪽으로 임베딩했는지 구분. 실험실은 판정을 다시 계산하지 않고 기록된 값만 읽습니다

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
| LAB_ARTIST_JOBS_DIR | 선택: AI 닮은 가수 검색 job별 job.json 읽기 전용 디렉터리; 콜백 실패한 계산 목록도 진단 표시 |
| LAB_CASES_DIR | 동의받은 고정 입력 case JSON 디렉터리 |
| LAB_OWNER_SSH_TARGET, LAB_OWNER_SSH_KEY | 선택: 기존 백엔드 DB의 읽기 전용 job 소유자 조회 SSH 연결 및 서버 전용 key 경로 |
| LAB_OWNER_SSH_BIN | 선택: OpenSSH client 실행 파일; 기본 PATH의 ssh |
| LAB_OWNER_MAP | 선택: 승인된 백엔드 job→회원 연결 snapshot 경로; SSH 설정 없을 때 사용 |
| LAB_PORT | 기본 8790 |
| LAB_HOST | 인증 모드 bind 주소 기본 127.0.0.1 |
| LAB_USERNAME, LAB_PASSWORD | 팀 인증 사용자와 12자 이상 비밀번호 |
| LAB_DEV_MODE | 로컬 전용 1 인증 우회 개발 모드 |

카탈로그 array hash와 range provenance hash를 시작 시 확인합니다. index 및 추천 core fingerprint를 화면과 보고서에 표시합니다. 배치 변경 후에는 실험실 프로세스를 재시작해 새 지도 좌표계를 생성해야 합니다. 사용자 임베딩 두 모델은 기존 모델 계약에 맞춰 생성된 자료만 연결하세요. 차원만 같다고 새 모델과 기존 모델을 혼용할 수는 없습니다.

일반 TIMBRE 분석의 진단 재생은 동일 sessionId의 RANGE 콜백만 결합합니다. 시간상 가까운 요청이나 다른 session의 음역을 섞지 않습니다. 실제 추천 당시 BE voiceProfile, 취향, 제외 원곡이 동일하다는 보장은 없으므로 **당시 추천의 정확한 재현이라고 표시하지 않습니다**. 동일 session에 RANGE가 복수이면 자동 결합하지 않습니다. 음역 입력이 없거나 불신뢰이면 기본값은 운영과 같은 음색 단독 fallback입니다.

추천 job.json에는 원래 query 벡터·histogram이 저장되지 않으므로 과거 추천 job을 ID만으로 정확히 재생할 수 없습니다. 당시 저장된 추천 목록은 상태 상세에 표시합니다. 원본이 만료됐으면 임의로 복구하거나 새 데이터로 과거 결과를 대체하지 않습니다. 운영의 음성 자동 삭제 정책은 변경하지 않습니다.

### 회원 ID 확인

화면의 주 식별자는 **가입 ID(로그인 이메일)**입니다. 현재 가입·로그인 계약이 `email`을 사용하므로, 정확한 job 소유자의 `users.email`을 조회해 `loginId`로 반환합니다. 내부 숫자 `user_id`는 **회원번호**라는 별도 보조 정보이며 sessionId/profileId와도 구분합니다. BIGINT 정밀도를 보존하기 위해 userId는 API 문자열로 반환합니다. AI job에 임의의 회원 번호를 기록하거나 시간순으로 회원을 추측하지 않습니다. 분석은 `voice_analysis_component_job.ai_job_id` 또는 `voice_analysis_job.ai_job_id` → 같은 session의 `user_id`, 추천은 `recommendation.job_id` → `user_id`로 **해당 job-id를 직접 연결**한 후 users를 조회합니다. job의 session/profile, 회원번호 또는 로그인 ID 연결이 충돌하면 표시하지 않습니다. 회원번호만 제공된 이전 snapshot은 가입 ID를 숫자로 대체하지 않고 확인 불가로 표시합니다.

선택 SSH bridge는 기존 백엔드의 `micon-mysql-1` 컨테이너에서 `START TRANSACTION READ ONLY` 내 고정 SELECT만 실행합니다. 기존 운영 진단 접근(`sudo -n python3 -`, 컨테이너 password file)을 사용하는 **로컬 내부 진단용**이며, 비밀번호·SSH key·전체 회원 정보는 응답이나 Git에 포함되지 않습니다. 요청 소유자 확인에 필요한 로그인 이메일만 조회하며 이 값은 개인정보이므로 localhost 개발 모드 또는 인증된 팀용 페이지에서만 볼 수 있습니다. 목록 전체를 일괄 조회하고 결과를 메모리에 30초만 캐시합니다. 연결 실패/삭제/매핑 없음은 `가입 ID 확인 불가`로 구분하고 운영 DB를 수정하지 않습니다. 공유 배포에는 이 관리자 SSH 접근을 그대로 사용하지 말고, 최소 권한 SELECT 계정/승인된 BE 소유자 조회 API 또는 아래 read-only snapshot을 적용하세요. Docker 예시는 SSH client·관리자 key를 포함하지 않습니다.

Portable snapshot은 `schemaVersion: 1`, 미래 Unix 초 `validUntilEpoch`, `owners: [{kind: "analysis" 또는 "recommendation", id: 정확한 job-id, userId: 회원번호 문자열, loginId: 가입 로그인 이메일, sessionId/profileId: 확인된 연결 번호}]` 형식입니다. 승인된 백엔드 exporter가 공급하고 read-only mount 및 `LAB_OWNER_MAP`으로 연결하세요. 만료되면 확인 불가가 됩니다(최대 캐시 30초). snapshot은 개인정보인 loginId를 포함하므로 접근·보관·삭제 정책을 적용하고 Git에 commit하지 않습니다. 원본 음성·비밀번호·토큰·불필요한 계정 정보를 넣지 않습니다. 저장 테스트 case에는 실제 회원 접수증처럼 ID를 부여하지 않습니다.

### 회원 음역과 닮은 가수

Job 상세는 확인된 **job→회원→동일 session** 연결에 DB SELECT를 실행합니다. 추천 job은 `recommendation.source_session_id`를 사용합니다. 과거 추천에 이 값이 없으면 확인된 동일 job의 `profile_id/user_id` → `user_voice_profile`의 **현재 연결 session**을 별도 컨텍스트로 조회하고 추천 당시 음역·가수 결과는 확인 불가라고 명시합니다. job의 원 sessionId를 현재 프로필 session으로 채우지 않으며 회원의 가장 최근 session을 임의로 붙이지 않습니다. 동일 job에 서로 다른 session/profile이 연결되면 회원과 상세 결과를 숨깁니다. 목록에는 추가 상세 DB 조회를 하지 않고 선택한 job만 조회하며 캐시는 10초입니다. 분석 job이 계속 COMPLETED여도 별도로 진행되는 닮은 가수 DB 결과를 10초마다 다시 확인합니다.

- 음역은 `voice_range_measurement.full_low_midi/full_high_midi`의 **앱 등록 전체 음역**입니다. 측정·품질 상태를 함께 표시하며 미측정/유효하지 않은 범위를 채우지 않습니다. 해당 기록에는 실측 중앙값·안정/편안한 음역이 없으므로 범위 중간값으로 대체하지 않습니다. 측정 시각은 앱 전송값의 DB 원문이며 접수 시각과 시간대가 다를 수 있습니다.
- 닮은 가수 **실제 저장 목록**은 해당 session의 `similar_artist_result JOIN singer`입니다. 가수 이름은 표시용 BE 데이터이고 AI 서버의 ID-only 응답 계약을 변경하지 않습니다. DB 저장 확인은 앱 화면 표시 확인과 다릅니다. 최신 시도와 다른 component job에서 저장된 결과는 이전 시도로 표시합니다.
- AI **계산 목록**은 BE가 해당 session에 연결한 정확한 `ai_job_id`의 로컬 기록만 읽습니다. 원 요청 session·결과 버전·배치·scoring version을 대조합니다. 콜백 409 등으로 거절됐으면 계산 완료와 전달 실패를 함께 표시하며 사용자에게 실제 전달된 추천으로 표시하지 않습니다. 현재 session 버전과 다른 이전 계산도 표시합니다.
- 새 ID-only 계산 목록의 이름은 ID sidecar가 index hash와 일치할 때, BE `singer_id`로 조회합니다. ID가 없는 과거 기록은 보존된 당시 이름만 별도 표기하고 이름으로 ID를 추측하지 않습니다. 해당 sidecar가 없는 배치에서는 알 수 없는 가수 ID를 임의의 이름으로 대체하지 않습니다.

이 상세 기능은 현재 로컬 SSH read-only bridge를 사용하는 경우 연결됩니다. 소유자 snapshot만으로 실시간 음역·가수 정보를 확보했다고 간주하지 않습니다. EC2 팀 배포는 승인된 최소권한 데이터 소스 구현과 AI 검색 job 읽기 전용 연결이 별도 필요합니다. 관리자 key를 image/Git에 넣거나 팀 브라우저에 원본 payload·벡터·음성 파일을 보내지 않습니다. 연결 실패는 저장 결과가 없다는 뜻이 아니며 조회 불가로 표시합니다.

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

현재 후보 설정은 ECAPA 혼합 비중, 평균·구간 비중, 음색·음역 최종 비중, 선택 장르와 밀어내기 배수, 언어 게이트 on/off, 최대 키 변화, 음역 밖 시간 비율 허용, 핵심 음역 초과 허용(실험), 최소 음역 점수, 곡 프로파일 신뢰도, 음역 게이트 활성화, Top K입니다. 기본값은 production `build_shelves`를 사용하고 후보는 같은 `score_all_tracks` + `compose_by_final_score`(운영 현행)에 검증된 별도 gate 정책을 적용합니다.

**조립 방식은 운영을 따라간다.** 운영은 `compose_by_final_score`(`final-score-v1`)로 바뀌었습니다 — 축별 2:2:1 할당을 쓰지 않고 게이트 통과 후보를 final score 순으로 담으며, 한 가수의 곡은 최대 `MAX_SONGS_PER_ARTIST`(2)곡까지만 넣습니다. 실험실의 기본 조립 방식도 이것이고, 2:2:1 축별 할당(`compose_two_axis`)은 **이전 방식 비교용**으로만 남겨 두었습니다. 음역 게이트도 "핵심 음역 초과 0반음"이 아니라 `MAX_OUTSIDE_RANGE_RATIO`(최적 키에서 음역 밖에 머무는 시간 비율 ≤ 5%)이며, 목소리 shelf의 허용 키는 ±4입니다. 화면 기본값·상수는 `/api/map` 의 `catalog.defaults`·`catalog.composition` 에서 받아 채우므로, 운영 상수가 바뀌면 화면도 따라갑니다(하드코딩 금지). 전역 상수를 monkeypatch하지 않습니다. 후보 정책이 맞다고 판단되면 **별도의 운영 코드 변경과 회귀 테스트 및 MR**이 필요합니다. 실험 실행으로 운영 설정이 자동 적용되지 않습니다.

전체 모델 순위는 각 모델 음색 점수 내림차순입니다. 최종 추천은 게이트 후 두 축 quota 선택 + final_score 정렬입니다. 최적 키는 먼저 -6~+5 범위에서 계산한 값이며, 현재 후보 설정도 그 최적 키를 gate로 확인합니다. ±3 안에서 대체 키를 다시 최적화하는 기능은 아닙니다. 성별 필터나 정확도 자동 판정도 없습니다. 탈락 여부와 Top K 미선택을 혼동하지 마세요.

### 장르 (`genres.py`)

**현행 운영 흐름**은 이렇다. ① 사용자가 장르를 고른다 → ② 장르를 **언어**로 바꾼다(`발라드·댄스·록` → 한국어, `팝·영어곡` → 영어, `J-Pop` → 일본어) → ③ 그 언어가 아닌 곡을 통째로 뺀다 → ④ 남은 곡끼리 ECAPA·Singer-BYOL·음역 점수로 겨룬다 → ⑤ **같은 언어 안에서 발라드냐 댄스냐는 순위에 전혀 반영되지 않는다.** 스타일 태그를 만드는 `selected_tags()` 는 테스트 외에 호출되는 곳이 없다.

실험실이 검증하는 대안은 세 가지다. 운영 코어는 건드리지 않는다.

- **DB 장르로 통일.** 기준 표기가 곧 DB 라벨(`발라드`, `랩/힙합`, `J-POP` …)이다. 예전 번들의 영어 태그(`Ballad`, `Hip-Hop`)와 표기 변형(`록/메탈`)은 `DB_LABEL_ALIASES` 로 같은 DB 라벨에 접는다. 따로 외울 코드값이 없다.
- **언어 게이트 폐기.** 장르를 언어로 바꾸지 않는다(`useLanguageGate` 기본 꺼짐). J-POP 을 안 고른 사람에게 J-POP 이 안 뜨는 것은 언어 필터가 아니라 아래 감점으로 이뤄진다. 비교하려면 화면에서 다시 켤 수 있다.
- **선택 안 한 장르 감점(`genrePushdown`).** 후보에서 빼는 게 아니라 점수를 깎는다. 그래서 음색이 압도적으로 닮은 곡은 장르가 달라도 올라올 수 있고, 화면은 그렇게 뚫고 들어온 곡을 따로 보여 준다.

**감점 크기의 기준은 Top-K 경쟁 구간이다.** 전체 점수 폭(최고−최저)을 쓰면 슬라이더를 조금만 올려도 하드 필터가 된다. 실제 입력에서 전체 폭은 0.52인데 Top-10 의 1위와 10위 차이는 0.067밖에 안 되기 때문이다. 그래서 `band = 감점 전 1위 − K위 커트라인` 을 단위로 삼고, 슬라이더는 그 **배수**(0~3배)다. 실측(880곡, `발라드` 선택, 음역 게이트 끔):

| 밀어내기 | Top-10 중 발라드 | 선택 밖 잔류 |
| --- | --- | --- |
| 0배 | 1곡 | 9곡 (알앤비·락·인디·포크·OST) |
| 1배 | 6곡 | 4곡 |
| 2배 | 10곡 | 없음 |

- **적용 지점**: 현행 조립 방식은 final score 순이므로 감점이 곧 Top-K 구성을 바꾼다. 비교용으로 남긴 2:2:1 축별 할당에서는 선발이 축 점수로 이뤄지므로 그때만 두 축 점수에도 같은 값을 뺀다. 모델별 순위표에는 언제나 감점 전 점수를 남긴다.
- **감점하지 않는 곡**: 장르 태그가 없거나 DB 라벨로 못 접은 곡, `K-Pop` 같은 범용 태그만 달린 곡. 메타데이터가 비었다는 이유로 곡이 조용히 사라지면 안 되기 때문이고, 그 수를 화면에 따로 표시한다.

**운영 쪽에서 별도로 확인할 것**: `shelves.RAP_TAGS` 는 영어 태그(`Hip-Hop`)만 보는데 현재 카탈로그 라벨은 한글(`랩/힙합`)이라 랩 제외가 동작하지 않는다. 실험실 변경 범위 밖이므로 운영 MR로 따로 다뤄야 한다.

### 고정 개선안 검증

실험 결과 아래의 **개선안 검증 프로세스**는 현행 Top K와 별도로 고정한 후보 정책을 같은 입력으로 비교한다. 후보 정책은 다음 순서다.

1. 랩·원곡 제외 후, 음역 입력이 있으면 최적 키에서 사용자 핵심 음역 밖의 곡 histogram 질량이 5% 이하인 곡만 남긴다.
2. 두 모델 모두 상위 23%인 곡을 순위 합으로 우선 선택하고, Top K가 부족하면 35%까지 넓힌다.
3. 부족한 자리는 RRF(`k=60`)로 채우며, 같은 순위에서는 키 이동이 적은 곡을 우선한다.
4. 서로 다른 가수를 먼저 고르고, 후보가 부족할 때만 두 번째 곡을 허용하며 한 가수는 최대 2곡으로 제한한다.

**주의(2026-09-21):** 이 고정 개선안의 1번(음역 밖 질량 ≤ 5%)과 4번(가수당 최대 2곡)은 그 사이 **운영에 이미 반영됐다**(`MAX_OUTSIDE_RANGE_RATIO`, `MAX_SONGS_PER_ARTIST`). 따라서 이 패널이 지금 실제로 검증하는 차이는 2~3번(두 모델 합의 비율 + RRF 보충)뿐이고, 가수 다양성 지표는 현행에서도 이미 충족될 수 있다. 패널을 다시 쓸 때 후보 정책을 현행 기준으로 갱신해야 한다.

화면은 고유 가수 수, 중복 곡 수, 한 가수의 최다 노출, 각 곡의 음역 밖 질량을 함께 보여 준다. `range_profiles.json`의 값은 duration-weighted 음정 히스토그램이므로, 5%는 시간 구간에 대한 근사치이며 고음 한 번의 실제 난이도나 사용자의 선호를 증명하지 않는다. 따라서 이 비교는 가수 편중과 음역 프록시의 개선 여부만 판정하며, **추천 품질 개선 확정에는 별도 사용자 선호 평가·연습 완료율·녹음 결과가 필요하다.**

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
