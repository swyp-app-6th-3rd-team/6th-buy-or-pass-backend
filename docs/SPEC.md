# SPEC

무엇을 만드는가 — API · 스키마 · 처리 규칙. **계속 갱신한다.**
결정이 바뀌면 항목을 고치고 맨 아래 변경 이력에 남긴다.

진행 표기: ☑ 완료 · ◐ 진행중 · ☐ 미착수

---

## 1. 기술 스택

| 항목 | 버전 | 비고 |
|---|---|---|
| JDK | 25 (Amazon Corretto) | Gradle toolchain 으로 고정 |
| Spring Boot | 4.1.0 | Hibernate 7.4.5 · MySQL Connector/J 9.7.0 관리 |
| Spring Security | 7.1.0 | `PathPatternRequestMatcher` (Ant/Mvc matcher 제거됨) |
| Jackson | 3.1.4 (`tools.jackson`) | groupId 가 `com.fasterxml` 이 아니다 |
| QueryDSL | 7.5 (`io.github.openfeign.querydsl`) | 본가 아님. classifier `:jpa` |
| MySQL | 8.4 LTS | `utf8mb4_0900_ai_ci`, `lower_case_table_names=0` |
| Flyway | 12.4.0 + `spring-boot-flyway` | 자동설정 모듈이 별도다 |
| springdoc | 3.1.0 | 2.x 는 Boot 4 비호환 |
| Testcontainers | 1.21.3 | `testcontainers-bom` 으로 버전 관리 |
| ArchUnit | 1.4.1 | |
| OpenTelemetry Java Agent | 2.30.0 | 옵트인. 아키텍처 독립적 단일 jar |
| Micrometer Prometheus Registry | Boot 4.1 관리 | `/actuator/prometheus` (관리 포트) |
| Grafana / Tempo / Prometheus / Loki | 12.3.1 / 2.9.0 / v3.7.3 / 3.5.7 | 독립 스택. 전부 arm64 네이티브 |

---

## 2. 패키지 구조 ☑

비즈니스 축으로 먼저 자르고, 그 안에서 계층을 나눈다.

```
com/example/sakila/
├── common/          ApiResponse · ResponseCode · PageResponse · ScrollResponse
│                    CursorCodec · CorrelationIdFilter
├── config/          ClockConfig · QuerydslConfig · ScalarConfig
├── error/           ApiException · GlobalExceptionHandler
│
├── rental/          ★ DDD 참조 구현 (남길 것)
│   ├── domain/      Rental · RentalStore · RentalSearchCondition
│   ├── service/     RentalService · RentalQueryService · RentalCommand
│   ├── infra/       RentalEntity · RentalRepository(package-private)
│   │                JpaRentalStore · RentalQueryRepositoryImpl
│   └── controller/  RentalController + dto/
│
├── sakila/          ★ 예제 (새 프로젝트에서 지울 것)
│   └── infra/       15개 엔티티 + 리포지토리 + 컨버터
│
└── auth/            OAuth2 + JWT
    ├── domain/      User · Role · SocialProvider · UserStore · RefreshTokenStore
    ├── service/     AuthService · JwtService
    ├── infra/       UserEntity · UserRefreshTokenEntity · Jpa*Store
    ├── oauth/       OAuth2UserInfo(+3 어댑터) · CustomOAuth2UserService
    │                OAuth2SuccessHandler · OAuth2FailureHandler
    │                HttpCookieOAuth2AuthorizationRequestRepository
    ├── security/    JwtAuthenticationFilter · @CurrentUser
    │                RestAuthenticationEntryPoint · RestAccessDeniedHandler
    ├── config/      SecurityConfig · AuthProperties
    └── controller/  AuthController
```

---

## 3. API

모든 응답은 `ApiResponse<T>` 로 감싼다.

```json
{ "code": "OK", "message": "정상 처리되었습니다.", "returnObject": { } }
```

