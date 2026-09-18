# MICON 통합 요구사항 명세서 v3<span style="color: #ffffff; background-color: #15803d;">.1</span>

담당자: 신승민
등록일: 2026년 9월 2일 오후 2:20
보관: 초안
수정일: 2026년 9월 <span style="color: #ffffff; background-color: #15803d;">11</span>일
<span style="color: #ffffff; background-color: #15803d;">요약: 기존 통합 명세에 기기 음역 측정, 곡 선택·MR·가사 기반 음색 녹음, 조건부 수동 종료, 부분 리포트와 선택 저장 요구사항을 반영한 개정안</span>
유형: 요구사항

- 작성일: 2026-08-31
- 기준 자료: Notion `📋 요구사항 명세서`, Figma `MICON(부제)` 디자인 및 프로토타입
- <span style="color: #ffffff; background-color: #15803d;">온보딩 개정 기준: 2026-09-11 곡 선택·녹음 종료·리포트·모델 결과 보관 요구사항 및 음역 FE·음색 BE 경유 AI 처리 합의.</span>
- 대상: Android 모바일 애플리케이션 MVP
- 우선순위: `P0` 필수, `P1` 중요, `P2` 후속
- 상태: 팀 검토용 초안

### 운영 저장소 구현 참고 (2026-09-09)

운영 구조는 <a href="operations/S3_VOICE_STORAGE.md">SSAFY 서버의 BE·MySQL + 개인 AWS 계정 비공개 S3</a>로 선택했다. 파일과 DB 메타데이터를 분리하며, 팀원 파일 업로드·권한 전달·DB 적재는 해당 운영 계약을 따른다. 2026-09-09 버킷·BE 전용 IAM·서버 키 저장 및 CLI 파일 통신 검증은 완료했고, 앱/DB/AI 통합과 팀원 업로더 발급은 미완료다. 이 인프라 변경은 기능 우선순위, 삭제 범위, 음원·가사 이용 권한을 변경하지 않는다. <span style="color: #ffffff; background-color: #15803d;">온보딩</span> <span style="color: #ffffff; background-color: #15803d;">원음의</span> <span style="color: #ffffff; background-color: #15803d;">임시</span> <span style="color: #ffffff; background-color: #15803d;">보관은</span> <span style="color: #ffffff; background-color: #15803d;">10.2.4절,</span> <span style="color: #ffffff; background-color: #15803d;">그</span> <span style="color: #ffffff; background-color: #15803d;">외</span> <span style="color: #ffffff; background-color: #15803d;">녹음의</span> <span style="color: #ffffff; background-color: #15803d;">저장</span> <span style="color: #ffffff; background-color: #15803d;">설정은 SET-002를 따른다</span>.

## 1. 개정 목적

기존 Notion 명세의 기능 요구사항과 현재 Figma 화면을 하나의 구현 기준으로 통합한다. 중복된 요구사항 ID를 정리하고, 디자인에만 존재하던 화면 상태와 최근 결정사항을 명시한다.

### 1.1 이번 개정에서 확정한 사항

- MVP의 기본 플랫폼은 Android 모바일 앱이다.
- 알림 기능과 알림 권한은 MVP에서 제외한다.
- 런타임에 사용자에게 요청하는 필수 권한은 마이크 권한 하나로 제한한다.
- <span style="color: #ffffff; background-color: #15803d;">첫 </span>음성 <span style="color: #ffffff; background-color: #15803d;">온보딩은</span> <code><span style="color: #ffffff; background-color: #15803d;">기기</span> <span style="color: #ffffff; background-color: #15803d;">음역</span> <span style="color: #ffffff; background-color: #15803d;">측정</span> <span style="color: #ffffff; background-color: #15803d;">→</span> <span style="color: #ffffff; background-color: #15803d;">곡</span> <span style="color: #ffffff; background-color: #15803d;">검색·선택</span> <span style="color: #ffffff; background-color: #15803d;">→ MR·가사 기반 음색 </span>녹음 <span style="color: #ffffff; background-color: #15803d;">→</span> <span style="color: #ffffff; background-color: #15803d;">부분</span> <span style="color: #ffffff; background-color: #15803d;">리포트 → 선택 저장</span></code><span style="color: #ffffff; background-color: #15803d;"> 순서로 진행한다</span>.
- <span style="color: #ffffff; background-color: #15803d;">음역은 기존 v7 규칙으로 기기에서 분석한다. 음색 녹음은 BE를 통해 AI에 전달하며 FE가 AI를 직접 호출하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">음색 측정곡은 검색하여 선택한다. MR과 가사를 제공하며 MR 시작 위치는 </span><code><span style="color: #ffffff; background-color: #15803d;">처음부터</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">2절부터</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">하이라이트부터</span></code><span style="color: #ffffff; background-color: #15803d;"> 중 선택한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">녹음은 일정 시간이 지났다는 이유로 자동 종료하지 않는다. MR 재생 중 실제 누적 녹음 시간이 30초 이상이고 MR이 일시정지된 상태에서만 </span><code><span style="color: #ffffff; background-color: #15803d;">녹음 종료</span></code><span style="color: #ffffff; background-color: #15803d;"> 버튼을 활성화하며, 사용자가 눌러 종료한다.</span>
- 찜 목록은 단순 목록과 폴더형 `세트리스트`를 함께 제공한다.
- <span style="color: #ffffff; background-color: #15803d;">온보딩 화면은 결과 없음, 음역 측정, 곡 선택, 음색 녹음, 분석 중, 부분 결과, 저장 가능, 저장 중, 저장 완료, 재시도 필요 상태를 구분한다. ECAPA·Singer-BYOL의 이름·개별 처리 상태는 화면에 노출하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">음역은 최저음부터 최고음까지 연속 범위로 표시하고, 닮은 가수는 ECAPA 결과의 코사인 유사도로 검색하여 표시한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">ECAPA와 Singer-BYOL 처리 결과는 모델별로 독립 보관한다. 사용자가 저장하면 최신 프로필 1개를 반영한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">온보딩 원음은 재시도용으로 최대 1시간 임시 보관하고, 미저장 분석 결과는 마지막 결과 생성 후 24시간 보관한다. 프로필 저장은 원음 장기 보관을 의미하지 않는다.</span>
- 넓은 저장공간 권한은 사용하지 않는다. 파일을 가져오거나 내보낼 때 Android 시스템 파일 선택기를 사용한다.
- 백그라운드 녹음은 MVP에서 지원하지 않는다.

## 2. 서비스 개요

MICON은 사용자의 음역과 보컬 특성을 분석하여 부르기 적합한 노래와 추천 키를 제공하고, 선택한 곡을 실시간 음정 피드백과 함께 연습할 수 있도록 돕는 모바일 서비스다.

## 3. 서비스 목표

1. 사용자가 노래를 부르기 전에 자신의 음역에 적합한 곡인지 판단하게 한다.
2. 반복적인 키 조절 시행착오를 줄이고 적절한 시작 키를 제안한다.
3. 기존에 부르던 곡 외에도 사용자의 목소리에 맞는 새로운 곡을 발견하게 한다.
4. 연습 전 예상 난관과 연습 후 개선 구간을 연결해 반복 연습을 돕는다.
5. 측정 및 연습 기록을 바탕으로 보컬 능력의 변화를 확인하게 한다.

## 4. 사용자와 이용 범위

### 4.1 회원

- 분석 결과, 찜, 세트리스트, 연습 기록을 저장하고 다시 조회한다.
- 음역·음색 기반 개인화 추천과 추천 키를 받는다.
- 개인정보와 음성 데이터의 저장·삭제 방식을 관리한다.

### 4.2 로그인 필수 정책

※ 모든 핵심 기능은 로그인 후 이용할 수 있습니다.

- 비로그인 사용자는 랜딩, 서비스 소개, 회원가입 및 로그인 화면에만 접근할 수 있다.
- 음성 측정·분석, 노래 추천·연습, 찜·세트리스트, 기록 및 설정을 이용하려면 반드시 로그인해야 한다.
- 비로그인 상태에서 보호된 기능에 접근하면 로그인 화면으로 이동한다.
- 로그인 완료 후에는 사용자가 접근하려던 화면이나 작업을 이어서 진행할 수 있다.

## 5. 정보 구조 및 핵심 화면

### 5.1 하단 내비게이션

1. 분석 리포트
2. 노래 연습
3. 추천곡
4. 찜 목록

설정은 상단의 설정 아이콘 또는 마이페이지에서 진입한다. 알림 아이콘과 알림 화면은 제거한다.

### 5.2 Figma 기준 화면 목록

| 화면군 | 화면 및 상태 |
| --- | --- |
| 시작 | 랜딩, 시작, 회원가입, 선호 가수·장르 조사, 로그인 |
| <span style="color: #ffffff; background-color: #15803d;">온보딩·</span>분석 | 결과 없음, <span style="color: #ffffff; background-color: #15803d;">기기 음역 </span>측정<span style="color: #ffffff; background-color: #15803d;">,</span> <span style="color: #ffffff; background-color: #15803d;">곡</span> <span style="color: #ffffff; background-color: #15803d;">검색·</span>선택, <span style="color: #ffffff; background-color: #15803d;">MR 시작 위치 선택, MR·가사 기반 </span>녹음, <span style="color: #ffffff; background-color: #15803d;">MR 일시정지·재개, 녹음 종료 안내, </span>분석 중, <span style="color: #ffffff; background-color: #15803d;">부분</span> <span style="color: #ffffff; background-color: #15803d;">리포트</span>, <span style="color: #ffffff; background-color: #15803d;">저장 가능·진행·완료·실패, </span>다시 분석 |
| 추천 | 개인화 추천곡 목록, 인기차트, 선택 곡 상세, 곡 적합도 상세 |
| 연습 | 곡 선택, 연습 전, 연습 중, 연습 종료, 연습 분석 상세 |
| 찜 | 찜 목록, 세트리스트 목록, 세트리스트 상세 |
| 사용자 | 마이페이지, 설정, 개인정보 및 음성 데이터 관리 |

## 6. 핵심 사용자 흐름

### 6.1 첫 분석 흐름

<span style="color: #ffffff; background-color: #15803d;">로그인·회원가입 → 기존 선호 가수·장르 조사 → 분석 시작 → 마이크 안내 및 권한 요청 → 기기 음역 측정·결과 전송 → 곡 검색·선택 → MR·가사 로드 → 시작 위치 선택 → MR 재생과 음색 녹음 → MR 재생 중 누적 녹음 30초 이상 확보 → MR 일시정지 → 사용자가 </span><code><span style="color: #ffffff; background-color: #15803d;">녹음 종료</span></code><span style="color: #ffffff; background-color: #15803d;"> 선택 → BE 업로드·AI 분석 → 부분 리포트 → 필수 결과 완료 → 사용자 선택 저장</span>

