# Phase 0 — OTel 계측 검증 결과

검증 시점: 2026-08-17
판정: **통과 (가설 A 확정 — 계측 성립)**

## 방법 — 백엔드 0 개

OTel 은 exporter 를 콘솔로 바꾸면 백엔드가 필요 없다. Tempo·Collector 를 하나도 띄우지 않고
앱만 실행해 계측 여부를 확인했다.

```sh
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=sakila-ddd-template \
  -Dotel.traces.exporter=console \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -jar build/libs/sakila-ddd-template-0.0.1-SNAPSHOT.jar
```

에이전트: `opentelemetry-javaagent.jar` **2.30.0** (25MB, 아키텍처 독립적 단일 jar).
`Premain-Class: io.opentelemetry.javaagent.OpenTelemetryAgent`,
`Can-Redefine-Classes: true`, `Can-Retransform-Classes: true`.

## [실측] 결과

### 1. 에이전트가 Java 25 + Boot 4.1 에서 기동한다

```
Started SakilaTemplateApplication in 15.883 seconds (process running for 17.518)
```

→ **가설 C(에이전트가 기동을 방해) 배제.**

### 2. SQL 이 계측된다 (핵심 판정)

```
'SELECT INFORMATION_SCHEMA.KEYWORDS' : 7cb12b4f… CLIENT
  [tracer: io.opentelemetry.jdbc:2.30.0-alpha]
  AttributesMap{data={
    db.connection_string=mysql://127.0.0.1:13306,
    server.address=127.0.0.1,
    db.user=sakila,
    db.statement=SELECT WORD FROM INFORMATION_SCHEMA.KEYWORDS…,
    db.system=mysql
  }}
```

**`db.statement` 에 실제 SQL 문자열이 담긴다.** → **가설 B(SQL 미계측) 배제.**

### 3. 적용된 계측

| tracer | 역할 |
|---|---|
| `io.opentelemetry.jdbc` | **SQL 스팬 (N+1 검증의 전제)** |
| `io.opentelemetry.tomcat-10.0` | HTTP 진입점. 스팬 이름이 라우트로 나온다(`GET /api/rentals`) |

Hibernate(`hibernate.orm.core`, `hibernate.orm.jpa`)와
HikariCP(`hikari.pool.HikariPool`)도 클래스패스에서 확인됐다.

### 4. JFR 상시 녹화 (별도 검증)

```sh
docker run --rm amazoncorretto:25-alpine sh -c \
  'java -XX:StartFlightRecording=name=app,disk=true,maxage=6h,maxsize=512m,\
settings=profile,dumponexit=true,filename=/tmp/jfr/onexit.jfr \
   -XX:FlightRecorderOptions=repository=/tmp/jfr -version'
# → [jfr,startup] Started recording 1.
# → onexit.jfr 생성 확인
```

Java 25 에서 플래그가 정상 동작하며 `dumponexit` 으로 파일이 생성된다.

## 부수 발견 — Flyway 체크섬 불일치

첫 시도에서 앱이 기동 실패했으나 **OTel 과 무관**했다.

```
Migration checksum mismatch for migration version 2
-> Applied to database : -1330042556
-> Resolved locally    : -1556364511
```

마이그레이션 파일은 git 기준 변경이 없었고(`5c6d608` 이후 수정 없음), 검증용 DB 볼륨이
다른 상태에서 시드된 것이었다. `docker compose down -v` 로 재생성해 해결했다.

**의미**: 실패 지점이 Spring 빈 생성(Flyway 검증)이었다는 것 자체가
**에이전트가 정상 부착됐다는 방증**이다 — 에이전트 문제였다면 컨텍스트 로딩 전에 죽는다.

## Pinpoint 와의 대비

| | Pinpoint 3.1.0 | OTel 2.30.0 |
|---|---|---|
| 백엔드 없이 검증 | **불가** (collector 필수) | **가능** (console exporter) |
| 계측 실패 시 | **조용히 실패** | `otel.javaagent.debug` 가 직접 보고 |
| Phase 0 소요 | 수 시간(HBase 기동 실패로 미완) | **수 분** |
| Hibernate 플러그인 | 없음 | 있음(`3.3+`) |

## 다음 단계

Phase 1(로컬 end-to-end) → Phase 2(운영 백엔드) → Phase 3(앱 통합) 진행.
남은 [추정] 항목은 TurboFilter 샘플링 게이팅뿐이며 v1 범위 밖이다.