### 3.1 인증 ☑

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/oauth2/authorization/{google\|kakao\|naver}` | — | 소셜 로그인 시작 |
| GET | `/login/oauth2/code/{provider}` | — | 콜백 (Spring 이 처리) |
| GET | `/api/auth/me` | 필요 | 내 정보 |
| POST | `/api/auth/refresh` | 쿠키 | 토큰 재발급 (회전) |
| POST | `/api/auth/logout` | 선택 | 리프레시 폐기 + 쿠키 만료 |

**토큰 전달 규약**
- 액세스 토큰 — 로그인 성공 시 리다이렉트 **쿼리파라미터**, 이후 `Authorization: Bearer`
- 리프레시 토큰 — **HttpOnly 쿠키만**. 본문에도 URL 에도 담지 않는다

### 3.2 대여 ☑

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rentals` | 대여 시작 (201) |
| POST | `/api/rentals/{id}/return` | 반납 |
| GET | `/api/rentals/{id}` | 단건 조회 |
| GET | `/api/rentals` | 목록 — 번호 페이징 |
| GET | `/api/rentals/scroll` | 목록 — 무한 스크롤 |
| GET | `/api/rentals/outstanding` | 미반납 목록 (오래된 순) |

**목록 조회 파라미터**: `customerId` · `staffId` · `inventoryId` · `returned` ·
`rentedFrom` · `rentedTo` · `page` · `size`(최대 100) · `sort`

**스크롤 파라미터**: `cursor`(비우면 첫 조각) · `limit`(기본 20, 최대 100)

### 3.3 문서 ☑

| Path | 설명 |
|---|---|
| `/swagger-ui.html` | Swagger UI |
| `/scalar` | Scalar (직접 등록 — [ADR-0007](adr/0007-scalar-manual-registration.md)) |
| `/v3/api-docs` | OpenAPI 스펙 |

---

## 4. 스키마

### 4.1 Sakila 16개 ☑

`actor` · `address` · `category` · `city` · `country` · `customer` · `film` ·
`film_actor` · `film_category` · `film_text` · `inventory` · `language` ·
`payment` · `rental` · `staff` · `store`

원본에서 변경한 것은 [ADR-0005](adr/0005-sakila-schema-modifications.md) 참조.

### 4.2 인증 2개 ☑

```sql
users(id, provider, provider_id, email, name, role, state, created_at, updated_at,
      UNIQUE KEY uk_users_provider (provider, provider_id))

user_refresh_token(id, user_id, token_hash CHAR(64), expires_at, created_at,
      UNIQUE KEY uk_refresh_user (user_id),
      UNIQUE KEY uk_refresh_token_hash (token_hash),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)
```

### 4.3 마이그레이션 ☑

| 파일 | location | 로드 시점 |
|---|---|---|
| `V1__sakila_schema.sql` | `db/migration` | 항상 |
| `V2__auth_tables.sql` | `db/migration` | 항상 |
| `V900__sakila_seed_data.sql` (9MB) | `db/seed` | `local` 프로파일만 |

---

## 5. 처리 규칙

### 5.1 시간 ☑

- 모든 시간 필드는 `LocalDateTime`. `Instant` 를 쓰지 않는다.
- `Clock` 빈은 **초 단위로 끊는다** — Sakila 컬럼이 `datetime(0)` 이라 정밀도를 맞춘다.
- 도메인·Store 는 `LocalDateTime.now()` 를 직접 부르지 않고 `Clock` 을 주입받는다.
- 근거: [ADR-0003](adr/0003-localdatetime-over-instant.md)

### 5.2 페이징 ☑

- 계층 내부는 Spring Data 타입 직접 사용 — `Pageable`/`Page`/`ScrollPosition`/`Window`
- **응답 경계에서만** `PageResponse`/`ScrollResponse` 로 변환
- keyset 정렬 키는 반드시 `(정렬컬럼, PK)` — 동률 구간에서 누락·중복을 막는다
- 커서는 Base64URL(JSON). **JSON 왕복 시 타입이 소실되므로** 복원 측이 문자열도 받아야 한다
- 정렬 필드는 허용 목록 방식. 모르는 필드는 무시한다
- 근거: [ADR-0004](adr/0004-spring-data-paging-types.md)

### 5.3 도메인 ☑

- 도메인은 JPA·Spring·Lombok·검증 애노테이션에 의존하지 않는다
- 생성자가 불변식을 강제. 복원은 `restore(...)` 정적 팩터리로 분리
- 변환 방향은 **엔티티 → 도메인** (`toDomain()`/`from()`)
- Store 인터페이스는 domain, 구현은 infra
- Spring Data 리포지토리는 **package-private**
- 근거: [ADR-0008](adr/0008-domain-entity-separation.md)

### 5.4 인증 ☑