- <span style="color: #ffffff; background-color: #15803d;">기존 회원은 저장된 선호 정보를 재사용한다. 선호 설문 완료와 음성 프로필 저장 완료는 별도로 관리한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">음역 결과 전송이 끝나기 전에도 곡 선택과 음색 녹음을 진행할 수 있으며, 두 결과를 같은 분석 세션에 연결한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">종료 조건을 충족하지 못하면 MR을 재개하여 계속 녹음하거나 재녹음·취소할 수 있다. 조건 충족이나 MR 곡 재생 종료만으로 녹음을 제출하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">음색 녹음 종료 후 업로드·분석·검색 시간이 필요하다. 음역과 닮은 가수는 준비되는 대로 먼저 표시하고, 저장 가능 여부를 별도로 안내한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">ECAPA·Singer-BYOL의 개별 상태는 BE가 내부적으로 관리한다. 사용자는 </span><code><span style="color: #ffffff; background-color: #15803d;">분석 중</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">추가 분석 필요</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">저장 준비 중</span></code><span style="color: #ffffff; background-color: #15803d;"> 등 작업을 이해할 수 있는 안내를 받는다.</span>

#### <span style="color: #ffffff; background-color: #15803d;">6.1.1 MR 시작 위치</span>

| <span style="color: #ffffff; background-color: #15803d;">선택</span> | <span style="color: #ffffff; background-color: #15803d;">동작</span> | <span style="color: #ffffff; background-color: #15803d;">인수 기준</span> |
| --- | --- | --- |
| <span style="color: #ffffff; background-color: #15803d;">처음부터</span> | <span style="color: #ffffff; background-color: #15803d;">곡의 시작 시점부터 MR 재생</span> | <span style="color: #ffffff; background-color: #15803d;">시작 위치와 가사 위치가 일치한다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">2절부터</span> | <span style="color: #ffffff; background-color: #15803d;">해당 곡에 등록된 2절 시작 시점부터 재생</span> | <span style="color: #ffffff; background-color: #15803d;">검증된 2절 시각을 사용하며 임의의 비율이나 시간으로 추정하지 않는다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">하이라이트부터</span> | <span style="color: #ffffff; background-color: #15803d;">해당 곡에 등록된 하이라이트 시작 시점부터 재생</span> | <span style="color: #ffffff; background-color: #15803d;">하이라이트 시각과 가사가 일치한다. 후렴이라는 이유만으로 임의 지정하지 않는다.</span> |

<span style="color: #ffffff; background-color: #15803d;">2절·하이라이트 메타데이터가 없으면 해당 선택지를 비활성화하고 이유를 안내한다. MR 또는 가사를 제공할 수 없는 곡은 음색 측정에 사용할 수 없음을 알리고 다른 곡을 선택하게 한다.</span>

#### <span style="color: #ffffff; background-color: #15803d;">6.1.2 녹음 종료 조건</span>

<code><span style="color: #ffffff; background-color: #15803d;">녹음 종료 활성화 = 녹음 세션 유효 AND MR 재생 중 누적 녹음 시간 &gt;= 30초 AND MR 일시정지 상태</span></code>

<span style="color: #ffffff; background-color: #15803d;">녹음 확보 조건은 </span><strong><span style="color: #ffffff; background-color: #15803d;">같은 녹음에서 MR이 재생 중이고 마이크 녹음도 실제 진행된 시간의 합이 30초 이상</span></strong><span style="color: #ffffff; background-color: #15803d;">인 것이다. 일시정지·버퍼링·마이크 중단 시간은 제외한다. MR의 현재 재생 위치나 곡 탐색으로 건너뛴 시간을 누적 녹음 시간으로 계산하지 않는다. 이 값은 유효 가창 시간이나 모델 분석 성공 여부를 뜻하지 않는다.</span>

| <span style="color: #ffffff; background-color: #15803d;">녹음 확보 조건</span> | <span style="color: #ffffff; background-color: #15803d;">MR 상태</span> | <span style="color: #ffffff; background-color: #15803d;">녹음 종료 버튼</span> |
| --- | --- | --- |
| <span style="color: #ffffff; background-color: #15803d;">누적 30초 미만</span> | <span style="color: #ffffff; background-color: #15803d;">재생 중</span> | <span style="color: #ffffff; background-color: #15803d;">비활성화. 필요한 녹음 시간 안내</span> |
| <span style="color: #ffffff; background-color: #15803d;">누적 30초 미만</span> | <span style="color: #ffffff; background-color: #15803d;">일시정지</span> | <span style="color: #ffffff; background-color: #15803d;">비활성화. 이어 녹음하기 안내</span> |
| <span style="color: #ffffff; background-color: #15803d;">누적 30초 이상</span> | <span style="color: #ffffff; background-color: #15803d;">재생 중</span> | <span style="color: #ffffff; background-color: #15803d;">비활성화. 일시정지 후 종료 안내</span> |
| <span style="color: #ffffff; background-color: #15803d;">누적 30초 이상</span> | <span style="color: #ffffff; background-color: #15803d;">일시정지</span> | <span style="color: #ffffff; background-color: #15803d;">활성화. 사용자가 눌렀을 때 녹음 종료</span> |

- <span style="color: #ffffff; background-color: #15803d;">MR 일시정지는 녹음 완료·제출이 아니다. 일시정지 동안의 대기 시간은 녹음 확보량에 포함하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">MR을 재개하면 녹음을 이어가고 종료 버튼을 다시 비활성화한다. 일시정지·재개 구간과 곡 재생 위치를 구분해 기록한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">녹음 확보 조건은 경과 시간만으로 서버 분석 성공을 보장하지 않는다. 서버는 업로드된 실제 입력의 품질을 별도로 검증한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">30초는 종료를 허용하는 최소 확보량이다. 재생 중 30초에 도달해도 녹음은 계속되며 자동 종료하지 않는다. 최대 녹음 시간·파일 크기는 수동 종료 흐름에 맞춰 15절에서 별도로 확정한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">MR이 끝까지 재생되면 재생기를 일시정지 상태로 전환한다. 누적 녹음이 30초 이상이면 종료 버튼을 활성화하되 사용자가 누르기 전에는 녹음을 정상 완료·제출하지 않는다. 부족하면 이어 녹음할 위치 선택 또는 재녹음을 안내한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">재녹음이나 다른 곡으로 바꾸면 새 녹음의 누적 시간을 0초부터 계산한다. 선택한 시작 위치에서 남은 MR 길이가 30초보다 짧으면 다른 시작 위치 또는 곡을 선택하도록 안내한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">취소·권한 회수·마이크 오류·앱 이탈로 중단된 녹음을 정상 완료나 자동 제출로 처리하지 않는다.</span>

### 6.2 추천 및 연습 흐름

분석 리포트 → 추천곡 → 곡 선택 → 적합도 상세 및 추천 키 확인 → 연습 전 설정 → 연습 중 → 연습 종료 → 연습 분석 상세 → 취약 구간 다시 연습

### 6.3 찜 및 세트리스트 흐름

추천곡/곡 상세에서 찜 → 찜 목록 → 세트리스트 생성 또는 선택 → 세트리스트 상세 → 곡 순서 관리 → 연습할 곡 선택

## 7. 기능 요구사항

### 7.1 회원 및 계정

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| MEM-001 | 이메일 회원가입 | 사용자는 이메일과 비밀번호로 회원가입할 수 있어야 한다. | P0 | 유효한 정보 입력 시 계정이 생성되고 로그인 상태가 된다. |
| MEM-002 | 이메일 로그인 | 사용자는 이메일과 비밀번호로 로그인할 수 있어야 한다. | P0 | 올바른 인증 정보로 로그인되며 잘못된 정보에는 원인을 안내한다. |
| MEM-003 | 로그아웃 | 사용자는 로그인 세션을 종료할 수 있어야 한다. | P0 | 로그아웃 후 개인 기록 화면에 접근할 수 없다. |
| MEM-004 | 비밀번호 재설정 | 이메일 가입 회원은 본인 확인 후 비밀번호를 재설정할 수 있어야 한다. | P0 | 완료 후 새 비밀번호만 유효하다. |
| MEM-005 | 회원 탈퇴 | 사용자는 확인 절차를 거쳐 회원 탈퇴할 수 있어야 한다. | P0 | 탈퇴한 인증 정보로 로그인하거나 기존 기록에 접근할 수 없다. |
| MEM-006 | 탈퇴 데이터 처리 | 탈퇴 시 음성·분석·추천·연습 데이터는 정책에 따라 삭제 또는 비식별화되어야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">온보딩 임시 원음·음역 결과·두 음색 모델의 결과·최신 프로필을 </span>삭제 <span style="color: #ffffff; background-color: #15803d;">대상에</span> <span style="color: #ffffff; background-color: #15803d;">포함한다.</span> <span style="color: #ffffff; background-color: #15803d;">늦은</span> <span style="color: #ffffff; background-color: #15803d;">분석·저장</span> <span style="color: #ffffff; background-color: #15803d;">작업으로 삭제 데이터가 복원되지 않으며 처리 결과를 안내한다</span>. |
| MEM-007 | 소셜 회원가입·로그인 | 소셜 회원가입과 로그인을 제공할 수 있어야 한다. | P2 | 지원 사업자와 계정 연결 정책 확정 후 적용한다. |
| MEM-008 | 선호 가수·장르 조사 | 회원가입 과정에서 사용자가 선호하는 가수와 장르를 선택할 수 있어야 한다.<span style="color: #ffffff; background-color: #15803d;"> 기존 로그인·설문 기능을 재사용한다.</span> | P0 | 복수 선택 결과가 계정에 <span style="color: #ffffff; background-color: #15803d;">저장되고 재진입해도 유지된다</span>. <span style="color: #ffffff; background-color: #15803d;">설문의</span> <code><span style="color: #ffffff; background-color: #15803d;">onboardingCompleted</span></code><span style="color: #ffffff; background-color: #15803d;">를</span> <span style="color: #ffffff; background-color: #15803d;">음성</span> <span style="color: #ffffff; background-color: #15803d;">분석·프로필</span> <span style="color: #ffffff; background-color: #15803d;">저장</span> <span style="color: #ffffff; background-color: #15803d;">완료</span> <span style="color: #ffffff; background-color: #15803d;">상태로</span> <span style="color: #ffffff; background-color: #15803d;">재사용하지 않는다</span>. |
| MEM-009 | 선호 정보 관리 | 사용자는 회원가입 이후 마이페이지에서 선호 가수와 장르를 확인하고 변경할 수 있어야 한다. | P1 | 변경한 선호 정보가 저장되고 이후 생성되는 추천 결과에 반영된다. |

