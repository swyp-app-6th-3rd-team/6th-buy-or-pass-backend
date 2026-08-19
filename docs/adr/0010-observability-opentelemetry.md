# ADR-0010 — 관측성은 OpenTelemetry + Grafana 스택으로 한다

**상태**: Accepted

## 맥락

이 템플릿의 관측성 자산은 두 가지뿐이었다.

| 자산 | 답할 수 있는 질문 |
|---|---|
| Logback 레벨별 파일 + `correlationId` MDC (ADR-0009) | "한 요청 안에서 **무슨 일**이 났나" |
| Actuator `health`, `info` | "**살아있나**" |

관측성 의존성은 `spring-boot-starter-actuator` 하나뿐이었고 Micrometer 레지스트리가 없어
메트릭이 메모리에만 쌓이고 있었다. 여기에 네 가지 공백이 있었다.

1. **지연 시간을 분해할 수 없다** — API 가 800ms 걸렸을 때 DB 가 얼마인지 모른다.
   로그는 시각을 남기지만 구간을 남기지 않는다.
2. **N+1 을 사후 발견할 수 없다** — `default_batch_fetch_size: 100` 이 실제로 듣는지
   **검증한 적이 없었다.** 수단이 `show-sql` 로그를 눈으로 세는 것뿐이었다.
3. **느린 요청 표본이 자동으로 안 잡힌다** — "성공했지만 느린" 요청은 `info.log` 에 묻힌다.
4. **커넥션풀 시계열이 없다** — Hikari `maximum-pool-size: 10` 이 적정한지 판단할 데이터가 없다.

### ADR-0009 와의 관계 — 부분적으로만 뒤집는다

ADR-0009 는 이렇게 못박았다.

> 로그 수집기(Loki·ELK 등)를 두지 않았다. 단일 홈서버에서 파일로 충분하고,
> **측정 없이 미들웨어를 도입하지 않는다는 원칙**에 따라 v1 범위 밖으로 둔다.

이 판단의 논리는 여전히 유효하지만, **관측성 도구는 "최적화" 가 아니라 "측정 수단의 확보"** 다.
측정 도구가 없으면 측정 자체를 시작할 수 없으므로 "측정 없는 최적화 금지" 원칙의 적용 대상이
아니다. 순환논법을 피하기 위해 이 예외를 명시한다.

단, **파일 로깅을 폐기하지 않는다.** ADR-0009 의 자산(레벨별 파일 + 볼륨 영속화)을 그대로
두고 OTLP 로 **동시 전송**한다. 컨테이너가 기동조차 못 할 때 `docker logs` 로 원인을 봐야
한다는 ADR-0009 의 논거가 여전히 성립하기 때문이다.

## 결정

**OpenTelemetry Java Agent + Grafana 스택**(Tempo / Prometheus / Loki / Grafana)을 쓴다.

### 왜 Pinpoint 가 아닌가 — 기능 우열이 아니라 제약 적합성

이 작업은 **Pinpoint APM 도입으로 시작**했고 검증 단계에서 방향이 바뀌었다.

| 항목 | 결과 |
|---|---|
| 에이전트 계측 | **통과** — MySQL 플러그인 후킹 클래스 6/6 이 Connector/J 9.7.0 에 존재하고 후킹 메서드가 JDBC 표준 API 였다 |
| **백엔드** | **실패** — `pinpoint-hbase` 가 **amd64 단일 아키텍처**이고 배포 대상이 Apple Silicon 이다 |

HBase 는 Pinpoint 3.x 에서 **대체 불가**다(트레이스 저장소이며 Pinot 은 메트릭용이다).
커뮤니티의 Cassandra·Elasticsearch 요청도 모두 미수용이다.
4 회 시도 전부 실패했고 마지막엔 225 초 JVM 정지가 났다.

추가로 스택 적합성에서도 밀린다.

| | Pinpoint 3.1.0 | OTel 2.30.0 |
|---|---|---|
| Hibernate 플러그인 | **없음** | 있음(`3.3+`) |
| Boot 4 지원 | 미문서화 (내부 의존성이 Boot 3.3.x) | 변경로그에 명시(`2.23.0`) |
| Java 25 | 3.1.x 에서 처음 지원 | `8+`, 상한 없음 |
| 릴리스 주기 | 3.1.0 (2026-05) | 월 단위, 최신 2.30.0 (2026-07) |
| 계측 실패 시 | **조용히 실패** | `otel.javaagent.debug` 가 직접 보고 |

### 왜 Boot 4 의 `spring-boot-starter-opentelemetry` 가 아니라 Agent 인가

Boot 4.0 은 OTel 스타터를 1 급 제공하지만 **에이전트와 동시 사용 시 메트릭이 중복 집계**되어
배타적 선택이다.

**Agent 를 택한다.** 공백 2 번(N+1)이 이 도입의 핵심인데 그러려면 **JDBC 레벨 스팬**이
개별로 찍혀야 한다. 스타터는 Micrometer 기반이라 Spring 빈 경계 위주다.
Pinpoint 조사에서 확인한 것과 같은 구조 — **SQL 트레이스는 ORM 이 아니라 JDBC 계측이 잡는다.**

→ `build.gradle` 에 `spring-boot-starter-opentelemetry` 를 넣지 않는다.

### 구성

**백엔드는 앱과 분리된 독립 스택**이다(`observability/docker-compose.yml`).
관측성 백엔드는 여러 앱이 공유하는 인프라이므로, 앱 compose 에 묶으면 앱을 `down` 할 때
백엔드까지 내려가 다른 앱의 트레이스가 끊긴다. external network 로 연결한다.

