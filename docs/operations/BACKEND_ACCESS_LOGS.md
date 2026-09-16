# 배포 서버 접근 로그 — 모바일 QA

BE가 처리한 요청과 BE → DB/S3/FastAPI 통신을 `micon.access` logger의 한 줄 `key=value` 로그로 기록한다. 기능 동작/DB 데이터/API 본문은 바꾸지 않는다. APK 재빌드는 필요 없다.

## EC2에서 실시간 보기

Windows PowerShell에서 접속:

```powershell
ssh -4 -i "C:\Users\SSAFY\Downloads\J15B110T.pem" ubuntu@j15b110.p.ssafy.io
```

EC2 터미널에서 현재 배포 버전의 스크립트를 실행한다:

```sh
release_sha=$(sudo sed -n 's/^RELEASE_SHA=//p' /var/lib/micon/backend-release.env)
sudo sh "/home/ubuntu/micon-releases/$release_sha/infra/deploy/tail_backend_access_logs.sh"
```

`Ctrl+C`로 종료. `recent` 인수를 붙이면 활성 슬롯의 최근 15분/최대 3,000줄 중 접근 로그만 한 번 보여 준다. 실시간 모드는 Nginx의 활성 blue/green 슬롯을 10초마다 다시 확인한다. 전환 감지 전 로그도 읽기 위해 직전 구간을 재생하므로 일부 줄은 중복 표시될 수 있다. Docker 로그 자체가 중복 저장되는 것은 아니다. 비활성 슬롯의 background 작업은 포함하지 않는다.

현재 슬롯만 간단히 보려면:

```sh
case "$(readlink /etc/nginx/micon/backend-active.conf)" in
  */blue.conf) backend=micon-backend-blue ;;
  */green.conf) backend=micon-backend-green ;;
  *) echo "활성 슬롯 확인 필요"; exit 1 ;;
esac
sudo docker logs -f --since 5m "$backend" 2>&1 | grep --line-buffered 'event='
```

이 두 번째 명령은 배포 시 새 슬롯으로 자동 이동하지 않는다. 실패만 보려면 마지막 grep을 `grep --line-buffered -E 'event=.*(status=error|status=[45][0-9][0-9]|slow=true|practice_asset_blocked|api_error)'`로 바꾼다. `ai_http status=0`은 HTTP 응답 전 연결/전송 실패이므로 별도로 확인한다.

## 읽는 순서

| event | 확인할 값 | 의미 |
|---|---|---|
| `http_access` | method, route, status, duration_ms | BE 요청 처리 결과. route는 실제 ID/쿼리를 지운 컨트롤러 경로 템플릿 |
| `db_query` | operation, sql_id, status, duration_ms, sql_state | 실제 JDBC 실행 결과. sql_id는 SQL의 해시 식별자이며 SQL/바인딩 값/조회 결과 아님 |
| `db_connection` | status=error, error, sql_state | 커넥션 풀에서 DB 연결을 얻지 못함 |
| `s3_access` | operation, object_id, status, http_status, aws_code | BE의 S3 get/put/delete 또는 presign_get. object_id는 객체 키의 해시 |
| `ai_http` | host, port, route, status, duration_ms, error | BE → FastAPI 호출. 4xx/5xx는 AI 응답 오류, 0은 응답을 받기 전 실패. 시간은 응답 헤더까지 |
| `ai_job_accepted` | analysis_id, job_ref | 음성 분석 접수 성공. job_ref는 AI job ID의 해시 |
| `ai_callback` | analysis_id, job_ref, component_status, duplicate | 인증된 음성 분석 컴포넌트 콜백 처리 결과 |
| `practice_asset_blocked` | song_id, selected_key, reason | 연습 차단 이유: song_not_ready / instrumental_missing / notes_missing / synced_lyrics_missing / invalid_asset_json |
| `api_error` | code, status, error | BE가 반환한 업무 오류 또는 예상하지 못한 오류의 종류 |