### 7.2 음성 측정 및 녹음

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| VOI-001 | 마이크 권한 요청 | 측정 또는 연습 시작 시점에 마이크 사용 목적을 먼저 안내하고 권한을 요청해야 한다. | P0 | 최초 앱 실행 시에는 권한을 요청하지 않는다. |
| VOI-002 | 권한 거부 안내 | 권한 거부 시 기능 제한과 Android 설정에서 허용하는 방법을 안내해야 한다. | P0 | `권한 허용하기` 또는 `기기 설정에서 허용하기`가 상태에 맞게 노출된다. |
| VOI-003 | <span style="color: #ffffff; background-color: #15803d;">기기</span> <span style="color: #ffffff; background-color: #15803d;">음역</span> 측정 | 안내된 음계를 따라 <span style="color: #ffffff; background-color: #15803d;">부른</span> <span style="color: #ffffff; background-color: #15803d;">결과를</span> <span style="color: #ffffff; background-color: #15803d;">기존</span> <code><span style="color: #ffffff; background-color: #15803d;">guided-range-v7</span></code> <span style="color: #ffffff; background-color: #15803d;">규칙으로</span> <span style="color: #ffffff; background-color: #15803d;">기기에서</span> <span style="color: #ffffff; background-color: #15803d;">분석하고 음역 프로필을 BE에 제출해야 </span>한다. | P0 | 목표 음<span style="color: #ffffff; background-color: #15803d;">·</span>유지 <span style="color: #ffffff; background-color: #15803d;">상태·진행을</span> 확인할 수 있다.<span style="color: #ffffff; background-color: #15803d;"> BE는 형식·일관성을 검증하며 음역 원음 업로드나 서버 음역 재추론을 요구하지 않는다. 전송 중 다음 음색 측정을 진행해도 같은 세션에 연결된다.</span> |
| VOI-004 | <span style="color: #ffffff; background-color: #15803d;">선택곡</span> <span style="color: #ffffff; background-color: #15803d;">기반</span> <span style="color: #ffffff; background-color: #15803d;">음색</span> 녹음 | 사용자는 <span style="color: #ffffff; background-color: #15803d;">곡을</span> <span style="color: #ffffff; background-color: #15803d;">검색·선택하고</span> <span style="color: #ffffff; background-color: #15803d;">제공된</span> <span style="color: #ffffff; background-color: #15803d;">MR과</span> <span style="color: #ffffff; background-color: #15803d;">가사를</span> <span style="color: #ffffff; background-color: #15803d;">따라</span> <span style="color: #ffffff; background-color: #15803d;">부르며</span> <span style="color: #ffffff; background-color: #15803d;">음색 분석용 녹음을 수행할 </span>수 있어야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">곡명·가수·MR·가사가</span> <span style="color: #ffffff; background-color: #15803d;">선택곡과 일치한다. </span>시작·일시정지·<span style="color: #ffffff; background-color: #15803d;">재개·수동 </span>종료·<span style="color: #ffffff; background-color: #15803d;">재녹음을</span> <span style="color: #ffffff; background-color: #15803d;">제공하며</span> <span style="color: #ffffff; background-color: #15803d;">음색 녹음은 BE를 통해 AI로 전달된다</span>. |
| VOI-005 | 실시간 입력 상태 | <span style="color: #ffffff; background-color: #15803d;">음역</span> <span style="color: #ffffff; background-color: #15803d;">측정에는</span> <span style="color: #ffffff; background-color: #15803d;">목표 음·현재 음높이·유지 상태를</span>, <span style="color: #ffffff; background-color: #15803d;">음색 녹음에는 MR 재생 중 누적 녹음 시간·</span>입력 음량<span style="color: #ffffff; background-color: #15803d;">·MR</span> <span style="color: #ffffff; background-color: #15803d;">및</span> <span style="color: #ffffff; background-color: #15803d;">가사</span> <span style="color: #ffffff; background-color: #15803d;">위치·30초</span> <span style="color: #ffffff; background-color: #15803d;">확보</span> <span style="color: #ffffff; background-color: #15803d;">여부를 </span>표시해야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">입력과</span> <span style="color: #ffffff; background-color: #15803d;">재생</span> <span style="color: #ffffff; background-color: #15803d;">상태에 </span>맞춰 <span style="color: #ffffff; background-color: #15803d;">갱신되고,</span> <span style="color: #ffffff; background-color: #15803d;">종료</span> <span style="color: #ffffff; background-color: #15803d;">버튼이 비활성화된 이유를 알 수 있다</span>.<span style="color: #ffffff; background-color: #15803d;"> 일시정지·버퍼링·마이크 중단 시간과 곡 탐색으로 건너뛴 시간은 확보량에서 제외한다.</span> |
| VOI-006 | 녹음 유효성 검사 | <span style="color: #ffffff; background-color: #15803d;">파일</span> <span style="color: #ffffff; background-color: #15803d;">형식·용량·입력</span> <span style="color: #ffffff; background-color: #15803d;">품질을</span> <span style="color: #ffffff; background-color: #15803d;">검증하고</span> <span style="color: #ffffff; background-color: #15803d;">분석에</span> <span style="color: #ffffff; background-color: #15803d;">충분한</span> <span style="color: #ffffff; background-color: #15803d;">사용자</span> <span style="color: #ffffff; background-color: #15803d;">가창을</span> <span style="color: #ffffff; background-color: #15803d;">확보해야 </span>한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">잘못된</span> <span style="color: #ffffff; background-color: #15803d;">파일은</span> <span style="color: #ffffff; background-color: #15803d;">접수를</span> <span style="color: #ffffff; background-color: #15803d;">거부한다.</span> <span style="color: #ffffff; background-color: #15803d;">정상</span> <span style="color: #ffffff; background-color: #15803d;">파일의</span> <span style="color: #ffffff; background-color: #15803d;">무음·저음량·잡음·가창</span> <span style="color: #ffffff; background-color: #15803d;">부족은 분석 후 자료 부족으로 판정할 수 있으며 구체적 재녹음 </span>사유를 <span style="color: #ffffff; background-color: #15803d;">안내한다</span>.<span style="color: #ffffff; background-color: #15803d;"> 종료 버튼 활성화를 분석 성공으로 간주하지 않는다.</span> |
| VOI-007 | 분석 진행 표시 | <span style="color: #ffffff; background-color: #15803d;">업로드·분석·저장</span> <span style="color: #ffffff; background-color: #15803d;">준비</span> <span style="color: #ffffff; background-color: #15803d;">상태를</span> <span style="color: #ffffff; background-color: #15803d;">사용자</span> <span style="color: #ffffff; background-color: #15803d;">작업</span> <span style="color: #ffffff; background-color: #15803d;">단위로</span> <span style="color: #ffffff; background-color: #15803d;">안내해야</span> 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">ECAPA·Singer-BYOL</span> <span style="color: #ffffff; background-color: #15803d;">이름과</span> <span style="color: #ffffff; background-color: #15803d;">개별</span> <span style="color: #ffffff; background-color: #15803d;">진행·성공·실패</span> <span style="color: #ffffff; background-color: #15803d;">상태를</span> <span style="color: #ffffff; background-color: #15803d;">노출하지 않는다. 임의 퍼센트를 만들지 않고 중복 제출 방지·취소·</span>실패 후 재시도를 제공한다. |
| VOI-008 | 재측정·재시도 | 실패 <span style="color: #ffffff; background-color: #15803d;">종류에</span> <span style="color: #ffffff; background-color: #15803d;">따라</span> <span style="color: #ffffff; background-color: #15803d;">필요한</span> <span style="color: #ffffff; background-color: #15803d;">입력</span> <span style="color: #ffffff; background-color: #15803d;">또는</span> <span style="color: #ffffff; background-color: #15803d;">처리만</span> <span style="color: #ffffff; background-color: #15803d;">다시 수행할 </span>수 있어야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">음역</span> <span style="color: #ffffff; background-color: #15803d;">부족은</span> <span style="color: #ffffff; background-color: #15803d;">재측정,</span> <span style="color: #ffffff; background-color: #15803d;">음색</span> <span style="color: #ffffff; background-color: #15803d;">모델</span> <span style="color: #ffffff; background-color: #15803d;">실패는</span> <span style="color: #ffffff; background-color: #15803d;">보관된</span> <span style="color: #ffffff; background-color: #15803d;">원음으로</span> <span style="color: #ffffff; background-color: #15803d;">실패한 처리만 재시도한다</span>.<span style="color: #ffffff; background-color: #15803d;"> 닮은 가수 검색 실패는 기존 ECAPA 결과로 검색만 재시도한다. 원음 만료나 가창 부족은 재녹음을 안내하고 사용자에게 모델 선택을 요구하지 않는다.</span> |
| VOI-009 | 백그라운드 녹음 제한 | 백그라운드로 이동하면 녹음을 계속하지 않아야 한다. | P0 | 녹음 중 앱 이탈 시 중단 또는 복귀 선택을 안내한다.(화면꺼짐 제외) |
| <span style="color: #ffffff; background-color: #15803d;">VOI-010</span> | <span style="color: #ffffff; background-color: #15803d;">분석 세션 복구</span> | <span style="color: #ffffff; background-color: #15803d;">앱 복귀·재실행 시 같은 계정의 유효한 임시 분석 세션을 복구할 수 있어야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">음역·음색 입력과 부분 결과가 같은 세션에 연결된다. 만료되면 새 측정을 안내하고, 재녹음·재시도 전의 늦은 결과가 현재 결과에 섞이지 않는다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">VOI-011</span> | <span style="color: #ffffff; background-color: #15803d;">MR 시작 위치 선택</span> | <span style="color: #ffffff; background-color: #15803d;">사용자는 녹음 시작 전에 </span><code><span style="color: #ffffff; background-color: #15803d;">처음부터</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">2절부터</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">하이라이트부터</span></code><span style="color: #ffffff; background-color: #15803d;"> 중 MR 시작 위치를 선택할 수 있어야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">곡별 검증된 시작 시각을 사용하며 MR·가사가 동기화된다. 구간 정보가 없는 선택지는 사유와 함께 비활성화한다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">VOI-012</span> | <span style="color: #ffffff; background-color: #15803d;">조건부 수동 녹음 종료</span> | <span style="color: #ffffff; background-color: #15803d;">MR 재생 중 실제 누적 녹음 시간이 30초 이상이고 MR이 일시정지된 경우에만 </span><code><span style="color: #ffffff; background-color: #15803d;">녹음 종료</span></code><span style="color: #ffffff; background-color: #15803d;"> 버튼을 활성화해야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">6.1.2절의 네 조합이 모두 일치한다. 30초 도달·MR 종료만으로 자동 제출하지 않으며, 사용자가 활성 버튼을 눌러야 정상 종료한다. 재녹음·곡 변경 시 확보 시간을 초기화한다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">VOI-013</span> | <span style="color: #ffffff; background-color: #15803d;">MR·가사 제공 및 분석 입력 분리</span> | <span style="color: #ffffff; background-color: #15803d;">선택곡의 MR과 가사를 제공하되 음색 분석 대상은 사용자의 가창이어야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">MR 또는 가사 누락 시 측정 시작 전에 다른 곡 선택을 안내한다. 원곡 가수의 보컬이나 재생 MR을 사용자 녹음에 의도적으로 합성하지 않으며, 반주 유입으로 사용자 가창을 판별할 수 없으면 자료 부족으로 안내한다.</span> |

