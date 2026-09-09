# B110 서버 초기 점검 및 방화벽 설정

## 2026-09-09 후속 확인과 저장소 결정

- SSAFY 서버 `j15b110.p.ssafy.io`를 BE·MySQL 실행에 계속 사용하고, S3만 개인 AWS 계정 자원으로 분리했다. [S3 운영 계약](S3_VOICE_STORAGE.md)에 실제 버킷·전용 IAM·서버 키 파일과 10:54 KST의 CLI 업로드/GET/삭제 검증을 기록했다. BE 컨테이너 주입·통합 및 팀원 업로더 발급은 미완료다.
- SSH 읽기 전용 조회에서 Ubuntu 24.04.4 LTS, 메모리 15GiB, 루트 디스크 309G/여유 약 304G, 리전 ap-northeast-2를 확인했다.
- UFW active, inbound 22/80/443 허용 및 outgoing allow 유지. Nginx active·설정 검사 성공, Docker 29.8.0·Compose 5.5.1을 확인했다.
- `micon-mysql-1`은 MySQL 8.4.11 healthy이며 DB 호스트 포트는 게시하지 않았다. DB 데이터/스키마 전체 검증이나 BE/AI 배포 성공을 뜻하지 않는다. [MySQL 운영 구성](MYSQL_RUNTIME.md)
- 서버의 기본 AWS 신원은 SSAFY `AmazonLightsailInstanceRole`이다. S3 버킷 전체 목록·역할 정책 목록 조회는 AccessDenied였다. 이 조회 거부만으로 모든 S3 작업이 불가능하다고 결론 내리지 않는다. 개인 S3에는 별도 개인 계정 전용 신원을 사용하며 SSAFY 역할은 변경하지 않는다.
- S15P21B110-211에서 개인 버킷용 IAM 사용자·키를 생성하고 서버 `/etc/micon/secrets/aws_credentials`에 `root:root 0600`으로 저장했다. 호스트 기본 인증·방화벽·기존 서비스는 유지했으며 추가 포트 개방·SQL 실행·BE 배포는 하지 않았다.

## 아래는 2026-09-07 초기 구축 기록

확인일: 2026-09-07. 실제 SSH 접속 결과를 기록한다. 서버 제공 엑셀은 암호화되어 읽지 못했으므로 아래 사양의 근거는 엑셀이 아니다.

## 접속 및 사양

- 호스트: `j15b110.p.ssafy.io`
- DNS A 레코드: `13.124.156.233`
- SSH 사용자: `ubuntu`
- 로컬 개인키로 인증 성공. 개인키 내용은 출력·복사·업로드하지 않았다.
- `StrictHostKeyChecking=yes`로 기존 알려진 호스트 정보 검증을 유지했다.
- OS: Ubuntu 24.04.4 LTS, x86_64
- CPU: 논리 CPU 4개, Intel Xeon Platinum 8259CL @ 2.50GHz
- 메모리: `free -h` 기준 총 15Gi, 점검 시 사용 683Mi
- 루트 파일시스템: `df -h /` 기준 309G, 사용 2.8G, 여유 307G
- Swap: 없음
- 점검 시 TCP 리슨: 외부 SSH 22, 로컬 DNS 53. 웹/API/DB 리슨 없음.
- 최초 점검 시 `docker`, `nginx` 실행 파일은 PATH에서 확인되지 않았다. 이후 Docker·nginx 설치 결과는 아래에 기록한다.

## 이번에 적용한 변경

기존 UFW는 active이며 기본 정책은 incoming deny, outgoing allow, routed disabled였다. 기존 SSH 22 허용 규칙과 기본 정책은 유지했다.

