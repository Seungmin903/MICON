# frontend

클라이언트 코드를 둔다. MVP 클라이언트는 Android 앱 하나다.

| 폴더 | 내용 |
|---|---|
| `android/` | MICON Android 앱 (Kotlin · Jetpack Compose). 시작 절차는 [android/README.md](android/README.md) |

Jira 파트 코드는 `FE`.

운영 구조는 [SSAFY BE·MySQL + 개인 AWS S3](../docs/operations/S3_VOICE_STORAGE.md)다. Android는 BE API와 발급된 만료 URL로 녹음을 처리하며 AWS 키·프로필 파일을 앱에 넣지 않는다. 개인 S3 생성·운영 연결은 아직 미완료다.
