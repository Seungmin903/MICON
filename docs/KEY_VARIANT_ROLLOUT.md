# 키별 반주 운영 전환

## 배포 게이트

1. 3곡 파일럿에서 18개 파일의 decode, 길이 차이 50ms 이하, `-3/0/+3` 청취를 확인한다.
2. 7개 shard manifest를 병합하고 `expectedVariants=5388`, 누락·중복·충돌 0건을 확인한다.
3. S3 HEAD 검증에서 5,388개 모두 크기, MIME, SSE, SHA-256 checksum이 일치하는지 확인한다.
4. `registry.py register` 리허설 후 `--apply`로 DB에 등록한다.
5. DB에서 898곡 각각 `-3..+3` 7개 키가 READY인지 확인한다.
6. `/songs/{songId}/mr?selectedKey=` 대표 21건과 Android 실기기 전환을 smoke test한다.

원본 `instrumental.opus` 898개는 어떤 단계에서도 삭제하거나 덮어쓰지 않는다. 앱 배포는 5번이 통과한 뒤 진행한다.

## 관측 수치

- 노트북별 곡 수, 원본 총 길이, 렌더 총 시간, 실패·재시도 수
- 산출물 수와 총 바이트, 최대 길이 차이
- 키 전환 p50/p95와 전환 뒤 재생 위치 오차
- API 조회 성공률과 422·5xx 건수

키 전환 합격 기준은 팀 Wi-Fi에서 p95 2.5초 이하, 재생 위치 오차 250ms 이하, 전환 중 판정 프레임 0개다.

## 롤백

1. 신규 Android 배포를 이전 버전으로 되돌린다.
2. `registry.py rollback --manifest merged.json`으로 삭제 대상 5,388개가 정확히 일치하는지 리허설한다.
3. `--apply`를 붙여 `INSTRUMENTAL_KEY` 행만 삭제한다.
4. 원키 연습 smoke test를 수행한다.

S3 객체는 복구와 원인 분석을 위해 유지한다. 삭제는 별도 승인 작업으로 진행한다.