- 액세스 토큰은 클레임만으로 인가 판단. **요청마다 DB 를 조회하지 않는다**
- 리프레시 토큰은 SHA-256 해시로 저장. 사용자당 한 행, 재발급 시 갱신
- 해시 불일치 시 저장 토큰을 폐기하고 재로그인을 강제한다 (재사용 탐지)
- `typ` 클레임으로 액세스/리프레시를 구분해 혼용을 막는다
- 리다이렉트 URI 는 호스트 화이트리스트 검증 (오픈 리다이렉트 방지)
- 근거: [ADR-0006](adr/0006-auth-hardening.md)

### 5.5 로깅 ☑

- 레벨별 디렉터리로 분리 — `${LOG_DIR}/{error,warn,info}/`
  - `error`·`warn` 은 `LevelFilter` 로 **그 레벨만**
  - `info` 는 `ThresholdFilter` 로 INFO 이상 전부 (전체 흐름 추적용)
- 롤링: 일 단위 + 파일당 100MB, 30일 보관, 전체 3GB 상한
- 패턴에 `%X{correlationId}` 포함 — `CorrelationIdFilter` 가 MDC 를 채운다
- `AsyncAppender` + `discardingThreshold=0` — 큐가 차도 로그를 버리지 않는다
- **prod 에서도 콘솔을 유지한다** — 기동 실패는 파일 appender 준비 전에 일어나므로
  콘솔을 끄면 `docker logs` 가 비어 원인을 못 본다
- 컨테이너에서는 `/app/logs` 를 named volume 에 마운트해 영속화
- 근거: [ADR-0009](adr/0009-log-persistence.md)

| 프로파일 | 콘솔 | 파일 |
|---|---|---|
| `test` | ✅ (WARN 이상) | ✗ |
| `local` | ✅ | ✅ |
| `prod` | ✅ | ✅ |

### 5.6 에러 응답 ☑

| 상황 | 코드 | HTTP |
|---|---|---|
| 요청 값 오류 · 도메인 불변식 위반 | `INVALID_REQUEST` | 400 |
| 미인증 · 토큰 오류 | `UNAUTHORIZED` / `INVALID_TOKEN` / `EXPIRED_TOKEN` | 401 |
| 권한 없음 | `FORBIDDEN` | 403 |
| 대상 없음 | `NOT_FOUND` / `RENTAL_NOT_FOUND` | 404 |
| 이미 반납됨 | `RENTAL_ALREADY_RETURNED` | 409 |
| 그 외 | `SYSTEM_ERROR` | 500 |

---

## 6. 아키텍처 규칙 ☑

`ArchitectureTest` 18개. 규칙을 추가할 때는 **일부러 위반하는 코드를 넣어
해당 규칙만 실패하는지 확인한 뒤** 커밋한다.

상세: [PRD-004](prd/PRD-004-테스트.md#아키텍처-규칙-18개)

---

## 7. 테스트 ☑

**108건 통과 / 0 실패**

| 계층 | 건수 | 컨테이너 |
|---|---|---|
| 도메인·어댑터·서비스 단위 | 60 | 불필요 |
| 아키텍처 | 18 | 불필요 |
| 통합 (인프라·API) | 38 | 필요 |

---

## 변경 이력

| 날짜 | 변경 | 계기 |
|---|---|---|
| 2026-08-15 | `Instant` → `LocalDateTime` | Sakila 컬럼이 `DATETIME`(타임존 없음) |
| 2026-08-15 | `PageQuery`/`PageResult` 자체 래퍼 제거 → Spring Data 타입 직접 사용 | 무한 스크롤에 `Window` 가 필요 |
| 2026-08-15 | `film.length` `Integer` → `Short` | `ddl-auto=validate` 가 `smallint unsigned` 불일치 검출 |
| 2026-08-15 | `film.rental_duration` `Short` → `Byte` | 같은 경로로 `tinyint unsigned` 검출 |
| 2026-08-15 | springdoc 2.8.13 → 3.1.0 | 2.x 가 Spring Data 4 와 비호환 (`NoClassDefFoundError`) |
| 2026-08-15 | `spring-boot-flyway` 모듈 추가 | Boot 4 는 자동설정이 모듈별로 분리됨. 마이그레이션이 조용히 실행되지 않았음 |
| 2026-08-15 | `Clock` 을 초 단위로 끊음 | keyset 스크롤에서 행 누락. `datetime(0)` vs 나노초 정밀도 불일치 |
| 2026-08-15 | 아키텍처 규칙 "컨트롤러는 엔티티를 노출하지 않는다" 에 패키지 조건 추가 | 이름만으로 필터링해 Spring `ResponseEntity` 를 오검출 |