### 7.3 음성 분석 리포트

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| ANL-001 | 분석 결과 빈 상태 | 분석 결과가 없을 때 <span style="color: #ffffff; background-color: #15803d;">온보딩</span> 시작을 유도하는 빈 상태를 제공해야 한다. | P0 | <code>목소리 분석하러 가기</code> 버튼이 <span style="color: #ffffff; background-color: #15803d;">기기</span> <span style="color: #ffffff; background-color: #15803d;">음역</span> <span style="color: #ffffff; background-color: #15803d;">측정으로</span> 연결된다.<span style="color: #ffffff; background-color: #15803d;"> 유효한 임시 세션이 있으면 복구 경로를 제공한다.</span> |
| ANL-002 | <span style="color: #ffffff; background-color: #15803d;">부분</span> <span style="color: #ffffff; background-color: #15803d;">리포트</span> | <span style="color: #ffffff; background-color: #15803d;">분석이</span> <span style="color: #ffffff; background-color: #15803d;">모두</span> <span style="color: #ffffff; background-color: #15803d;">끝나기</span> <span style="color: #ffffff; background-color: #15803d;">전에도</span> <span style="color: #ffffff; background-color: #15803d;">준비된</span> <span style="color: #ffffff; background-color: #15803d;">음역</span> <span style="color: #ffffff; background-color: #15803d;">결과와</span> <span style="color: #ffffff; background-color: #15803d;">닮은 가수를 먼저 </span>표시해야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">ECAPA 결과로 가수 검색이 끝나면 BYOL </span>완료 <span style="color: #ffffff; background-color: #15803d;">전에도</span> <span style="color: #ffffff; background-color: #15803d;">닮은</span> <span style="color: #ffffff; background-color: #15803d;">가수를</span> <span style="color: #ffffff; background-color: #15803d;">표시한다.</span> <span style="color: #ffffff; background-color: #15803d;">모델별 상태는 숨기고 저장 가능 여부·추가 처리 필요 여부를 별도로 안내한다</span>. |
| ANL-003 | <span style="color: #ffffff; background-color: #15803d;">최저</span>·<span style="color: #ffffff; background-color: #15803d;">최고</span> 음역 <span style="color: #ffffff; background-color: #15803d;">표시</span> | <span style="color: #ffffff; background-color: #15803d;">기기에서</span> <span style="color: #ffffff; background-color: #15803d;">측정한</span> <span style="color: #ffffff; background-color: #15803d;">최저음부터</span> <span style="color: #ffffff; background-color: #15803d;">최고음까지를</span> <span style="color: #ffffff; background-color: #15803d;">하나의</span> <span style="color: #ffffff; background-color: #15803d;">연속</span> <span style="color: #ffffff; background-color: #15803d;">범위로</span> 표시해야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">음이름·옥타브·범위</span> <span style="color: #ffffff; background-color: #15803d;">막대의</span> <span style="color: #ffffff; background-color: #15803d;">최저·최고가</span> <span style="color: #ffffff; background-color: #15803d;">일치하며</span> <span style="color: #ffffff; background-color: #15803d;">중간 음에서 </span>시각적<span style="color: #ffffff; background-color: #15803d;"> 범위를 끊지 않는다. 연속 표시는 음역 요약이며 내부 음별 측정</span>·<span style="color: #ffffff; background-color: #15803d;">성공</span> <span style="color: #ffffff; background-color: #15803d;">판정을</span> <span style="color: #ffffff; background-color: #15803d;">임의 변경하지 않는다</span>. |
| ANL-004 | 음역별 안정도 분석 | 음높이별 안정도를 음정 정확도, 흔들림, 유지 시간을 반영하여 표시해야 한다. | P2 | <span style="color: #ffffff; background-color: #15803d;">상세 시각화는 후속 범위다. 온보딩에 필요한 음별 지표 수집·프로필 집계는 VOI-003의 P0 범위이며, 상세 화면 도입 시 </span>안정·부담·불안정·미측정을 색상 외 표식과 텍스트로 구분한다. |
| ANL-005 | 어울리는 음악 특성 | 중심 음역, 고음 지속 시간, 적정 BPM, 가사 밀도, 프레이즈 길이, 장르, 분위기, 보컬 스타일을 제공해야 한다. | P2 | 각 결과에는 사용자가 이해할 수 있는 근거 문장이 포함된다. |
| ANL-006 | 종합 보컬 특성 | 음역, 음정 안정성, ~~고음 지속력~~, ~~호흡~~, 리듬 대응력, ~~발음 명료도~~를 종합 그래프로 제공해야 한다. | P1 | 미측정 항목은 0점이 아닌 `미측정`으로 표시한다. |
| ANL-007 | 다시 분석 | 기존 <span style="color: #ffffff; background-color: #15803d;">프로필이</span> 있을 때 <span style="color: #ffffff; background-color: #15803d;">새 세션으로 </span><code>다시 분석</code>을 제공해야 한다. | P0 | 새 <span style="color: #ffffff; background-color: #15803d;">결과를</span> <span style="color: #ffffff; background-color: #15803d;">사용자가</span> <span style="color: #ffffff; background-color: #15803d;">저장하기</span> <span style="color: #ffffff; background-color: #15803d;">전까지 </span>기존 <span style="color: #ffffff; background-color: #15803d;">저장</span> <span style="color: #ffffff; background-color: #15803d;">프로필을</span> <span style="color: #ffffff; background-color: #15803d;">유지한다.</span> <span style="color: #ffffff; background-color: #15803d;">저장 중·저장 완료 세션의 결과를 다시 측정한 값으로 변경하지 않는다</span>. |
| ANL-008 | 미측정·평가 불가 처리 | 데이터가 부족한 항목은 임의 점수를 생성하지 않아야 한다. | P0 | 낮은 점수와 평가 불가 상태를 명확히 구분한다. |
| <span style="color: #ffffff; background-color: #15803d;">ANL-009</span> | <span style="color: #ffffff; background-color: #15803d;">닮은 가수 표시</span> | <span style="color: #ffffff; background-color: #15803d;">ECAPA 결과 벡터와 동일 모델 규격의 가수 벡터를 코사인 유사도로 비교하여 닮은 가수를 표시해야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">기본 3명, 최대 5명을 순위·가수명·이미지와 함께 표시한다. 동일 가수를 중복 반환하지 않으며 후보가 부족하면 실제 개수만 표시한다. 점수를 닮음 확률로 바꾸지 않는다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ANL-010</span> | <span style="color: #ffffff; background-color: #15803d;">프로필 선택 저장</span> | <span style="color: #ffffff; background-color: #15803d;">음역과 두 음색 모델의 필수 결과가 준비되면 사용자가 프로필 저장을 선택할 수 있어야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">음역 </span><code><span style="color: #ffffff; background-color: #15803d;">available</span></code><span style="color: #ffffff; background-color: #15803d;">, ECAPA·BYOL 성공, 가수 검색 종료 시 저장할 수 있다. 검색 실패는 저장을 막지 않는다. 일부 모델이 실패하면 부분 결과는 유지하되 저장을 비활성화하고 모델명 없이 추가 분석 필요를 안내한다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ANL-011</span> | <span style="color: #ffffff; background-color: #15803d;">최신 프로필 관리</span> | <span style="color: #ffffff; background-color: #15803d;">사용자당 최신 저장 프로필 1개를 조회할 수 있어야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">새 데이터 저장이 모두 성공한 뒤 교체하고 이전 프로필은 삭제한다. 저장 실패·중복 요청·서버 재시작·작업 완료 순서 변경에도 기존 최신 프로필을 보호하며 재녹음 없이 저장을 재시도할 수 있다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ANL-012</span> | <span style="color: #ffffff; background-color: #15803d;">모델 결과 독립 보관</span> | <span style="color: #ffffff; background-color: #15803d;">ECAPA와 Singer-BYOL 처리 결과를 각각 식별·저장·조회할 수 있어야 한다.</span> | <span style="color: #ffffff; background-color: #15803d;">P0</span> | <span style="color: #ffffff; background-color: #15803d;">모델별 출력 벡터·구간 정보·모델 규격을 독립 보관한다. 두 결과를 합친 단일 대표 벡터나 혼합 점수만으로 대체하지 않는다. 닮은 가수 검색에는 ECAPA 결과만 사용하며 일반 FE 리포트에는 벡터 배열을 포함하지 않는다.</span> |

<span style="color: #ffffff; background-color: #15803d;">ANL-005·ANL-006의 음악 특성 설명·종합 보컬 그래프는 이번 온보딩 완료 조건에 포함하지 않는다. Singer-BYOL 결과로 별도 사용자용 음색 설명·점수를 만들지 않는다.</span>

### 7.4 곡 적합도 및 추천

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| FIT-001 | 음역 적합도 | 사용자 안정 음역과 곡 음역의 적합도를 제공해야 한다. | P0 | 원키와 추천 키 적용 결과를 같은 기준으로 비교한다. |
| FIT-002 | 음색 적합도 | 사용자 음색과 곡 보컬 특성의 적합도를 제공해야 한다. | P1 | 산정 근거를 설명 문장으로 제공한다. |
| FIT-003 | 리듬 적합도 | 리듬 대응력과 곡의 리듬 난이도를 비교해야 한다. | P1 | 데이터가 없으면 평가 불가로 표시한다. |
| FIT-004 | 종합 적합도 | 개별 적합도를 조합한 종합 적합도를 제공해야 한다. | P1 | 적용 가중치와 평가 불가 처리 규칙이 일관돼야 한다. |
| FIT-005 | 추천 키 | 사용자가 무리 없이 부를 수 있는 추천 키를 반음 단위로 제공해야 한다. | P0 | 추천 키 적용 음역과 부담 구간 변화를 함께 표시한다. |
| FIT-006 | 부담 구간 표시 | 편안한 음역을 벗어나는 곡 구간을 부담 구간으로 표시해야 한다. | P0 | 그래프와 구간 설명이 서로 연결된다. |
| REC-001 | 추천곡 순위 목록 | 추천곡 목록을 순위와 함께 제공해야 한다. | P0 | 동일한 곡을 한 목록에 중복 표시하지 않는다. |
| REC-002 | 음역 기반 추천 | 분석 완료 회원에게 음역 기반 개인화 추천을 제공해야 한다. | P0 | 각 곡에 추천 키와 최소 1개의 추천 사유가 포함된다. |
| REC-003 | 음색 기반 추천 | 음색 기반 추천을 제공해야 한다. | P1 | 분석 신뢰도가 낮으면 해당 기준을 사용하지 않는다. |
| REC-004 | 추천곡 상세 전환 | 추천곡을 선택하면 곡 상세, 적합도, 추천 사유가 해당 곡 기준으로 변경되어야 한다. | P0 | 선택 상태가 목록과 상세 화면에서 일치한다. |
| REC-005 | 추천 새로고침 | 추천 조건을 유지한 채 결과를 새로고침할 수 있어야 한다. | P0 | 로딩·성공·결과 없음·실패 상태를 구분한다. |
| REC-006 | 찜·연습 연결 | 곡 상세에서 찜과 연습 시작 기능을 제공해야 한다. | P0 | 선택한 곡과 추천 키가 다음 화면에 유지된다. |
| REC-007 | 고도화 추천 | 유사곡, 분위기, 상황, 시대, 행동 기록 기반 추천을 제공할 수 있어야 한다. | P2 | MVP 이후 별도 추천 정책으로 확정한다. |
| REC-008 | 선호 기반 추천 | 사용자가 선택한 선호 가수와 장르를 개인화 추천의 입력값으로 사용해야 한다. | P0 | 선호 정보가 있는 사용자의 추천 결과에 해당 가수 또는 장르와 관련된 곡이 반영되며, 추천 사유에서 선호 정보가 사용되었음을 확인할 수 있다. 선호 정보가 없으면 다른 추천 기준으로 결과를 제공한다. |
| REC-009 | 인기차트 조회 | 사용자는 현재 인기곡을 순위와 함께 확인할 수 있어야 한다. | P0 | 각 항목에 순위, 곡명, 가수 정보를 표시하며 차트의 기준과 집계 시점을 확인할 수 있다. 로딩·결과 없음·실패 상태를 구분한다. |
| REC-010 | 인기차트 곡 연결 | 사용자는 인기차트에서 곡을 선택하여 곡 상세 화면으로 이동할 수 있어야 한다. | P0 | 선택한 곡의 상세 정보가 표시되며, 로그인 및 분석 상태에 따라 적합도·추천 키·찜·연습 기능을 사용할 수 있다. |

