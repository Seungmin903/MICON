# 배포 준비와 실기기 QA 현황

기준일: 2026-09-21 KST. 이 문서는 현재 완료된 항목과 별도 확인이 필요한 항목만 기록한다. 과거 준비 과정은 Git 이력에서 확인한다.

## 현재 운영 구조

- BE·MySQL·Nginx: SSAFY 서버 `j15b110.p.ssafy.io`
- 곡·반주·녹음 파일: 개인 AWS 계정의 비공개 S3
- Android API 기준 주소: `https://j15b110.p.ssafy.io/api/v1/`
- 곡 카탈로그와 연습 자산: Android 번들 SQLite가 아니라 BE API에서 조회
- 서버 배포: `main` 전용 Jenkins Backend Pipeline
- Android MR 검증: `develop` 대상 GitLab MR 전용 Jenkins 작업

세부 계약은 [BE 운영 실행 계약](BACKEND_RUNTIME_CONTRACT.md), [S3 운영 계약](S3_VOICE_STORAGE.md), [Android Jenkins CI](JENKINS_ANDROID_CI.md), [Backend Jenkins CD](JENKINS_BACKEND_CD.md)를 따른다.

## 확인 완료

| 영역 | 확인 결과 |
|---|---|
| 키별 반주 S3 | 5,394개, 22,434,871,017 bytes 업로드. 899곡 × 원키를 제외한 6개 변경 키이며, 팀 결과 5,388개와 모나리자 보완 6개를 포함한다. |
| 모나리자 보완 | Rubber Band 변환, Opus 128kbps, 원본 대비 길이 차이 6ms 검증 완료. |
| 운영 DB | `INSTRUMENTAL_KEY` 5,286행(881곡 × 6개 변경 키) 등록. 기존 원키를 포함해 운영 카탈로그 881곡 모두 `-3..+3` 7키 커버리지를 확인했다. |
| 재실행 안전성 | 등록 작업 재실행 결과 `alreadyExact: 5286`으로 중복·변경 없이 통과했다. |
| DB에 없는 렌더 | 18곡은 S3에만 보존했으며 잘못된 메타데이터를 DB에 등록하지 않았다. |
| Android MR CI | GitLab 이벤트 SHA와 checkout SHA 일치 검사, `testDebugUnitTest`, `assembleDebug`, APK 구조·서명·API 주소 검증 및 산출물 보관을 수행한다. |
| Backend CD | `main` 전용 Pipeline과 Jenkins 작업이 분리되어 있다. 테스트·불변 archive·비활성 슬롯·readiness·카탈로그 gate 후 Nginx를 전환한다. |
| Release 후보 | `main` 커밋 `e041c4cb`, versionName `0.1.1`, versionCode `4`의 로컬 Release APK를 생성했고 서명·인증서 일치를 확인했다. 게시 완료를 뜻하지 않는다. |

키별 반주의 상세 수치와 롤백 기준은 [키별 반주 운영 전환](../KEY_VARIANT_ROLLOUT.md)에 기록한다.

## 배포 전 확인 필요

- [ ] 최신 Backend Jenkins 실행의 최종 성공 상태, 배포 SHA, 활성 blue/green 슬롯을 기록한다.
- [ ] 운영 API에서 대표 곡의 `/songs/{songId}/mr?selectedKey=-3..3` 응답과 실제 오디오 decode를 smoke test한다.
- [ ] Android 실기기에서 시작 전 키 변경, 재생 위치 유지, 재생 중 변경 잠금, 채점 기준 이동을 확인한다.
- [ ] Release APK를 실제 배포할지 결정하고 게시 위치·접근 대상·SHA-256을 기록한다.
- [ ] 기존 설치본 위에 동일 서명 업데이트가 되는지와 사용자 데이터 보존을 확인한다.
- [ ] Release keystore의 독립 백업과 복구 가능성을 담당자가 확인한다. 키·비밀번호는 Git이나 문서에 기록하지 않는다.
- [ ] 네트워크 중단, 만료 URL, 409/422/5xx 오류의 사용자 안내와 재시도를 검증한다.

## 완료 판정 기준

### Backend 배포

1. Jenkins checkout SHA가 `origin/main`과 일치한다.
2. 테스트와 `bootJar`가 성공한다.
3. 비활성 슬롯의 Flyway·readiness·S3 카탈로그 gate가 통과한다.
4. Nginx 전환 후 공개 API가 기대한 상태로 응답한다.
5. 배포 SHA·이미지 ID·활성 슬롯이 서버 상태 파일에 기록된다.

### Android 배포

1. 승인한 커밋·증가한 `versionCode`·운영 HTTPS 주소로 Release 빌드를 생성한다.
2. APK가 debuggable이 아니고 승인한 인증서로 서명됐는지 확인한다.
3. APK 안에 비밀정보와 로컬 카탈로그 SQLite가 없는지 확인한다.
4. 게시 APK의 SHA-256과 메타데이터를 함께 보관한다.
5. 실기기 업데이트 설치와 핵심 기능 smoke test를 통과한다.

## 롤백 원칙

- Backend는 DB migration이 직전 버전과 호환되는지 사람이 확인한 뒤 [서버 릴리스 Runbook](SERVER_RELEASE_RUNBOOK.md)의 직전 정상 슬롯 전환 절차를 사용한다.
- Android는 이전 정상 소스를 더 높은 `versionCode`로 다시 서명·배포한다. 낮은 버전 강제 설치나 앱 데이터 삭제를 기본 절차로 사용하지 않는다.
- `INSTRUMENTAL_KEY` 롤백은 manifest와 DB 대상 행이 정확히 일치하는지 리허설한 뒤 수행한다. 원본 반주와 S3 렌더 결과는 별도 승인 없이 삭제하지 않는다.