응답 헤더 `X-Request-ID`와 각 줄의 `trace_id`로 같은 요청을 묶는다. UUID 형식의 입력 ID만 허용하며 그 외에는 새 UUID를 만든다. 이 ID를 BE → AI 요청 헤더에도 전달한다. 예:

```sh
sudo docker logs --since 15m "$backend" 2>&1 | grep 'trace_id=응답에서_확인한_UUID'
```

비동기 콜백은 별도 HTTP 요청이므로 trace_id가 다를 수 있다. 이때 접수와 콜백의 `analysis_id`/`job_ref`를 연결한다. background DB 작업의 trace_id는 `-`일 수 있다. 이 구현은 스레드 간 자동 추적 시스템이 아니다.

예: `practice_asset_blocked reason=song_not_ready` 다음 `api_error`, `http_access status=409`가 나오고 `s3_access`가 없다면, S3를 호출하기 전에 DB 준비 상태에서 차단된 것이다. 이 로그는 부족한 MR/노트/가사를 생성하거나 준비 상태를 수정하지 않는다.

## 범위와 주의사항

- `presign_get status=ok http_status=0`은 URL 서명 생성 성공이며, S3 파일 존재/실제 다운로드 성공의 증거가 아니다. 앱/AI가 그 URL로 S3에 직접 접근한 GET은 BE를 통과하지 않아 여기 기록되지 않는다. 그 구간은 클라이언트 로그 또는 별도로 승인해 설정할 S3 server access logs/CloudTrail data events가 필요하다. 이번 변경에서 AWS 로그 서비스나 추가 과금 설정을 켜지 않는다.
- `ai_http status=202`는 작업 접수이며 분석 완료가 아니다. FastAPI 프로세스 내부 추론 오류/worker 로그는 AI 노트북에서 확인해야 한다. 이번 변경은 그 로그를 EC2로 수집하는 시스템이 아니다.
- DB 로그는 이 BE의 JDBC 실행이다. 다른 관리 도구/AI/다른 컨테이너가 DB에 접근한 이력까지 수집하는 DB 감사 로그가 아니다. SQL 성공은 필요한 행/컬럼이 모두 준비되었다는 뜻도 아니다.
- 헤더, 토큰, 이메일, 요청/응답 본문, 음성/임베딩, presigned URL, S3 객체 키, SQL/바인딩/결과 및 예외 메시지를 새 접근 로그에 넣지 않는다. 사용자 ID를 trace_id로 사용하지 않는다. 인프라 주소/곡 ID/해시도 운영 메타데이터이므로 로그 원문을 공개하지 않는다.
- 정상 health 요청과 background DB 쿼리는 DEBUG, 일반 요청은 INFO, 실패/느린 DB 쿼리는 WARN이다. `ACCESS_LOG_LEVEL=DEBUG`는 필요한 기간에만 사용한다. `DB_ACCESS_LOG_ENABLED=false`로 DB 계측을 끌 수 있다. 환경변수 변경은 컨테이너 재생성 후 적용된다.
- BE Docker 로그는 슬롯당 `20m × 5`로 순환한다. 디스크 증가를 제한하지만 장기 보관/감사 보관은 아니다. 컨테이너 삭제 시 기존 로그는 사라질 수 있다. 기존 슬롯의 로깅 설정은 그 슬롯이 다음에 재생성될 때 적용된다.

## 검증

`backend`에서 `./gradlew test bootJar`; 저장소 루트에서 `python -m unittest discover -s backend/contract_tests -v`와 `python -m unittest discover -s infra/deploy -p 'test_*.py' -v`를 실행한다. 신규 테스트는 실제 H2 쿼리/연결 실패, HTTP 상태/네트워크 오류, trace ID 정리, 민감 값 제외, WAV streaming body 유지, S3 성공/실패 로그를 검증한다. 운영 반영 후 읽기 전용 API 요청의 응답 헤더와 실제 활성 슬롯 로그를 함께 확인한다.