### 7.5 노래 연습

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| PRA-001 | 연습곡 선택 | 사용자는 추천곡 또는 검색·찜 목록에서 연습할 곡을 선택할 수 있어야 한다. | P0 | 곡명, 가수, 원키 및 곡 길이를 확인할 수 있다. |
| PRA-002 | 연습 전 난관 안내 | 연습 전에 예상 고음, 긴 호흡, 빠른 가사, 음정 도약 등 난관을 구간과 함께 안내해야 한다. | P1 | 데이터 부족 시 임의 난관을 생성하지 않는다. |
| PRA-003 | 키 조절 | 사용자는 추천 키를 기준으로 키를 반음 단위로 변경할 수 있어야 한다. | P1 | 변경 즉시 곡 음역과 부담 구간이 갱신된다. |
| PRA-004 | 반주·목표 음정 키 동기화 | 확정한 키를 반주와 목표 음정에 동일하게 적용해야 한다. | P0 | 재생 중 두 정보가 서로 어긋나지 않는다. |
| PRA-005 | 재생 제어 | 사용자는 재생, 일시정지, 재시작, 구간 이동을 사용할 수 있어야 한다. | P0 | 조작 후 현재 시간과 화면 상태가 일치한다. |
| PRA-006 | 가사 싱크 | 현재 가사를 재생 시간에 맞춰 강조해야 한다. | P0 | 가사 싱크가 없으면 연습 전에 지원 불가 사유를 안내한다. |
| PRA-007 | 실시간 음정 비교 | 목표 음정과 사용자의 실시간 음정을 같은 그래프에 표시해야 한다. | P1 | 두 음정의 차이를 색상뿐 아니라 위치·표식으로도 구분한다. |
| PRA-008 | 구간 반복 | 사용자는 후렴 또는 직접 지정한 구간을 반복할 수 있어야 한다. | P1 | 반복 시작·종료점과 현재 반복 상태가 명확히 표시된다. |
| PRA-009 | 마이크 오류 처리 | 마이크 입력이 중단되면 오류 상태와 해결 방법을 안내해야 한다. | P0 | 재연결 또는 연습 종료를 선택할 수 있다. |
| PRA-010 | 연습 종료 확인 | 연습 종료 전 실수로 나가는 경우 종료 여부를 확인해야 한다. | P0 | 취소 시 기존 연습으로 복귀한다. |

### 7.6 연습 리포트와 성장 기록

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| RPT-001 | 우수·개선 구간 분석 | 연습 종료 후 잘 부른 구간과 개선이 필요한 구간을 제공해야 한다. | P0 | 결과가 재생 타임라인과 연결된다. |
| RPT-002 | 구간별 평가 지표 | 음정 정확도, 음 유지, 리듬, 호흡 결과를 구간별로 제공해야 한다. | P1 | 측정하지 못한 항목은 `미측정`으로 표시한다. |
| RPT-003 | 취약 구간 다시 연습 | 개선이 필요한 구간에서 다시 연습을 시작할 수 있어야 한다. | P0 | 선택 구간과 키가 연습 화면에 전달된다. |
| RPT-004 | 연습 기록 저장 | 회원의 곡, 키, 연습 일시, 연습 구간 및 주요 결과를 저장해야 한다. | P0 | 저장 실패 시 성공으로 표시하지 않는다. |
| RPT-005 | 보컬 성장 기록 | 최고음, 안정 음역, 고음 유지 시간 등 성장 변화를 기록해야 한다. | P2 | 기존 최고 기록을 갱신한 경우에만 새 마일스톤을 생성한다. |

### 7.7 찜과 세트리스트

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| FAV-001 | 노래 찜·찜 해제 | 회원은 곡을 찜하거나 찜을 해제할 수 있어야 한다. | P0 | 목록과 상세 화면의 찜 상태가 즉시 일치한다. |
| FAV-002 | 찜 목록 조회 | 찜한 곡을 단일 목록으로 조회할 수 있어야 한다. | P0 | 결과 없음 상태와 곡 수를 표시한다. |
| FAV-003 | 세트리스트 생성·수정 | 사용자는 폴더형 세트리스트를 생성하고 이름을 수정할 수 있어야 한다. | P1 | 중복 이름 처리 규칙을 안내한다. |
| FAV-004 | 세트리스트 곡 관리 | 사용자는 찜한 곡을 하나 이상의 세트리스트에 추가하거나 제거할 수 있어야 한다. | P1 | 원본 찜 상태와 세트리스트 소속을 별도로 관리한다. |
| FAV-005 | 세트리스트 순서 변경 | 세트리스트 상세에서 곡 순서를 변경할 수 있어야 한다. | P1 | 변경된 순서가 재진입 후에도 유지된다. |
| FAV-006 | 곡·세트리스트 삭제 | 곡 또는 세트리스트 삭제 전 대상을 명확히 안내해야 한다. | P1 | 세트리스트 삭제가 곡의 원본 찜을 자동 해제하지 않는다. |

### 7.8 마이페이지와 설정

| ID | 기능 | 요구사항 | 우선순위 | 인수 기준 |
| --- | --- | --- | --- | --- |
| SET-001 | 마이크 권한 설정 | 마이크 권한의 현재 상태를 표시하고 Android 앱 설정으로 이동할 수 있어야 한다. | P0 | 허용됨·허용 안 됨 상태가 실제 시스템 상태와 일치한다. |
| SET-002 | 원본 녹음 저장 설정 | <span style="color: #ffffff; background-color: #15803d;">일반</span> <span style="color: #ffffff; background-color: #15803d;">녹음의</span> <span style="color: #ffffff; background-color: #15803d;">원본 </span>저장 여부를 설정할 수 있어야 하며 기본값은 OFF여야 한다.<span style="color: #ffffff; background-color: #15803d;"> 온보딩 원음에는 별도의 최대 1시간 임시 보관 정책을 적용한다.</span> | P0 | <span style="color: #ffffff; background-color: #15803d;">일반 녹음은 </span>OFF이면 분석 완료 후 삭제한다는 설명을 제공한다.<span style="color: #ffffff; background-color: #15803d;"> 온보딩에서는 프로필 저장이나 일반 원본 저장 설정으로 원음 보관 기간이 연장되지 않음을 안내한다.</span> |
| SET-003 | 음성 데이터 조회·삭제 | 사용자는 저장된 녹음과 음성 분석 데이터를 조회하고 삭제할 수 있어야 한다. | P0 | <span style="color: #ffffff; background-color: #15803d;">온보딩 임시 결과·최신 프로필·ECAPA·BYOL 결과까지 </span>삭제 <span style="color: #ffffff; background-color: #15803d;">범위에 포함하며, </span>범위와 되돌릴 수 없음을 확인한 뒤 처리한다. |
| SET-004 | 모든 음성 데이터 삭제 | 사용자는 모든 음성 데이터를 한 번에 삭제할 수 있어야 한다. | P0 | 계정 탈퇴와 <span style="color: #ffffff; background-color: #15803d;">별도로</span> <span style="color: #ffffff; background-color: #15803d;">제공하고</span> <span style="color: #ffffff; background-color: #15803d;">진행</span> <span style="color: #ffffff; background-color: #15803d;">중인</span> <span style="color: #ffffff; background-color: #15803d;">분석·저장 작업도 무효화한다</span>.<span style="color: #ffffff; background-color: #15803d;"> 삭제 이후 늦게 도착한 결과가 데이터를 복원하지 않는다.</span> |
| SET-005 | 음성 데이터 이용 안내 | 음성 데이터의 수집 목적, 저장 기간, 삭제 정책을 확인할 수 있어야 한다. | P0 | 개인정보 처리방침과 앱 내 설명이 일치한다. |
| SET-006 | 카운트인·진동 설정 | 시작 전 카운트인과 진동 피드백을 설정할 수 있어야 한다. | P1 | 변경 내용이 다음 연습부터 반영된다. |
| SET-007 | 알림 기능 제외 | 알림 관련 메뉴와 설정은 제공하지 않는다. | P0 | 알림 아이콘, 알림 설정, 알림 권한 요청이 존재하지 않는다. |

## 8. 시스템 권한 요구사항

| ID | 권한/기능 | 정책 | 우선순위 |
| --- | --- | --- | --- |
| PER-001 | `RECORD_AUDIO` | 측정 또는 연습 시작 직전에만 요청하며 거부 시 대체 안내를 제공한다. | P0 |
| PER-002 | 인터넷 사용 | 서버 분석·추천·동기화를 위해 매니페스트에 선언한다. 별도 사용자 팝업은 없다. | P0 |
| PER-003 | 네트워크 상태 확인 | 오프라인 및 재시도 안내가 필요할 때만 사용한다. | P0 |
| PER-004 | 진동 | 진동 피드백 기능을 제공하는 경우에만 사용한다. | P1 |
| PER-005 | 저장공간 | 광범위한 읽기·쓰기 권한을 요청하지 않고 시스템 파일 선택기를 사용한다. | P0 |
| PER-006 | 알림 | `POST_NOTIFICATIONS`를 선언하거나 요청하지 않는다. | P0 |
| PER-007 | 백그라운드 마이크 | MVP 1 에서는 사용하지 않으며 포그라운드 화면에서만 녹음한다. | P0 |
| PER-008 | 카메라·위치·연락처·전화 | 현재 서비스 범위에서 요청하지 않는다. | P0 |

## 9. 상태 및 예외 처리