앱 쪽은 **옵트인**이다(`docker-compose-otel.yml` override + `ARG OTEL_ENABLED=false`).
기본 실행은 변경 전과 완전히 동일하다 — ADR-0001 의 "지울 것/남길 것 분리" 철학을 따른다.

**메트릭은 별도 관리 포트로 노출한다.** `/actuator/prometheus` 를 `SecurityConfig` 의
`PUBLIC_GET`(인증 없이 열리는 allowlist)에 넣으면 JVM·DB 내부 지표가 공개되기 때문이다.
포트 분리만으로는 Security 필터 체인이 그대로 적용돼 401 이 나므로,
`EndpointRequest.toAnyEndpoint()` 를 매칭하는 전용 `SecurityFilterChain` 을 `@Order(1)` 로 둔다.

**JFR 상시 녹화를 켠다**(`maxage=6h` 링버퍼). OTel 과 독립적이며 JDK 내장이라 의존성이 0 이다.

## 결과

### 검증한 것 (측정값은 `docs/research/otel-measurements.md`)

| 항목 | 결과 |
|---|---|
| **N+1 방어 검증** (공백 2) | 지연 로딩 쿼리 **30 회 → 1 회**. `default_batch_fetch_size: 100` 이 실제로 작동함을 처음으로 확인 |
| **Hikari 풀 시계열** (공백 4) | `hikaricp_connections_{active,idle,max,pending}` 노출 |
| 액티브 요청 히스토그램 | `le=1.0/3.0/5.0` 버킷 — Pinpoint 액티브 스레드 뷰와 같은 구조 |
| 에이전트 오버헤드 | p50 2.06ms → 2.20ms, p95 2.50ms → 3.14ms (표본 200) |
| 파이프라인 | 트레이스→Tempo, 로그→Loki, 메트릭→Prometheus 도달 확인 |
| 보안 | 관리포트 200 / 서비스포트 401 |
| 회귀 | 테스트 111 건 통과 |

### 포기한 것

- **SQL 바인드 값을 트레이스에 담지 않는다.** OTel 의 `capture-query-parameters` 는
  allow-list 가 아니라 **sanitization 을 통째로 끄므로**(CVE-2026-54704 계열 노출 재개방)
  운영에서 위험하다. 바인드 값은 `org.hibernate.orm.jdbc.bind: TRACE` 로 이미 확보된다.
  샘플링된 요청만 로깅하려면 Logback `TurboFilter` 게이팅이 필요하나
  **공개 구현체가 없는 조합**이라 스파이크 대상으로 남긴다.
- **ServerMap 엣지 카운트의 샘플링 내성.** Pinpoint 는 에이전트 측에서 세므로 샘플링과
  무관하게 정확했지만, Tempo service graph 는 샘플링된 트레이스 기반이라 과소 집계된다.
  → 샘플링을 100% 로 두어 회피한다(단일 홈서버 트래픽에서 현실적).
- **무설정 일관성.** Pinpoint 는 에이전트 1 개 + UI 1 개로 4 개 뷰가 나왔다.
  OTel 은 5 컨테이너를 배선·튜닝해야 한다. 일회성 비용으로 받아들인다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| **Pinpoint 3.1** | `pinpoint-hbase` 가 amd64 전용이고 HBase 는 대체 불가. 배포 대상이 arm64 라 4 회 시도 전부 실패. 추가로 Hibernate 플러그인 부재, Boot 4 미문서화 |
| **Pinpoint 백엔드만 원격 x86 에** | 에이전트는 arm64 에서 정상 동작하므로 기술적으로 가능하나, 홈서버 하나로 끝내려는 전제와 어긋나고 외부 인프라 비용·관리가 생긴다 |
| **HBase 를 arm64 로 직접 빌드** | 이미지 유지보수를 떠안는다. Hadoop 네이티브 라이브러리 이슈가 예상되고 성공이 불확실하다 |
| **Boot 4 `spring-boot-starter-opentelemetry`** | Micrometer 기반이라 JDBC 레벨 스팬이 나오지 않을 가능성이 크다. 공백 2 번(N+1)이 핵심이므로 부적합. 에이전트와 병용 시 중복 집계 |
| **`grafana/otel-lgtm` 단일 컨테이너** | README 가 명시적으로 "development, demo, testing" 용도라고 못박고 데이터 영속화도 권장하지 않는다. 상시 운영에 부적합 |
| **Pyroscope (지속 프로파일링)** | JFR 로 충분한지 먼저 확인한다. 측정 없이 미들웨어를 도입하지 않는다는 원칙 적용. 플레임그래프가 필요해지면 재검토 |
| **Tail sampling** | 단일 노드에 과하다. 에이전트에서 100% 캡처를 전제하므로 오히려 비용이 늘고, `isSampled()` 기반 로그 게이팅과 결합하면 로그와 트레이스가 어긋난다 |
| **Datadog 등 상용 APM** | Continuous Profiler 의 데이터 소스가 JFR 이라 역량 이득이 없다. 편의성 차이에 비용을 지불할 근거가 부족하다 |
| **현행 유지(로그 + health 만)** | 공백 1~4 를 하나도 해결하지 못한다. 특히 `default_batch_fetch_size` 검증 수단이 계속 없다 |

조사 기록 전체는 `docs/research/` 에 있다.
