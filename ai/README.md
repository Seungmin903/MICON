# ai

음성 분석·음원 처리·추천 코드를 둔다. 대용량 데이터셋, 모델 체크포인트, 실험 산출물은 Git 에 직접 커밋하지 않는다.

- VocalScope 엔진(FastAPI)·배치 추출기·웹 검증 도구: develop의 [vocal-scope/](../vocal-scope/README.md)
- 음색 매칭 모델 검증: `feat/timbreModelEval` 브랜치의 `timbre_eval/`
- 추천 알고리즘: `recommend-algo` 브랜치
- 곡 임베딩 S3 패키징·등록·활성화·AI 캐시: [embedding_catalog/](embedding_catalog/), [운영 절차](../docs/operations/EMBEDDING_CATALOG.md). 기존 추천 인덱스 어댑터를 제공하며 스코어링/모델 추출 코드를 복제하지 않는다.

현재 VocalScope는 루트 `vocal-scope/`에 있으며 이 문서 변경에서 코드를 이동하지 않는다. 추가 모델·추천 코드의 통합 위치는 담당자와 별도 합의한다.

Jira 파트 코드는 `AI`.

## 운영 파일·DB 인수 계약 (2026-09-09)

BE·MySQL은 SSAFY 서버, 파일은 개인 AWS 계정의 비공개 S3에 둔다. [S3 운영 계약](../docs/operations/S3_VOICE_STORAGE.md)을 공통 기준으로 사용한다. 2026-09-09 개인 버킷·BE 전용 IAM·서버 키 파일과 CLI 직접 연결 검증은 완료했다. BE 컨테이너·AI 통합 검증은 미완료이며 AI 서비스의 실제 배포 위치·인증은 별도 확정한다. BE 키를 AI/팀원에게 공유하지 않는다.

- 녹음 분석 요청에서는 BE가 발급한 만료 있는 presigned GET URL을 사용한다. AI 분석 API나 Android에 BE의 AWS 키를 전달하지 않는다. AI 실행 환경의 outbound HTTPS·다운로드 시간·URL 만료 처리를 검증한다.
- 담당자 로컬의 승인된 데이터는 별도 업로더 권한으로 `catalog/<담당자>/<batch-id>/`에 올린다. 대용량 원본·키·DB 덤프를 Git에 커밋하지 않는다. ECAPA·Singer-BYOL은 전용 prepare/upload 절차를 사용한다.
- 업로드 결과는 곡 ID↔object key, 파일 크기·체크섬·데이터 버전 목록으로 BE 담당자에게 전달한다. MySQL 적재/초기화는 별도 검토된 작업이며 S3 업로드로 자동 등록되지 않는다.
- `catalog/`의 실제 음원 제공·배치 읽기 권한은 담당자가 별도 합의한다. 현재 BE 녹음용 권한에는 포함하지 않는다. Presigned URL 전체를 로그에 남기거나 영구 주소로 저장하지 않는다.