| ID | 상황 | 요구 동작 |
| --- | --- | --- |
| ERR-001 | 마이크 권한 거부 | 제한 기능과 설정 경로를 안내한다. |
| ERR-002 | 무음·저음량·과도한 잡음 | 문제 원인과 재측정 방법을 안내한다. |
| ERR-003 | 분석 데이터 부족 | 점수를 생성하지 않고 평가 불가 또는 재측정 필요로 표시한다. |
| ERR-004 | 곡 음역 정보 없음 | 추천 키와 적합도를 평가 불가로 표시한다. |
| ERR-005 | 추천 결과 없음 | 조건 완화 또는 기본 추천으로 이동할 방법을 제공한다. |
| ERR-006 | 반주·가사·음정·구간 데이터 누락 | 연습 시작 전에 누락된 기능과 제한 범위를 안내한다.<span style="color: #ffffff; background-color: #15803d;"> 온보딩에서는 MR·가사 누락 시 다른 곡 선택을, 2절·하이라이트 시각 누락 시 해당 시작 위치의 이용 불가를 안내한다.</span> |
| ERR-007 | 네트워크 중단 | 가능한 범위에서 입력 상태를 보존하고 재시도를 제공한다. |
| ERR-008 | 저장 실패 | 성공 상태를 표시하지 않고 <span style="color: #ffffff; background-color: #15803d;">기존 최신 프로필을 유지한다. 임시 결과가 유효하면 새 녹음 없이 </span>다시 저장할 <span style="color: #ffffff; background-color: #15803d;">수</span> <span style="color: #ffffff; background-color: #15803d;">있어야 한다</span>. |
| ERR-009 | <span style="color: #ffffff; background-color: #15803d;">로그인 </span>세션 만료 | 재로그인 후 안전하게 복귀할 수 있는 경로를 제공한다.<span style="color: #ffffff; background-color: #15803d;"> 분석 세션 만료와 구분한다.</span> |
| ERR-010 | 결과 삭제 | 삭제 대상을 확인하고 완료 또는 실패 결과를 명확히 표시한다. |
| <span style="color: #ffffff; background-color: #15803d;">ERR-011</span> | <span style="color: #ffffff; background-color: #15803d;">임시 분석 세션 만료</span> | <span style="color: #ffffff; background-color: #15803d;">재로그인으로 복구되는 것으로 안내하지 않고 새 측정을 시작할 수 있게 한다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ERR-012</span> | <span style="color: #ffffff; background-color: #15803d;">재시도용 원음 만료</span> | <span style="color: #ffffff; background-color: #15803d;">음색 모델 재처리에 새 녹음이 필요함을 안내한다. 이미 성공한 ECAPA 결과로 검색만 재시도할 때는 원음을 요구하지 않는다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ERR-013</span> | <span style="color: #ffffff; background-color: #15803d;">일부 음색 처리 실패</span> | <span style="color: #ffffff; background-color: #15803d;">음역·닮은 가수 등 성공 결과는 유지하고 저장을 비활성화한다. 개별 모델명·상태 대신 추가 분석 필요와 재시도를 안내한다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ERR-014</span> | <span style="color: #ffffff; background-color: #15803d;">닮은 가수 검색 실패·후보 없음</span> | <span style="color: #ffffff; background-color: #15803d;">검색 실패는 재시도, 후보 없음은 결과 없음으로 구분한다. 음역과 필수 모델 결과가 준비됐다면 저장을 막지 않는다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ERR-015</span> | <span style="color: #ffffff; background-color: #15803d;">녹음 종료 조건 미충족</span> | <span style="color: #ffffff; background-color: #15803d;">MR 재생 중 누적 녹음 30초 미달 또는 MR 재생 중이라는 이유를 안내한다. 30초 이상 확보와 MR 일시정지를 모두 충족하기 전에는 종료 버튼을 활성화하지 않는다.</span> |
| <span style="color: #ffffff; background-color: #15803d;">ERR-016</span> | <span style="color: #ffffff; background-color: #15803d;">이전 작업의 늦은 결과·저장 버전 충돌</span> | <span style="color: #ffffff; background-color: #15803d;">재녹음·재시도 이전 결과는 현재 세션 결과에 반영하지 않는다. 저장 대상 버전이 달라졌으면 최신 결과를 확인하게 한다.</span> |

## 10. 데이터 요구사항

### 10.1 사용자

- 사용자 식별자, 이메일, 닉네임, 인증 유형
- 가입·수정·탈퇴 일시
- 개인정보 및 원본 녹음 저장 설정

### 10.2 음성 분석

#### <span style="color: #ffffff; background-color: #15803d;">10.2.1 기기 음역 프로필</span>

- <span style="color: #ffffff; background-color: #15803d;">측정 식별자, </span><code><span style="color: #ffffff; background-color: #15803d;">guided-range-v7</span></code><span style="color: #ffffff; background-color: #15803d;">, 측정 완료 시각, 출처 </span><code><span style="color: #ffffff; background-color: #15803d;">ON_DEVICE</span></code><span style="color: #ffffff; background-color: #15803d;">.</span>
- <code><span style="color: #ffffff; background-color: #15803d;">available</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">partial</span></code><span style="color: #ffffff; background-color: #15803d;">, </span><code><span style="color: #ffffff; background-color: #15803d;">insufficient_data</span></code><span style="color: #ffffff; background-color: #15803d;"> 품질 상태와 실제 성공한 최저·최고 MIDI. 음표 MIDI는 36~84이며 성공한 음이 없으면 최저·최고는 비워 둔다.</span>
- <span style="color: #ffffff; background-color: #15803d;">음별 시도·성공 횟수, 측정·성공 상태, 음정 정확도·흔들림·유지 시간 등 기존 v7 지표, 탐색·종료·경계 정보.</span>
- <span style="color: #ffffff; background-color: #15803d;">표시용 음역은 최저·최고를 잇는 연속 범위다. 세부 음별 지표는 실제 측정값을 보존하며 표시 범위를 채우기 위해 미측정 음을 성공으로 바꾸지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">BE는 중복 음·유한 수치·범위와 음별 결과의 일관성을 검사한다. 별도 음역 임베딩 모델이나 서버 음역 재분석 결과를 만들지 않는다.</span>

#### <span style="color: #ffffff; background-color: #15803d;">10.2.2 음색 입력과 모델별 처리 결과</span>

| <span style="color: #ffffff; background-color: #15803d;">구분</span> | <span style="color: #ffffff; background-color: #15803d;">보관 데이터·규칙</span> |
| --- | --- |
| <span style="color: #ffffff; background-color: #15803d;">녹음 입력</span> | <span style="color: #ffffff; background-color: #15803d;">녹음·세션·선택곡 식별자, MR 시작 선택과 실제 시작 시각, MR·가사 데이터 버전, 녹음 시작·종료·일시정지·재개 구간, MR 재생 중 실제 누적 녹음 시간과 30초 확보 여부·판정 버전, 품질 정보</span> |
| <span style="color: #ffffff; background-color: #15803d;">원음 형식</span> | <span style="color: #ffffff; background-color: #15803d;">사용자 마이크 입력 WAV, PCM 16-bit, mono, 44.1 또는 48 kHz. 최대 시간·용량은 수동 종료 흐름에 맞춰 별도 확정</span> |
| <span style="color: #ffffff; background-color: #15803d;">ECAPA 결과</span> | <span style="color: #ffffff; background-color: #15803d;">192차원 모델 출력과 구간별 벡터·시각·유효 여부. 독립된 결과 식별자와 저장 참조로 관리</span> |
| <span style="color: #ffffff; background-color: #15803d;">Singer-BYOL 결과</span> | <span style="color: #ffffff; background-color: #15803d;">1000차원 모델 출력과 구간별 벡터·시각·유효 여부. ECAPA 결과와 분리하여 식별·저장·조회</span> |
| <span style="color: #ffffff; background-color: #15803d;">모델 규격</span> | <span style="color: #ffffff; background-color: #15803d;">모델별 버전, 체크포인트 해시, 전처리 버전, 차원, 정규화 방식</span> |
| <span style="color: #ffffff; background-color: #15803d;">모델 입력 구간</span> | <span style="color: #ffffff; background-color: #15803d;">기본 4초 창·2초 간격, 유효 구간 최소 5개·최대 12개를 두 모델에 동일하게 사용. 일시정지·재개로 끊긴 입력을 가로질러 하나의 연속 구간을 만들지 않음</span> |
| <span style="color: #ffffff; background-color: #15803d;">전처리</span> | <span style="color: #ffffff; background-color: #15803d;">ECAPA는 창별 RMS 0.1·16 kHz·L2 정규화, Singer-BYOL은 창별 peak 정규화·44.1 kHz·L2 정규화</span> |

<span style="color: #ffffff; background-color: #15803d;">이 개정에서는 </span><strong><span style="color: #ffffff; background-color: #15803d;">두 모델의 처리 결과를 독립적으로 보관하는 방식</span></strong><span style="color: #ffffff; background-color: #15803d;">을 적용한다. 이를 단일 대표 벡터나 혼합 점수로 축약하여 원래 모델 결과를 대체하지 않는다. 향후 혼합 점수를 도입하면 비교 대상·모델별 점수·가중치·산식 버전을 별도 계약으로 정의해야 하며, 이번 온보딩에서는 생성하지 않는다.</span>

<span style="color: #ffffff; background-color: #15803d;">닮은 가수는 ECAPA 결과 벡터와 같은 규격의 가수 벡터를 코사인 유사도로 비교한다. Singer-BYOL 결과나 두 모델 혼합 점수를 이 검색에 사용하지 않는다. 구간별 유사도를 가수별 순위 점수로 집계하는 세부 규칙은 15절에서 확정한다. 검증된 가수 ID가 없는 데이터는 후보에서 제외한다.</span>

<span style="color: #ffffff; background-color: #15803d;">모델 차원·체크포인트·전처리 규격이 다르면 결과를 거부한다. 두 모델이 모두 성공하면 사용한 구간 목록이 같아야 한다. 화면의 누적 녹음 30초 조건과 모델의 최소 유효 구간 기준을 구분하며, 30초를 녹음했더라도 무음·잡음·가창 부족이면 분석 자료 부족으로 처리한다.</span>

#### <span style="color: #ffffff; background-color: #15803d;">10.2.3 세션·리포트·저장 프로필</span>

- <span style="color: #ffffff; background-color: #15803d;">인증된 사용자 소유의 세션 ID, 녹음 ID, 작업 ID, 결과 버전, 생성·결과 갱신·만료 시각.</span>
- <span style="color: #ffffff; background-color: #15803d;">음역·두 음색 모델·가수 검색의 내부 상태와 오류·재시도 가능 여부. 모델별 상태는 FE 화면에 표시하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">일반 리포트에는 음역 프로필, 닮은 가수 ID·이름·이미지·순위·코사인 점수, 공개용 처리 안내, 저장 가능 여부를 제공한다. 모델 출력 벡터 배열은 포함하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">저장 작업 ID·상태·실패 사유, 최신 프로필 ID·버전·저장 시각, 모델별 데이터 참조. 조회 가능한 저장 프로필은 사용자당 1개다.</span>
- <span style="color: #ffffff; background-color: #15803d;">MySQL에는 리포트·메타데이터·벡터 참조를, Vector DB에는 모델별 벡터를 저장한다. 새 데이터가 모두 준비된 후 최신 프로필을 교체한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">중복 저장은 한 번만 반영한다. 사용자별 저장 순서를 관리하고 저장 중·완료 세션의 분석 결과를 수정하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">추천에 인계할 항목은 프로필 ID·버전, 기기 음역 프로필·출처, ECAPA·BYOL 독립 결과 참조·모델 규격, 기존 선호 가수·장르다. 온보딩 저장으로 추천 생성 이벤트를 자동 발행하지 않는다.</span>

