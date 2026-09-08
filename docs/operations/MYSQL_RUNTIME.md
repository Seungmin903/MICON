# MySQL 운영 실행 환경

Jira S15P21B110-153 / 2026-09-08. 백엔드의 MySQL 드라이버·Flyway와 병합된 150 운영 계약을 기준으로 한다.

## 채택 구성

- MySQL 8.4 LTS, 정확한 이미지 `mysql:8.4.11`과 amd64에서 확인한 digest를 함께 고정한다.
- `micon_mysql_data` named volume에 `/var/lib/mysql`을 영속화한다.
- `micon_backend` 내부 Docker 네트워크만 사용하고 호스트 포트를 게시하지 않는다.
- root, migration, application 계정을 분리한다. `micon_app`에는 `SELECT, INSERT, UPDATE, DELETE`만 부여한다.
- 비밀번호 3개는 서버 `/etc/micon/secrets/`의 `root:root 600` 파일로 관리하고 Git·Compose·로그에 값을 넣지 않는다.
- Redis는 현재 백엔드 의존성이 없다는 150 계약에 따라 구성하지 않는다.

## 공유 MICON.sql 처리

다운로드로 전달된 `MICON.sql`은 운영 DB에 직접 실행하지 않는다. 다음 문제가 있어 Jira 149 담당자가 순서가 보장된 Flyway migration으로 정정해야 한다.

- `ENUM` 값이 없는 컬럼과 `VARC HAR` 오타가 있다.
- 외래키 컬럼에 잘못된 `DEFAULT AUTO_INCREMENT`가 반복된다.
- 현재 인증 migration의 `users`, `refresh_token`과 공유 DDL의 `USER`, `REFRESH_TOKEN` 정의가 충돌한다.
- 백엔드 곡·추천 조회는 대문자 테이블을 사용하지만 현재 Flyway에는 해당 테이블 migration이 없다.
- 일부 필수 외래키와 자동 증가 정책, 초기 곡·추천 데이터가 없다.

현재 승인된 V1·V2는 인증 및 비밀번호 재설정 테이블만 만든다. 곡·추천 API 운영 성공은 149의 정정 migration과 seed 검증 이후 판단한다.

## 서버 최초 준비

아래 명령은 예시이며 비밀번호를 명령 인자나 터미널 기록에 넣지 않는다. 실제 설치 시 서버에서 무작위 hex 값을 파일로 직접 생성하고 파일 모드를 `600`, 디렉터리를 `700`으로 제한한다.

```sh
sudo install -d -m 700 -o root -g root /etc/micon/secrets
sudo sh -c 'umask 077; openssl rand -hex 32 > /etc/micon/secrets/mysql_root_password'
sudo sh -c 'umask 077; openssl rand -hex 32 > /etc/micon/secrets/mysql_migration_password'
sudo sh -c 'umask 077; openssl rand -hex 32 > /etc/micon/secrets/mysql_app_password'
sudo install -d -m 755 -o root -g root /opt/micon
```

Compose 원본을 `/opt/micon/` 아래에 배치한 다음 기동한다. 앱 계정 bootstrap은 멱등이므로 컨테이너를 재생성한 뒤 다시 실행할 수 있다. 기존 볼륨에 다시 적용하려고 볼륨을 삭제하지 않는다.

```sh
sudo docker compose -f /opt/micon/infra/compose/mysql.compose.yml config --quiet
sudo docker compose -f /opt/micon/infra/compose/mysql.compose.yml up -d mysql
sh /opt/micon/infra/mysql/bootstrap-mysql.sh /opt/micon/infra/compose/mysql.compose.yml
sh /opt/micon/infra/mysql/verify-mysql.sh /opt/micon/infra/compose/mysql.compose.yml
```

`docker compose config`, `docker inspect`, 환경 전체 출력은 비밀값을 노출할 수 있어 CI 로그에 그대로 남기지 않는다. 검증 스크립트는 비밀값을 출력하지 않는다.

공식 entrypoint는 root·migration 계정까지만 만든다. 앱 계정은 별도 bootstrap 스크립트가 컨테이너 root로 secret을 읽어 생성한다. 따라서 호스트 secret의 권한을 완화하지 않아도 되며, bootstrap은 재실행해도 같은 상태가 된다.

## 백엔드 연결 계약

- migration 단계: `DB_URL=jdbc:mysql://mysql:3306/micon?...`, 사용자 `micon_migrator`, migration 비밀번호 파일을 실행 환경에서 읽어 주입한다.
- 애플리케이션 단계: 같은 URL, 사용자 `micon_app`, 앱 비밀번호를 주입한다. 별도 migration 단계가 준비되기 전에는 앱 Flyway를 임의로 끄지 않는다.
- DB health의 `SELECT 1` 성공이 readiness 필수 조건이다. DB 실패 시 신규 버전으로 Nginx를 전환하지 않는다.

## 검증과 완료 경계

이 구성만으로 153 완료가 아니다. 빈 DB 초기화, V1·V2 Flyway 실행, 계정 권한, 외부 포트 차단, 볼륨 영속성, 재부팅 자동 복구를 실제 서버에서 기록해야 한다. 149 migration 전에는 곡·추천 API 정상 동작을 주장하지 않는다.

공식 근거: [MySQL 8.4 LTS 릴리스 모델](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html), [Docker Official Image — MySQL](https://hub.docker.com/_/mysql).
