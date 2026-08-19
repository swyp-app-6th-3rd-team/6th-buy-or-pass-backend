# sakila-ddd-template

Spring Boot 4 · JDK 25 프로젝트 템플릿.
DDD 구조 · OAuth2 소셜 로그인 · JWT · QueryDSL · TestContainers 가 **실제로 도는 상태**로 들어 있다.

Sakila 샘플 DB 를 예제로 쓴다. 새 프로젝트를 시작할 때는
[지울 것](#새-프로젝트로-쓸-때-지울-것) 을 따라 예제를 걷어내면 된다.

---

## 5분 안에 띄우기

```bash
# 1. 환경 변수
cp .env.example .env
# JWT_SECRET_KEY 를 채운다 (32바이트 이상)
openssl rand -base64 48

# 2. DB 기동 (앱은 IDE 에서 실행하는 게 기본)
docker compose -f docker-compose-dev.yml up -d
docker compose -f docker-compose-dev.yml ps    # healthy 가 될 때까지 대기

# 3. 애플리케이션
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

compose 파일이 환경별로 나뉘어 있어 `-f` 가 필요하다(`docker-compose-dev.yml` ·
`docker-compose-prod.yml`). 매번 붙이기 번거로우면 `.env` 에 다음을 넣으면 된다.

```bash
COMPOSE_FILE=docker-compose-dev.yml
```

`local` 프로파일은 Sakila 시드 데이터(9MB, 46,273 INSERT)를 로드한다.
**첫 기동은 1~3분** 걸리고 이후에는 Flyway 가 건너뛴다. 스키마만 필요하면 프로파일을 빼면 된다.

앱과 DB 를 함께 컨테이너로 띄우려면:

```bash
docker compose -f docker-compose-dev.yml --profile app up -d
```

### 확인

| URL | 내용 |
|---|---|
| http://localhost:8080/scalar | API 문서 (Scalar) |
| http://localhost:8080/swagger-ui.html | API 문서 (Swagger UI) |
| http://localhost:8080/actuator/health | 헬스체크 |
| http://localhost:9090/actuator/prometheus | 메트릭 (관리 포트 — 서비스 포트에서는 401) |
| http://localhost:3000 | Grafana ([관측성](#관측성) 을 띄운 경우) |

---

## 스택

| 항목 | 버전 | 함정 |
|---|---|---|
| JDK | 25 | — |
| Spring Boot | 4.1.0 | 자동설정이 **모듈별로 분리**됨 (`spring-boot-flyway` 등을 따로 넣어야 한다) |
| Spring Security | 7.1.0 | `AntPathRequestMatcher`·`MvcRequestMatcher` 제거 → `PathPatternRequestMatcher` |
| Jackson | 3.x | groupId 가 **`tools.jackson`** 이다 (`com.fasterxml` 아님) |
| QueryDSL | OpenFeign 포크 7.5 | 본가는 Hibernate 7 미지원. classifier 가 **`:jpa`** (`:jakarta` 아님) |
| MySQL | 8.4 | `lower_case_table_names=0` (리눅스 기본값에 맞춤) |
| springdoc | 3.1.0 | 2.x 는 Boot 4 비호환 |

> Boot 4 는 아직 새 버전이라 **생태계 라이브러리 버전을 개별 확인해야 한다.**
> Boot 3 프로젝트에서 좌표를 그대로 가져오면 대부분 깨진다.

---

## 구조

비즈니스 축으로 먼저 자르고, 그 안에서 계층을 나눈다.

```
com/example/sakila/
├── common/       ApiResponse · PageResponse · ScrollResponse · CursorCodec
├── config/       ClockConfig · QuerydslConfig · ScalarConfig
├── error/        ApiException · GlobalExceptionHandler
│
├── rental/       ★ DDD 참조 구현 — 남길 것
│   ├── domain/   순수 도메인 (프레임워크 의존 0)
│   ├── service/  유스케이스
│   ├── infra/    JPA 엔티티 · Store 구현 · QueryDSL
│   └── controller/
│
├── sakila/       ★ 예제 — 지울 것 (얇은 엔티티 15개)
│
└── auth/         OAuth2 3사 + JWT
```

### DDD 는 `rental` 하나로만 시연한다

Sakila 16개를 전부 풀 DDD 로 만들면 클래스가 60개를 넘어 **템플릿이 아니라 Sakila 앱**이 된다.
`rental` 만 참조 구현으로 두고 나머지는 얇게 뒀다.
근거: [ADR-0001](docs/adr/0001-skeleton-plus-one-reference.md)

`rental` 에서 볼 것:

| 파일 | 무엇을 보여주는가 |
|---|---|
| `domain/Rental.java` | 생성자가 불변식 강제, 상태 전이 규칙, `restore()` 복원 경로 |
| `domain/RentalStore.java` | 저장소 계약을 도메인이 정의 (의존 역전) |
| `infra/RentalEntity.java` | 엔티티가 도메인을 안다 (`toDomain()`/`from()`) |
| `infra/RentalRepository.java` | **package-private** — 컴파일러가 유출을 막는다 |
| `infra/RentalQueryRepositoryImpl.java` | QueryDSL 동적 조건 + keyset 스크롤 |

---

## 인증

Google · Kakao · Naver 소셜 로그인 + JWT.
`.env` 에 클라이언트 값만 채우면 바로 동작한다.

```
GET /oauth2/authorization/google
  → 프로바이더 로그인
  → 콜백
  → 리다이렉트: {redirectUri}?accessToken=...
     + Set-Cookie: refresh_token (HttpOnly)
```

**토큰 전달 규약**
- 액세스 토큰 — 쿼리파라미터로 한 번 전달, 이후 `Authorization: Bearer`
- 리프레시 토큰 — **HttpOnly 쿠키만.** URL 이나 본문에 담지 않는다
  (URL 은 브라우저 히스토리·리퍼러·서버 로그에 남는다)

컨트롤러에서 현재 사용자는 이렇게 받는다:

```java
@GetMapping("/me")
public ApiResponse<MeResponse> me(@CurrentUser Long userId) { ... }
```

보안 설계 근거와 참조 프로젝트에서 고친 결함 11건: [ADR-0006](docs/adr/0006-auth-hardening.md)

---

## 페이징

| 용도 | 타입 | 엔드포인트 |
|---|---|---|
| 번호 페이징 (총 건수 필요) | `Pageable` → `Page` | `GET /api/rentals` |
| 무한 스크롤 | `ScrollPosition` → `Window` | `GET /api/rentals/scroll` |

`Page` 는 `OFFSET n LIMIT m` 이라 뒤로 갈수록 느려지고 count 쿼리도 추가된다.
`Window` 는 keyset 이라 위치와 무관하게 일정하다. rental 이 16,044건이라 실제로 차이가 난다.

**응답에는 Spring 타입을 그대로 내보내지 않는다.** `PageResponse`/`ScrollResponse` 로 변환한다 —
`Page` 를 직렬화하면 `pageable.sort.sorted` 같은 내부 구조가 API 계약이 되기 때문이다.
ArchUnit 규칙이 이를 강제한다.

---

## 테스트

```bash
./gradlew test          # 전체 113건
```

| 계층 | 건수 | Docker |
|---|---|---|
| 도메인·어댑터·서비스 단위 | 56 | 불필요 |
| 아키텍처 (ArchUnit) | 18 | 불필요 |
| 통합 (TestContainers MySQL) | 39 | 필요 |

컨테이너 재사용을 켜면 통합 테스트가 빨라진다:

```bash
echo "testcontainers.reuse.enable=true" >> ~/.testcontainers.properties
```

### 아키텍처 규칙

`ArchitectureTest` 18개가 구조를 지킨다 — 도메인 순수성, 계층 경계, 응답 계약, 템플릿 경계.

**규칙을 추가할 때는 일부러 위반하는 코드를 넣어 그 규칙만 실패하는지 확인한 뒤 커밋한다.**
통과만으로는 규칙이 무언가를 지킨다는 증거가 되지 않는다 —
실제로 이 검증 과정에서 규칙 하나의 오검출 버그를 찾았다([PRD-004](docs/prd/PRD-004-테스트.md)).

---

## 관측성

**기본은 꺼져 있다.** 아무 설정 없이 실행하면 변경 전과 동일하게 동작한다.

트레이스(Tempo) · 메트릭(Prometheus) · 로그(Loki)를 Grafana 한 곳에서 본다.
백엔드는 **여러 앱이 공유하는 독립 스택**이라 앱과 수명주기가 분리돼 있다 —
앱을 내려도 다른 앱의 트레이스가 끊기지 않는다.

```bash
# 1. 백엔드 (최초 1회, 이후 계속 떠 있음)
docker network create observability-net
docker compose -f observability/docker-compose.yml up -d

# 2. 앱에 에이전트 부착 (--build 를 빠뜨리지 말 것)
docker compose -f docker-compose-prod.yml -f docker-compose-otel.yml --profile app up -d --build
```

Grafana 는 http://localhost:3000 (datasource·대시보드가 자동 등록된다).
기본 계정은 `.env` 의 `GRAFANA_USER`/`GRAFANA_PASSWORD` 다.

> **홈서버에 배포한다면** [배포 매뉴얼](docs/observability-deployment.md) 을 먼저 읽는다.
> 자원 산정 · 보존 기간 · 인증 하드닝 · 앱 추가 · 백업/복구 · 장애 대응이 들어 있다.
> 특히 **기본 비밀번호와 보존 기간은 배포 전에 반드시 바꾼다.**

### 무엇을 볼 수 있나

| 화면 | 답하는 질문 |
|---|---|
| Tempo 트레이스 | "이 요청의 800ms 중 DB 가 얼마인가" — 구간별 분해 |
| Tempo service graph | 서비스 간 호출 토폴로지 (Pinpoint ServerMap 대체) |
| `진행 중 요청` 대시보드 | "지금 무엇이 오래 걸리고 있나" — 1s/3s/5s 버킷 |
| Hikari 패널 | "풀 크기 10 이 적정한가" — active/idle/pending 시계열 |
| Loki | trace_id 로 그 요청의 로그를 바로 조회 |

로그 한 줄에 `correlationId` 와 `trace_id` 가 함께 찍히므로 두 세계가 연결된다.

### 앱이 멈췄을 때 — JFR

JFR 상시 녹화가 **6시간 롤링 링버퍼**로 돌고 있다(오버헤드 약 2%).
사람이 그 시점에 붙어 있지 않아도 사후에 조회할 수 있다.

```bash
docker exec <container> jcmd 1 JFR.dump name=app filename=/app/logs/jfr/dump.jfr
# JDK Mission Control 이나 `jfr summary` 로 연다
```

`jdk.ThreadDump`(jstack 형식) · `jdk.JavaMonitorEnter`(락 대기 + 이전 소유자) ·
`jdk.ThreadPark` 등이 담긴다.

### 끄고 싶으면

`docker-compose-otel.yml` 을 빼면 된다. 앱 이미지에서도 제거하려면
`--build-arg OTEL_ENABLED=false`(기본값)로 빌드한다.

근거와 측정값: [ADR-0010](docs/adr/0010-observability-opentelemetry.md) ·
[docs/research/](docs/research/)

---

## 로그

로그는 **레벨별 디렉터리**로 나뉘어 파일로 쌓이고, 컨테이너에서는 도커 볼륨에 영속화된다.
**컨테이너를 삭제해도 로그는 남는다** — 배포는 대개 컨테이너 재생성이므로 이게 없으면
배포할 때마다 이전 로그를 잃는다.

```
logs/error/error.log     ERROR 만        ← 장애 시 여기부터
logs/warn/warn.log       WARN 만
logs/info/info.log       INFO 이상 전부   ← 전체 흐름 추적
```

각 줄에 `correlationId` 가 붙는다. `X-Request-Id` 헤더를 보내면 그 값이 쓰이고,
없으면 UUID 가 생성되어 응답 헤더로 돌아온다. 한 요청의 로그를 흩어진 줄들 사이에서
다시 모을 때 쓴다.

관측성을 켜면 `trace_id`·`span_id` 가 함께 찍혀 Grafana 의 트레이스와 이어진다
(에이전트가 없으면 하이픈으로 표시된다).

```
2026-08-15 12:22:20.247 WARN  [a3f2c1e0-...] [76f7ba13...] [6055ebab...] [http-nio-8080-exec-3] c.e.s.e.GlobalExceptionHandler - ...
```

### 로그 확인

**로컬 개발** — 프로젝트 아래 `logs/` 에 쌓인다.

```bash
tail -f logs/error/error.log
```

**컨테이너** — 볼륨에 쌓인다.

```bash
# 컨테이너 안에서 바로 보기
docker exec sakila-app tail -f /app/logs/error/error.log

# 호스트에서 볼륨 위치 찾기
docker volume inspect sakila-log --format '{{.Mountpoint}}'
# → /var/lib/docker/volumes/sakila-log/_data
sudo tail -f /var/lib/docker/volumes/sakila-log/_data/error/error.log

# 컨테이너가 죽어서 exec 이 안 될 때
docker run --rm -v sakila-log:/logs alpine tail -50 /logs/error/error.log
```

마지막 방법이 중요하다 — **컨테이너가 기동조차 못 한 상황**에서도 볼륨만 있으면
로그를 꺼낼 수 있다. 기동 실패 자체는 `docker logs` 로도 보인다(콘솔 로깅을 유지했다).

### 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `LOG_DIR` | `logs` (컨테이너: `/app/logs`) | 로그 위치 |
| `LOG_MAX_HISTORY` | 30 | 보관 일수 |
| `LOG_MAX_FILE_SIZE` | 100MB | 파일당 크기 |
| `LOG_TOTAL_SIZE_CAP` | 3GB | 전체 상한 |

**bind mount 로 바꾸려면** — 호스트 경로가 직관적이라 `sudo` 없이 볼 수 있다.
`docker-compose-dev.yml` 의 볼륨 항목을 `- ./logs:/app/logs` 로 바꾸고,
호스트 디렉터리 소유권을 컨테이너의 `app` UID 에 맞춘다.
UID 가 어긋나면 권한 오류가 나므로 named volume 을 기본으로 뒀다.

설계 근거와 트레이드오프: [ADR-0009](docs/adr/0009-log-persistence.md)

---

## 새 프로젝트로 쓸 때 지울 것

1. **`src/main/java/com/example/sakila/sakila/`** — 예제 엔티티 15개, 통째로 삭제
2. **`src/main/java/com/example/sakila/rental/`** — DDD 참조 구현.
   구조를 익힌 뒤 자기 도메인으로 교체
3. **`src/main/resources/db/`** — `V1__sakila_schema.sql` · `V900__sakila_seed_data.sql` 삭제,
   `V2__auth_tables.sql` 은 남긴다
4. **`src/test/java/com/example/sakila/rental/`** — 예제 테스트
5. **`ResponseCode`** — `RENTAL_*` 항목을 자기 도메인 코드로 교체
6. **`ArchitectureTest`** — `RENTAL_DOMAIN` 상수와 "템플릿 경계" 규칙을 자기 패키지에 맞게 수정
7. **`docs/`** — ADR·PRD 는 이 템플릿의 결정 기록이다. 참고만 하고 새로 쓴다
8. **`src/test/java/com/example/sakila/observability/`** — N+1 검증 테스트는
   `sakila` 예제 엔티티에 의존한다. 1번을 지우면 함께 지운다

**남길 것**: `common/` · `config/` · `error/` · `auth/` · 빌드 설정 · docker-compose ·
CI · PR 템플릿 · ArchUnit 골격 · **`observability/`**(관측성 스택은 도메인 무관)

관측성을 안 쓸 거라면 `observability/` · `docker-compose-otel.yml` 을 지우고
`Dockerfile` 의 `otel` 스테이지와 `COPY --from=otel` 한 줄을 제거한다.
JFR 은 JDK 내장이라 ENTRYPOINT 의 `-XX:StartFlightRecording` 만 빼면 된다.

---

## 문서

| 문서 | 내용 |
|---|---|
| [docs/SPEC.md](docs/SPEC.md) | API · 스키마 · 처리 규칙 (계속 갱신) |
| [docs/adr/](docs/adr/) | 되돌리기 비싼 결정 9건과 기각한 대안 |
| [docs/prd/](docs/prd/) | 무엇을 왜 만들었나 + 완료 판정과 검증 결과 |
| [docs/requirement/](docs/requirement/) | 원문 요청과 해석 |
| [docs/research/](docs/research/) | 결정 **전** 조사 기록 (실측 / 문서 / 추정 구분) |
| [docs/observability-deployment.md](docs/observability-deployment.md) | 관측성 스택 홈서버 배포·운영 매뉴얼 |

특히 볼 만한 것:

- [ADR-0002](docs/adr/0002-openfeign-querydsl.md) — 왜 본가 QueryDSL 을 못 쓰는가
- [ADR-0003](docs/adr/0003-localdatetime-over-instant.md) — 시각 정밀도 불일치로 데이터가 사라진 사례
- [ADR-0006](docs/adr/0006-auth-hardening.md) — 참조 프로젝트에서 고친 보안 결함 11건
- [ADR-0009](docs/adr/0009-log-persistence.md) — 컨테이너를 지워도 로그가 남게 하는 법

---

## 트러블슈팅

| 증상 | 원인 | 조치 |
|---|---|---|
| Q 클래스가 생성되지 않는다 | `querydsl-apt` classifier 가 `:jakarta` | `:jpa` 로 바꾼다 |
| `Schema validation: missing table` | Flyway 자동설정 모듈 누락 | `spring-boot-flyway` 의존성 확인 |
| `Client id of registration must not be empty` | 프로바이더 클라이언트 값 미설정 | `.env` 를 채우거나 해당 registration 을 지운다 |
| 기동 시 `JWT_SECRET_KEY ... 32바이트 이상` | 키가 짧거나 없음 | `openssl rand -base64 48` |
| keyset 스크롤에서 행이 누락된다 | 정렬 키에 PK 가 없거나 시각 정밀도 불일치 | [ADR-0003](docs/adr/0003-localdatetime-over-instant.md) |
| 통합 테스트가 느리다 | 컨테이너 재사용 미설정 | `~/.testcontainers.properties` |
| 컨테이너에서 `Permission denied` 로 로그를 못 쓴다 | 볼륨이 `root:root` 로 마운트됨 | Dockerfile 이 `USER` 전에 `mkdir`+`chown` 하는지 확인 |
| `docker compose --profile app up` 이 `xargs is not available` 로 실패 | corretto 이미지에 findutils 없음 | Dockerfile 의 `yum install -y findutils` 확인 |
| `Client id of registration 'google' must not be empty` (컨테이너에서만) | `.env` 의 빈 값이 yml 기본값을 덮음 | compose 가 `:-not-configured` 를 넘기는지 확인 |