#### <span style="color: #ffffff; background-color: #15803d;">10.2.4 보관 및 삭제</span>

| <span style="color: #ffffff; background-color: #15803d;">대상</span> | <span style="color: #ffffff; background-color: #15803d;">보관 정책</span> |
| --- | --- |
| <span style="color: #ffffff; background-color: #15803d;">입력 없는 세션</span> | <span style="color: #ffffff; background-color: #15803d;">생성 후 24시간</span> |
| <span style="color: #ffffff; background-color: #15803d;">미저장 분석 결과</span> | <span style="color: #ffffff; background-color: #15803d;">마지막 분석 결과 생성 후 24시간. 단순 조회로 연장하지 않음</span> |
| <span style="color: #ffffff; background-color: #15803d;">업로드한 음색 원음</span> | <span style="color: #ffffff; background-color: #15803d;">업로드 후 최대 1시간. 두 모델 성공 시 조기 삭제 가능. 재시도로 최대 보관 시각을 연장하지 않음</span> |
| <span style="color: #ffffff; background-color: #15803d;">최신 저장 프로필</span> | <span style="color: #ffffff; background-color: #15803d;">새 프로필로 교체하거나 사용자가 음성 데이터 삭제·탈퇴할 때까지</span> |

<span style="color: #ffffff; background-color: #15803d;">프로필 저장은 원음 장기 보관 동의가 아니다. 세션 취소 시 임시 결과를 폐기하고, 음성 데이터 삭제·탈퇴 시 원음·음역·두 모델 결과·프로필과 관련 진행 작업을 함께 정리한다. 늦은 callback이나 저장 완료가 삭제 데이터를 복원하지 않아야 한다.</span>

#### <span style="color: #ffffff; background-color: #15803d;">10.2.5 후속 분석 항목</span>

<span style="color: #ffffff; background-color: #15803d;">호흡·리듬·발음·종합 보컬 특성 등은 해당 후속 기능에서 실제로 측정한 경우에만 보관한다. 이번 음성 온보딩의 필수 산출물이나 임의 생성 점수로 포함하지 않는다.</span>

### 10.3 곡

- 곡 식별자, 곡명, 가수, 앨범 이미지
- 장르, 분위기, 발매 연도, BPM, 원키
- 최저음·최고음, 음별 지속 시간, 가사 밀도, 프레이즈 길이
- 보컬 특성, 난이도
- 반주 오디오, `notes`, `lyrics`, `sections` 데이터
- <span style="color: #ffffff; background-color: #15803d;">온보딩 곡 검색용 곡명·가수명, MR·가사 제공 가능 여부, 2절·하이라이트 시작 시각과 검증 상태. 각 시각은 MR·가사와 같은 곡 타임라인을 사용한다.</span>

### 10.4 추천·찜

- 추천 대상 회원 사용자
- 추천곡, 순위, 기준, 추천 키, 적합도, 추천 사유, 추천 시각
- 찜 여부, 세트리스트 식별자·이름·곡 순서

### 10.5 연습

- 사용자, 곡, 선택 키, 연습 구간
- 시간별 사용자 음정과 목표 음정
- 리듬 기록, 연습 시간, 구간별 결과, 연습 일시

## 11. 음원 처리 요구사항

1. 원곡에서 보컬과 반주를 분리한다.
2. 분리한 보컬에서 기본 주파수를 추출하고 시간별 목표 음정으로 변환한다.
3. 가사와 음원의 시간을 정렬해 가사 싱크 데이터를 생성한다.
4. 1절·후렴 등 반복 연습에 필요한 구간 정보를 생성한다.
5. 서버는 원키 반주와 메타데이터를 제공한다.
6. 키 변경 시 반주와 목표 음정은 동일한 반음 수만큼 함께 이동해야 한다.
7. 음원·가사·앨범 이미지의 이용 권한과 출처를 곡 단위로 관리해야 한다.
8. <span style="color: #ffffff; background-color: #15803d;">온보딩 측정곡에 검색 정보·MR·가사와 검증된 2절·하이라이트 시작 시각을 제공한다. 구간 정보가 없는 경우 임의의 시각을 채우지 않는다.</span>

## 12. 비기능 요구사항

### 12.1 성능

- 녹음 중 음량, 진행률 및 현재 음정은 눈에 띄는 끊김 없이 갱신되어야 한다.
- 키 변경 결과는 조작 직후 반영된다고 느낄 수 있어야 한다.
- 연습 중 목표 음정과 사용자 음정의 표시 지연 목표값을 별도로 확정한다.
- 분석·추천·리포트 API의 응답 시간 목표와 타임아웃을 별도로 확정한다.
- <span style="color: #ffffff; background-color: #15803d;">온보딩은 음역 결과 전송 중 음색 녹음을 진행할 수 있다. AI는 두 모델을 독립 처리하고 ECAPA 완료 후 BYOL 완료를 기다리지 않고 닮은 가수 검색을 시작한다.</span>
- <span style="color: #ffffff; background-color: #15803d;">FE는 활성 화면에서 BE를 2초 간격으로 조회하고 백그라운드에서는 중단한다. 복귀 시 저장한 세션 ID로 현재 상태를 조회한다.</span>

### 12.2 보안 및 개인정보

- 비밀번호를 복구 가능한 평문으로 저장하지 않는다.
- 모든 개인 데이터는 본인 계정으로만 접근할 수 있어야 한다.
- 마이크 사용 목적과 음성 데이터 처리 방식을 권한 요청 전에 설명한다.
- 원본 음성 저장 동의와 서비스 개선·모델 학습 동의는 분리한다.
- <span style="color: #ffffff; background-color: #15803d;">일반 녹음은 SET-002의 저장 설정에 따르며, 온보딩 원음은 10.2.4절의 최대 1시간 임시 보관을 적용한다. 프로필 저장으로 원음 보관을 연장하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">FE는 기존 사용자 인증으로 BE에 접근하고 사용자 ID를 요청 본문으로 선택하지 않는다. BE·AI 간 인증 정보는 FE에 전달하지 않는다.</span>
- 데이터 삭제와 회원 탈퇴 결과를 감사 가능한 상태로 기록한다.

### 12.3 접근성 및 UI

- Android 및 Material 3의 탐색, 터치 영역, 텍스트 위계 기준을 적용한다.
- 색상만으로 상태를 구분하지 않고 텍스트·아이콘·선 종류를 함께 사용한다.
- 그래프에는 수치 또는 요약 문장을 제공한다.
- 시스템 글자 크기가 커져도 핵심 정보와 조작 요소가 잘리거나 겹치지 않아야 한다.
- 터치 대상은 인접 요소와 충분히 구분되어야 한다.
- 화면 읽기 기능에서 버튼 이름과 상태를 이해할 수 있어야 한다.

### 12.4 호환성과 안정성

- MVP는 Android 지원 버전 범위를 별도로 확정한다.
- 마이크를 사용할 수 없는 기기 또는 상태에서는 원인을 안내한다.
- 화면 회전 또는 앱 일시 중단 시 녹음·연습 데이터의 손실 정책을 일관되게 적용한다.
- 서버 오류가 발생해도 앱이 종료되지 않고 재시도 경로를 제공해야 한다.

## 13. MVP 범위

### 13.1 포함

- 이메일 회원가입·로그인·비밀번호 재설정·탈퇴
- <span style="color: #ffffff; background-color: #15803d;">기기</span> <span style="color: #ffffff; background-color: #15803d;">음역</span> 측정과 <span style="color: #ffffff; background-color: #15803d;">곡</span> <span style="color: #ffffff; background-color: #15803d;">검색·MR·가사·시작</span> <span style="color: #ffffff; background-color: #15803d;">위치</span> <span style="color: #ffffff; background-color: #15803d;">선택을 포함한 음색 </span>녹음
- <span style="color: #ffffff; background-color: #15803d;">조건 충족 및 MR 일시정지 후 사용자가 선택하는 녹음 종료</span>
- <span style="color: #ffffff; background-color: #15803d;">최저·최고의 연속 음역 표시, ECAPA 기반 닮은 가수, 부분 리포트·선택 저장·최신 프로필 조회</span>
- 음역 기반 추천, 추천 사유, 추천 키
- 곡 적합도 상세
- 곡 선택, 키 조정, 반주·가사·목표 음정·사용자 음정 기반 연습
- 연습 결과와 취약 구간 재연습
- 찜 목록과 기본 세트리스트
- 마이크·원본 녹음·음성 데이터 설정

### 13.2 제외 또는 후순위

- 알림과 푸시 메시지
- 백그라운드 녹음
- 소셜 로그인
- 행동 기록 기반 고도화 추천
- 보컬 성장 마일스톤
- 데스크톱 전용 UI
- 블루투스 기기 검색·관리 기능

### <span style="color: #ffffff; background-color: #15803d;">13.3 이번 음성 온보딩의 작업 경계</span>

<span style="color: #ffffff; background-color: #15803d;">포함: 기존 로그인·선호 설문 재사용, 기기 v7 음역 집계, 곡 검색·선택·MR·가사·시작 위치 연동, 조건부 수동 녹음 종료, BE 경유 두 음색 모델 처리, ECAPA 기반 가수 검색, 모델 결과 독립 보관, 부분 리포트, 재시도·복구·선택 저장·만료·삭제.</span>

<span style="color: #ffffff; background-color: #15803d;">곡 검색과 MR·가사 제공은 음색 측정 입력을 위한 기능이다. 추천 AI 실행·추천 결과 저장·추천 알고리즘 변경, 종합 보컬 그래프·BYOL 설명 점수, 저장 리포트 이력·성장 기록은 이번 온보딩 작업에 포함하지 않는다. 서비스 전체의 추천·연습 요구사항은 해당 기능 범위에서 수행한다.</span>

<span style="color: #ffffff; background-color: #15803d;">Vector DB 제품 선정·배포, 전체 음원 적재와 새 모델 학습은 별도 작업이다. 온보딩 완료에는 측정에 사용할 MR·가사·시작 구간 데이터와 검증된 가수 ID·동일 ECAPA 규격의 검색 데이터가 필요하다.</span>

## 14. 전체 인수 조건