```sh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

IPv4와 IPv6 모두 규칙 추가를 확인했다. UFW reset/disable/enable, 기존 규칙 삭제, SSH 설정 변경은 하지 않았다.

| 포트 | 적용 상태 |
|---|---|
| 22 | 기존 SSH 허용 유지 |
| 80/tcp | HTTP 허용 추가 |
| 443/tcp | HTTPS 허용 추가 |
| 3306, 8080, 8787 | 외부 허용 추가하지 않음 |

변경 후 별도의 새 SSH 연결에 성공했고 `sudo ufw status numbered`로 규칙을 재확인했다.
방화벽 설정 직후에는 웹 서버가 아직 리슨하지 않았다. 이후 아래 Nginx·HTTPS 단계에서 외부 접속을 확인했다. 클라우드 보안 그룹 설정 자체는 조회하지 않았다.

이번 추가분만 되돌릴 필요가 생기면 대상 확인 후 `sudo ufw delete allow 80/tcp`, `sudo ufw delete allow 443/tcp`를 사용한다. 복구 명령은 실행하지 않았다.

## Docker·Compose 설치 결과 (2026-09-07)

- Docker 공식 Ubuntu APT 저장소의 noble/stable, amd64 패키지를 사용했다.
- 저장소 정의: `infra/bootstrap/docker.sources` → 서버 `/etc/apt/sources.list.d/docker.sources`
- 공식 HTTPS 주소에서 받은 서명 공개키: `/etc/apt/keyrings/docker.asc`
- 확인한 공개키 fingerprint: `9DC858229FC7DD38854AE2D88D81803C0EBFCD88`
- APT 패키지 인덱스의 서명 검증을 유지했다. insecure/trusted=yes 옵션이나 convenience script를 사용하지 않았다.
- 패키지 목록 갱신 후 설치 시뮬레이션을 확인하고 아래 정확한 버전을 지정해 설치했다. 기존 패키지 삭제 0개, 업그레이드 0개, 신규 설치 5개.

| 패키지 | 설치 버전 |
|---|---|
| docker-ce | `5:29.8.0-1~ubuntu.24.04~noble` |
| docker-ce-cli | `5:29.8.0-1~ubuntu.24.04~noble` |
| containerd.io | `2.3.4-2~ubuntu.24.04~noble` |
| docker-buildx-plugin | `0.37.0-1~ubuntu.24.04~noble` |
| docker-compose-plugin | `5.5.1-1~ubuntu.24.04~noble` |

버전 고정은 이번 설치 명령의 재현을 위한 것이다. APT hold나 영구 pin 정책은 설정하지 않았다.
시뮬레이션에 기존 패키지 22개가 not upgraded로 표시됐다. 별도 OS 보안 업데이트 필요성을 점검해야 하며, 이번에 전체 시스템 업그레이드·재부팅은 수행하지 않았다.

### 검증

- Engine Client/Server: `29.8.0`, Compose: `v5.5.1`, Buildx: `v0.37.0` 확인.
- Docker·containerd: `active`, 부팅 자동 시작 `enabled` 확인. 실제 재부팅 시험은 하지 않았다.
- 공식 `hello-world` 이미지를 받은 후 repository digest로 고정해 실행했다.
- 검증 이미지: `hello-world@sha256:5dd0d3e6e255913fc30f90b9f2b1d359cc2cbdb48090cc4b65f1676e203243cc`
- Docker 직접 실행 1회, Compose 실행 1회 모두 `Hello from Docker!`, 종료 코드 0.
- Compose 정의 `infra/bootstrap/compose.smoke.yaml`의 `config --quiet` 통과.
- 두 테스트 모두 network none, read-only, capability 전체 제거, no-new-privileges 설정. 외부 포트·호스트 볼륨·Docker 소켓을 컨테이너에 연결하지 않았다.
- `--rm`으로 테스트 컨테이너는 종료 후 자동 제거됐다. 종료 후 `docker ps -a`는 빈 목록이다. 검증용 이미지 캐시는 남겨뒀다.
- 새 SSH 연결로 Docker 설치 후 상태 조회 성공. UFW active 및 기존 22·80/tcp·443/tcp 허용 규칙 유지.
- 리슨 포트는 설치 전과 동일하게 외부 22와 로컬 DNS 53뿐이다. Docker API TCP 포트도 개방하지 않았다.
- 사용자에게 root 수준 Docker 권한을 추가하지 않았다. `docker` 그룹은 비어 있고 운영 명령은 `sudo docker ...`로 실행한다.

### Docker 네트워크 주의

Docker 설치 후 IPv4 forwarding은 1이고 `DOCKER-USER` 체인이 생성됐다. UFW의 routed 표시는 disabled에서 deny로 바뀌었다. 수동으로 UFW 정책을 변경하지는 않았다.
Docker의 포트 게시가 UFW를 우회할 수 있으므로 UFW의 deny만으로 컨테이너 보호를 보장하지 않는다. DB는 Compose의 호스트 `ports`를 생략한다. 호스트 nginx와 연결할 API만 필요시 `127.0.0.1:호스트포트:컨테이너포트`로 제한하고 외부 차단을 별도 검증한다. 이는 후속 배포 계획이며 현재 API 포트를 게시한 것은 아니다.
현재는 게시된 포트가 없으며 향후 실제 Compose 네트워크의 외부 접근 차단 검증은 별도 단계다.

## Nginx HTTP 설정 결과 (2026-09-07)

- 호스트 패키지 방식으로 `nginx`, `nginx-common` 각각 `1.24.0-2ubuntu7.17` 설치. Ubuntu noble 업데이트·보안 저장소 패키지이며 nginx.org의 별도 최신 버전으로 교체하지 않았다.
- 현재 공개 진입점은 호스트 nginx다. Docker 컨테이너에는 80/443을 중복 게시하지 않는다.
- 적용 원본: `infra/nginx/micon-http.conf`
- 서버 설정: `/etc/nginx/sites-available/micon-http.conf`, 활성화 symlink: `/etc/nginx/sites-enabled/micon-http.conf`
- 원본·서버 파일 SHA-256 일치: `352d9b0a330ddb904f19a06ede4ac44d978d65a3bd32cc433d927b162106395f`
- 패키지 기본 사이트의 활성화 symlink만 `/var/backups/micon/nginx-initial-20260907/default.enabled`로 이동해 비활성화했다. `/etc/nginx/sites-available/default` 원본은 보존했다.
- `nginx -t` 통과 후 reload, 서비스 active·부팅 자동 시작 enabled 확인.
- 적용 직후 첫 로컬 요청은 404였다. 이후 재조회와 외부 검증에서 아래 기대 응답을 확인했다.
- Nginx 버전 번호 노출 차단, 응답 `nosniff`·`no-store` 설정.
- 액세스 로그는 query string·Authorization 헤더·본문을 기록하지 않는 형식이다. 오류 로그는 별도 파일이며 후속 인증 API 연동 시 민감정보 노출을 다시 점검한다.
- Ubuntu 패키지의 일 단위 로그 회전, 14개 보관 및 압축 설정 확인.

| 외부 HTTP 검증 | 기대/실제 결과 |
|---|---|
| 도메인 `/healthz` | 200, `micon-edge`, `scope=nginx-only` |
| 도메인 `/` | 503, `not_ready` |
| 도메인 `/api/v1/health` | 503, 실제 API 미배포 |
| 존재하지 않는 ACME challenge 경로 | 404 |
| 도메인 `/.env` | 503, 파일 제공하지 않음 |
| 잘못된 Host 헤더 | 404 |

HTTP 상태 확인 6종 모두 기대 결과를 확인했다. `/healthz`는 nginx만 검사하므로 백엔드·DB·AI 정상 여부나 통합 배포 완료의 근거가 아니다.
HTTP 단계 당시 리슨은 외부 22·80과 로컬 DNS 53이었다. 이후 TLS 적용으로 443 리슨이 추가됐다.

## HTTPS 적용 결과 (2026-09-07)

- 사용자가 Let's Encrypt 가입 약관에 명시적으로 동의하고 제공한 이메일로 ACME 계정을 등록했다. 이메일 원문과 계정·인증서 비밀키는 저장소에 넣지 않았다. 별도 EFF 이메일 공유는 선택하지 않았다.
- Ubuntu 패키지 Certbot `2.9.0-1` 및 의존성 6개 설치. 기존 패키지 업그레이드·삭제 없이 총 7개 신규 설치.
- `certonly --webroot`로 nginx 중단 없이 HTTP-01 검증. webroot는 `/var/www/micon-acme`.
- 대상/SAN: `j15b110.p.ssafy.io`, ECDSA 인증서. 발급기관: Let's Encrypt `YE1`.
- 유효기간: 2026-09-07 06:55:00 UTC ~ 2026-12-06 06:54:59 UTC (만료: 한국시간 2026-12-06 15:54:59).
- 인증서 경로: `/etc/letsencrypt/live/j15b110.p.ssafy.io/fullchain.pem`
- 비밀키 경로: 같은 디렉터리의 `privkey.pem`. 실제 대상 파일 권한 `600 root:root` 확인. 키 내용은 읽어 출력하거나 로컬로 복사하지 않았다.

### Nginx TLS 설정

- 현재 활성 원본: `infra/nginx/micon-https.conf` → `/etc/nginx/sites-available/micon-https.conf`
- 활성 symlink: `/etc/nginx/sites-enabled/micon-https.conf`
- 이전 HTTP 원본과 활성 symlink는 `/var/backups/micon/nginx-pre-tls-20260907/`에 보존했다. 두 설정을 동시에 활성화하면 로그 형식·서버 정의가 중복되므로 함께 활성화하지 않는다.
- 설정 SHA-256(로컬·서버 일치): `8c92e3f2ebb951aeeb1d74665e5e4a56e5bdb3fb6989a51053db9fcc3364266d`
- `nginx -t` 성공 후 reload 적용. 실패 시 기존 HTTP 활성화 상태로 복구하도록 적용했다. 실제 복구 경로는 실행되지 않았다.
- TLS 1.2·1.3 허용, 세션 티켓 비활성화. 알 수 없는 SNI의 기본 TLS 서버는 handshake를 거부한다.
- HTTP는 고정된 도메인의 HTTPS 주소로 308 리다이렉트하며 경로·쿼리를 유지한다. HTTP ACME challenge 경로는 갱신을 위해 예외로 남겼다.
- HSTS preload/includeSubDomains는 설정하지 않았다.
- 공개 리슨: SSH 22, nginx 80·443. UFW의 기존 허용 규칙 유지. DB/API 포트는 여전히 공개하지 않았다.

### HTTPS 검증

- 외부 Windows PC의 curl로 인증서 검증을 끄지 않고 `/healthz` 200 확인.
- 서버 OpenSSL에서 TLS 1.2와 TLS 1.3 각각 인증서 체인·호스트명 `Verification: OK` 확인.
- HTTP `/healthz` → 308 → HTTPS `/healthz` 200 확인.
- HTTPS `/`·`/api/v1/health`·`/.env`는 503, 존재하지 않는 HTTP ACME challenge는 404 확인.
- 503은 아직 BE/AI를 배포하지 않았음을 나타내는 의도된 응답이다. `/healthz`도 nginx만 검사하므로 서비스 전체 정상으로 해석하지 않는다.

### 자동 갱신

- 배포 훅 원본: `infra/nginx/renewal-reload-nginx.sh`
- 설치 경로: `/etc/letsencrypt/renewal-hooks/deploy/50-micon-nginx`, `0755 root:root`
- 훅 SHA-256(로컬·서버 일치): `a12617cf8c9e0b0c7aa6ff3a3ef776fa6533f514e0dec791d99970f847f5ac27`
- 해당 도메인의 갱신 성공 후에만 `nginx -t` → `systemctl reload nginx.service` 실행. 성공 기록은 journal의 `micon-certbot` 태그로 남긴다.
- `certbot.timer` active·enabled. 배포판 기본 하루 두 번 기준 일정과 최대 12시간 무작위 지연을 유지했다. 별도 cron 중복 등록 없음.
- 자동 실행 명령: `/usr/bin/certbot -q renew --no-random-sleep-on-renew`
- 모의 갱신 명령: `sudo certbot renew --cert-name j15b110.p.ssafy.io --dry-run --run-deploy-hooks --non-interactive`
- 모의 갱신 결과: 성공, 종료 코드 0. staging 환경에서 발급 검증 후 배포 훅 실행을 확인했다. 최초 실행에는 Certbot의 186초 무작위 대기가 있었다.
- 훅 실행 출력의 `error output`은 nginx가 정상 검사 결과를 stderr로 쓰는 데 따른 표기였다. 실제 내용은 syntax ok / test successful이며 journal의 `micon-certbot` 성공 기록과 reload 후 HTTPS 200으로 정상 동작을 확인했다.
- 모의 갱신 후 실제 제공 인증서가 Let's Encrypt YE1, 만료 2026-12-06인 기존 운영 인증서임을 재확인했다. 테스트 인증서로 교체하지 않았다.
- 향후 실제 갱신 성공을 미리 보장하지 않는다. 만료·갱신 오류 감시와 알림 연결은 후속 작업이다.

## 다음 단계

- DB 제품·버전 팀 합의 후 비공개 네트워크, 영속 볼륨, 계정·백업 구성
- 인증서 만료·갱신 오류 모니터링 연결
- BE/AI 실제 실행 버전·경로·인증 계약 확인 후 배포
- Docker 도입 시 포트 게시가 호스트 방화벽과 상호작용하는 방식을 별도로 검증하고 DB/API 포트를 무심코 전체 인터페이스에 게시하지 않는다.

2026-09-07 당시 DB 생성·애플리케이션 배포는 수행하지 않았다. 이후 DB 상태는 문서 상단의 2026-09-09 후속 기록을 우선한다. 재현 가능한 설정 원본과 이 기록은 작업 브랜치의 MR로 공유하며, 개인키·인증서 비밀키·ACME 계정 파일은 포함하지 않는다.

## 참고

- 사용자 제공 `ufw 포트 설정하기.txt`
- [Ubuntu 공식 방화벽 안내](https://ubuntu.com/server/docs/how-to/security/firewalls/)
- [Docker 공식 Ubuntu 설치 안내](https://docs.docker.com/engine/install/ubuntu/)
- [Docker 패킷 필터링과 방화벽](https://docs.docker.com/engine/network/packet-filtering-firewalls/)
- [Ubuntu nginx 설치 안내](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/)
- [Let's Encrypt 가입 약관](https://letsencrypt.org/repository/)
- [Certbot 인증서 발급·갱신 안내](https://eff-certbot.readthedocs.io/en/stable/using.html)
- [Nginx SSL 모듈](https://nginx.org/en/docs/http/ngx_http_ssl_module.html)
