# 7키 반주 분산 생성

원키 898곡에서 `-3, -2, -1, +1, +2, +3` 반주를 Opus 128kbps로 만든다. 원본과 S3 원키 객체는 수정하지 않는다.

## 중앙 노트북 준비

```powershell
python pipeline.py preflight
python pipeline.py inventory --source-root D:\instrumental --output work\inventory.json
python pipeline.py shard --inventory work\inventory.json --output-dir work\shards --count 7
python pipeline.py pilot --inventory work\inventory.json --output work\pilot.json
python pipeline.py package --shards-dir work\shards --source-root D:\instrumental --output-dir work\laptops
```

`summary.json`에서 모든 곡이 한 번씩 배정됐는지 확인한다. 각 노트북에는 자기 shard JSON, 해당 원본 디렉터리, 이 폴더를 전달한다. AWS 장기 자격 증명은 전달하지 않는다.
같은 NTFS 볼륨에서 빠르게 준비할 때는 `package`에 `--hardlink`를 붙인다. 다른 노트북으로 전달하기 전에는 일반 복사나 압축으로 실제 파일을 복제한다.

## 각 노트북 렌더

```powershell
python pipeline.py preflight
python pipeline.py render `
  --shard work\shard-01.json `
  --source-root D:\instrumental `
  --output-root D:\key-variants `
  --manifest work\manifest-01.jsonl `
  --jobs 4
```

중단되면 같은 명령을 다시 실행한다. 성공한 곡·키는 건너뛰고 실패 항목만 다시 처리한다. CPU 사용이 불안정하면 `--jobs 3`으로 낮춘다.

## 병합, 업로드, 검증

```powershell
python pipeline.py merge --inventory work\inventory.json --manifests "work\manifest-*.jsonl" --output work\merged.json --report work\merge-report.json
python pipeline.py presign --manifest work\merged.json --bucket micon-b110-prod-apne2-20260909 --output-dir work\upload-plans --expires 43200
python pipeline.py upload --plan work\upload-plans\upload-shard-01.json --output-root D:\key-variants
python pipeline.py verify-s3 --manifest work\merged.json --bucket micon-b110-prod-apne2-20260909 --output work\s3-report.json
```

presigned URL은 렌더 manifest의 SHA-256을 S3 checksum으로 서명한다. URL은 12시간 뒤 만료된다. 만료된 계획은 다시 발급한다.

## DB 등록과 롤백

DB 환경 변수 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`를 설정한다. 기본 실행은 트랜잭션을 롤백하는 리허설이다.

```powershell
python registry.py register --manifest work\merged.json
python registry.py register --manifest work\merged.json --apply
python registry.py rollback --manifest work\merged.json
```

등록은 기존 행이 같은 경로·크기·SHA-256이면 건너뛴다. 값이 다르면 전체 트랜잭션을 중단한다. 롤백은 manifest와 경로·SHA-256이 정확히 일치하는 5,388개 행만 대상으로 한다.
