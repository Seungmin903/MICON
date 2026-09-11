# 인증 API

2026-09-10 기준. 기계 판독용 명세는 [openapi.yaml](openapi.yaml)을 참고한다.
로컬 기본 주소는 `http://localhost:8080`, Nginx를 통한 외부 기본 주소는 `https://j15b110.p.ssafy.io/api/v1`이다. 배포 여부는 별도로 확인해야 한다.

| 기능 | 메서드 | 경로 | Authorization | 성공 상태 |
|---|---|---|---|---|
| 로그인 | POST | `/auth/login` | 불필요 | 200 |
| 회원가입 | POST | `/auth/signup` | 불필요 | 201 |
| 로그아웃 | POST | `/auth/logout` | `Bearer <accessToken>` | 200 |
| 토큰 갱신 | POST | `/auth/refresh` | `Bearer <refreshToken>` | 200 |

## 로그인·회원가입

`Content-Type: application/json`으로 요청한다. `email`, `password`는 필수다. 로그인에서는 기기 정보가 선택이며, 회원가입에서는 사용하지 않는다.

```json
{
  "email": "user@example.com",
  "password": "password123",
  "deviceId": "device-001",
  "deviceName": "Galaxy"
}
```

이메일은 앞뒤 공백 제거·소문자 변환 후 처리한다. 이메일은 최대 255자, 로그인 기기 정보는 각각 최대 100자다. 회원가입 비밀번호는 8~64자이며 로그인 비밀번호는 필수·최대 64자다. 두 요청 모두 비밀번호는 UTF-8 기준 72바이트를 넘을 수 없다.

로그인 성공 시 사용자 정보와 토큰을 반환한다.

```json
{
  "code": "SUCCESS",
  "message": "요청을 처리했습니다.",
  "data": {
    "userId": 42,
    "email": "user@example.com",
    "accessToken": "<accessToken>",
    "refreshToken": "<refreshToken>",
    "accessTokenExpiresAt": "2026-09-10T10:30:00Z"
  }
}
```

회원가입은 계정만 생성하며 로그인 세션이나 토큰을 만들지 않는다. 성공 응답은 다음과 같다. 앱은 완료 안내 후 로그인 화면으로 돌아가 사용자가 직접 로그인하도록 한다.

```json
{
  "code": "SUCCESS",
  "message": "요청을 처리했습니다.",
  "data": null
}
```

주요 오류: 잘못된 입력 `400`, 로그인 정보 불일치 `401`, 비활성 계정 로그인 `403`, 중복 이메일 회원가입 `409`.

## 토큰 갱신·로그아웃

두 요청 모두 JSON 본문이 없다. 갱신에는 **Refresh Token**, 로그아웃에는 **Access Token**을 Authorization Bearer 헤더로 전달한다. `X-Refresh-Token` 헤더는 사용하지 않는다.

갱신 응답은 위 성공 응답의 `data`에서 `userId`, `email`을 제외한 세 필드를 반환한다. 두 토큰을 함께 저장해야 하며 기존 Refresh Token은 재사용할 수 없다. 기본 만료 시간은 Access Token 30분, Refresh Token 30일이다. 갱신 성공 시 Refresh Token 만료 시간도 다시 계산된다.

로그아웃 응답은 `{"code":"SUCCESS","message":"요청을 처리했습니다.","data":null}`이다. 현재 세션을 폐기하므로 해당 세션의 기존 Access Token과 Refresh Token을 사용할 수 없다. 다른 기기의 세션은 유지된다. 토큰 누락·만료·폐기 등 인증 실패 시 `401`을 반환한다.

## 경로 전환

기존 `POST /auth/sessions`, `POST /users`, `DELETE /auth/sessions/current`, `POST /auth/tokens/refresh`를 위 네 경로로 대체했다. 기존 경로 alias는 제공하지 않는다. 서버와 앱을 함께 전환해야 한다. 특히 로그아웃 메서드는 `DELETE`에서 `POST`로 변경되었다.

`GET /users/me`, `DELETE /users/me`, 비밀번호 재설정 API의 경로는 그대로다. 회원용 분석·추천 등은 계속 Access Token 인증을 요구한다.