1. <span style="color: #ffffff; background-color: #15803d;">기기</span> <span style="color: #ffffff; background-color: #15803d;">음역</span> <span style="color: #ffffff; background-color: #15803d;">측정</span> <span style="color: #ffffff; background-color: #15803d;">결과의</span> <span style="color: #ffffff; background-color: #15803d;">최저음·최고음을</span> <span style="color: #ffffff; background-color: #15803d;">확인하고</span> <span style="color: #ffffff; background-color: #15803d;">그</span> <span style="color: #ffffff; background-color: #15803d;">사이를</span> <span style="color: #ffffff; background-color: #15803d;">연속 범위로 볼 </span>수 있다.
2. 측정하지 못한 항목은 낮은 점수가 아닌 `미측정` 또는 `재측정 필요`로 표시된다.
3. 분석 완료 회원에게 음역 기반 추천과 추천 키가 제공된다.
4. 추천곡을 선택하면 곡 상세, 추천 사유 및 적합도 정보가 함께 변경된다.
5. 사용자가 확정한 키가 반주와 목표 음정에 동일하게 적용된다.
6. 현재 가사와 사용자 음정이 연습 진행에 맞춰 표시된다.
7. 연습 종료 후 구간별 결과를 확인하고 취약 구간을 다시 연습할 수 있다.
8. 찜한 곡을 조회하고 세트리스트에 분류할 수 있다.
9. 설정에서 마이크 상태와 원본 녹음 저장 여부를 확인하고 음성 데이터를 삭제할 수 있다.
10. 앱 어디에서도 알림 권한을 요청하거나 알림 설정을 표시하지 않는다.

### <span style="color: #ffffff; background-color: #15803d;">14.1 음성 온보딩 인수 조건</span>

1. <span style="color: #ffffff; background-color: #15803d;">곡을 검색·선택하면 해당 MR과 가사가 제공되며, 처음·2절·하이라이트의 시작 위치와 가사 위치가 일치한다. 누락된 구간은 임의로 생성하지 않는다.</span>
2. <span style="color: #ffffff; background-color: #15803d;">누적 녹음 29.9초에서 MR을 일시정지하면 종료 버튼이 비활성이고, 30.0초 이상에서 일시정지하면 활성화된다. 30초 이상이어도 재생 중이면 비활성이며 재개하면 다시 비활성화된다.</span>
3. <span style="color: #ffffff; background-color: #15803d;">재생 중 20초 녹음 후 10초 일시정지하고 다시 10초 녹음하면 누적 확보량은 30초다. 버퍼링·마이크 중단·곡 탐색으로 건너뛴 시간은 합산하지 않는다. 30초 도달·MR 재생 종료만으로 자동 완료·제출하지 않고, 재녹음 시 누적량은 초기화된다.</span>
4. <span style="color: #ffffff; background-color: #15803d;">음역 결과 전송 중 음색 녹음을 진행해도 같은 세션에 연결된다. 음역은 기기 결과로 표시하며 서버 음역 추론을 호출하지 않는다.</span>
5. <span style="color: #ffffff; background-color: #15803d;">음역은 최저·최고 사이를 끊지 않고 표시한다. 표시 방식으로 인해 원래 음별 측정 여부·판정값이 변경되지 않는다.</span>
6. <span style="color: #ffffff; background-color: #15803d;">ECAPA가 완료되면 BYOL을 기다리지 않고 가수를 검색한다. 성공한 음역·가수 결과는 먼저 표시하며 ECAPA·BYOL 이름과 개별 상태를 노출하지 않는다.</span>
7. <span style="color: #ffffff; background-color: #15803d;">닮은 가수는 ECAPA 코사인 유사도로 산출한다. 기본 3명·최대 5명이며 중복 가수·미검증 ID·임의 확률을 표시하지 않는다.</span>
8. <span style="color: #ffffff; background-color: #15803d;">BYOL 등 필수 내부 처리가 실패하면 부분 결과는 유지하고 저장은 비활성화한다. 추가 분석 필요와 재시도를 모델명 없이 안내한다. 가수 검색 실패만 있을 때는 필수 결과가 준비되면 저장할 수 있다.</span>
9. <span style="color: #ffffff; background-color: #15803d;">두 모델 결과가 독립적으로 저장·조회되고 각각의 차원·체크포인트·전처리 규격을 검증한다. 구간 목록이 다르거나 계약이 불일치하면 거부한다.</span>
10. <span style="color: #ffffff; background-color: #15803d;">재녹음 후 이전 작업의 결과가 현재 결과에 섞이지 않는다. 앱 재실행 시 유효한 세션은 복구하고, 세션·원음 만료는 각각 새 측정·재녹음으로 안내한다.</span>
11. <span style="color: #ffffff; background-color: #15803d;">저장 중복 클릭·Vector DB 실패·저장 도중 재시작에도 기존 최신 프로필이 손상되지 않는다. 사용자당 조회 가능한 저장 프로필은 1개이며, 실패 후 새 녹음 없이 다시 저장할 수 있다.</span>
12. <span style="color: #ffffff; background-color: #15803d;">원음은 최대 1시간, 미저장 결과는 24시간 보관 규칙을 따른다. 삭제·탈퇴 이후 이전 분석·저장 작업이 결과를 복원하지 않는다.</span>
13. <span style="color: #ffffff; background-color: #15803d;">설문 완료와 음성 프로필 저장 완료가 독립적으로 관리되며 온보딩 저장으로 추천 생성 작업이 자동 실행되지 않는다.</span>

<span style="color: #ffffff; background-color: #15803d;">최대 입력 제한과 가수별 구간 점수 집계 규칙은 15절 확정 후 해당 경계값까지 검증한다. 녹음 종료를 위한 누적 30초 기준은 확정이다.</span>

## 15. 추가 확정이 필요한 항목

<span style="color: #ffffff; background-color: #15803d;">기기 음역의 기존 v7 규칙, MR 재생 중 누적 녹음 30초와 일시정지를 함께 요구하는 수동 종료, 온보딩 원음 최대 1시간·미저장 결과 24시간은 확정 사항이다. 다음 세부 항목은 별도로 확정한다.</span>

- <span style="color: #ffffff; background-color: #15803d;">수동 종료 흐름의 최대 녹음 시간·최대 업로드 용량 및 제한 도달 시 안내·재녹음 처리. 안전상 중단을 정상 분석 완료로 처리하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">MR</span> <span style="color: #ffffff; background-color: #15803d;">재생</span> <span style="color: #ffffff; background-color: #15803d;">중</span> <span style="color: #ffffff; background-color: #15803d;">반주</span> <span style="color: #ffffff; background-color: #15803d;">유입에 대한 입력 품질 판정·처리 방식, 유효 음량·잡음·</span>신뢰도 기준<span style="color: #ffffff; background-color: #15803d;">.</span>
- <span style="color: #ffffff; background-color: #15803d;">긴 녹음과 일시정지·재개 구간에서 분석용 4초 창을 최소 5개·최대 12개 선정하는 규칙.</span>
- <span style="color: #ffffff; background-color: #15803d;">독립 보관한 ECAPA 구간 결과의 코사인 유사도를 가수별 순위 점수로 집계하는 방식과 가수 검색 벡터 구성. 두 모델 혼합 점수는 이번 범위에 포함하지 않는다.</span>
- <span style="color: #ffffff; background-color: #15803d;">측정용 곡별 2절·하이라이트 시작 시각의 데이터 출처·검수 담당 및 MR·가사 동기화 확인.</span>
- <span style="color: #ffffff; background-color: #15803d;">후속</span> 종합 적합도 계산 공식<span style="color: #ffffff; background-color: #15803d;">·</span>가중치<span style="color: #ffffff; background-color: #15803d;">.</span>
- 추천 키 최대 조정 범위
- 추천·분석·리포트 응답 시간과 실시간 음정 지연 목표
- 곡 메타데이터, 인기곡, 음원, 가사, 앨범 이미지의 출처와 이용 권한
- Android 최소 지원 버전과 테스트 대상 기기
- <span style="color: #ffffff; background-color: #15803d;">온보딩 외 </span>원본 음성<span style="color: #ffffff; background-color: #15803d;">·</span>분석 결과<span style="color: #ffffff; background-color: #15803d;">·</span>연습 기록의 정확한 보관 기간<span style="color: #ffffff; background-color: #15803d;">. 온보딩 원음 최대 1시간·미저장 결과 24시간은 확정이다.</span>
- 로그인 세션의 만료 시간과 재인증 기준
- 세트리스트 최대 개수와 세트리스트당 최대 곡 수

## 16. 기존 문서와의 주요 변경 이력

| 항목 | 기존 | v2.0 |
| --- | --- | --- |
| 플랫폼 | 모바일·데스크톱 브라우저 혼재 | Android 모바일 MVP로 통일 |
| VOI ID | `VOI-003` 중복 | 음계 측정과 노래 녹음을 별도 ID로 분리 |
| 분석 화면 | 결과 중심 | 결과 없음·분석 중·결과 있음·재분석 상태 추가 |
| 찜 | 단순 목록 | 세트리스트 생성·상세·곡 관리 추가 |
| 설정 | 알림 포함 | 알림 제거, 마이크·원본 녹음·음성 데이터 중심으로 재구성 |
| 권한 | 세부 기준 부족 | 마이크만 런타임 요청, 저장공간·알림·백그라운드 마이크 제외 |
| 개인정보 | 일반 원칙 | 원본 녹음 기본 OFF와 분석 후 삭제 정책 추가 |

### <span style="color: #ffffff; background-color: #15803d;">16.1 v3.1 온보딩 개정 (2026-09-11)</span>

| <span style="color: #ffffff; background-color: #15803d;">항목</span> | <span style="color: #ffffff; background-color: #15803d;">개정 내용</span> |
| --- | --- |
| <span style="color: #ffffff; background-color: #15803d;">온보딩 흐름</span> | <span style="color: #ffffff; background-color: #15803d;">기기 음역 측정 후 곡 선택 기반 음색 녹음, 부분 리포트, 선택 저장</span> |
| <span style="color: #ffffff; background-color: #15803d;">MR·가사</span> | <span style="color: #ffffff; background-color: #15803d;">곡 검색·선택 후 제공하고 처음·2절·하이라이트 시작 선택 지원</span> |
| <span style="color: #ffffff; background-color: #15803d;">녹음 종료</span> | <span style="color: #ffffff; background-color: #15803d;">MR 재생 중 누적 녹음 30초 이상과 MR 일시정지를 모두 만족할 때 수동 종료 버튼 활성화. 30초 자동 종료 없음</span> |
| <span style="color: #ffffff; background-color: #15803d;">결과 화면</span> | <span style="color: #ffffff; background-color: #15803d;">모델별 상태 비노출, 최저·최고의 연속 음역 표시, ECAPA 코사인 유사도 기반 닮은 가수</span> |
| <span style="color: #ffffff; background-color: #15803d;">결과 보관</span> | <span style="color: #ffffff; background-color: #15803d;">ECAPA·Singer-BYOL 결과 독립 보관, 최신 프로필 1개, 실패 시 기존 프로필 유지</span> |
| <span style="color: #ffffff; background-color: #15803d;">보관·삭제</span> | <span style="color: #ffffff; background-color: #15803d;">온보딩 원음 최대 1시간·미저장 결과 24시간, 삭제 후 늦은 작업의 복원 방지</span> |
| <span style="color: #ffffff; background-color: #15803d;">후속 확정</span> | <span style="color: #ffffff; background-color: #15803d;">최대 입력 제한·가창 구간 선정·가수별 점수 집계·MR 구간 데이터</span> |
