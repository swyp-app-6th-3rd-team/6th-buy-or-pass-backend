# OpenTelemetry + Grafana 스택은 Pinpoint 를 대체할 수 있는가

조사 시점: 2026-08-17

## 질문

Pinpoint 백엔드(HBase)가 arm64 홈서버에서 뜨지 않는 상황에서,
OTel + Jaeger/Tempo 스택이 **Pinpoint 의 기능을 대체 가능한가?** 더 나은 점은 있는가?

## 요약

초기 판정은 "85% 대체, 손실 3 건" 이었으나 **후속 조사 2 건으로 손실 2 건이 철회됐다.**
남은 것은 조립 비용과 샘플링 시 엣지 카운트 정확도뿐이며, 이 프로젝트의 제약
(Boot 4.1 / Java 25 / arm64)에서는 OTel 이 명확히 유리하다.

| 초기 손실 판정 | 최종 |
|---|---|
| SQL 바인드 값 | **강등** — 값은 이미 확보 중(Hibernate 로그). 샘플링 선택성은 TurboFilter 로 구현 가능(스파이크 필요) → [sampled-sql-bind-capture.md](sampled-sql-bind-capture.md) |
| 실시간 액티브 스레드 | **철회** — Micrometer LongTaskTimer 로 동등 대체, 사후 회고는 JFR 이 우위 → [always-on-thread-visibility.md](always-on-thread-visibility.md) |
| 무설정 일관성(조립 비용) | **유지** — 일회성 비용 |
| ServerMap 엣지 카운트 정확도 | **유지(조건부)** — 샘플링 100% 면 소멸 |

## 1. Pinpoint 고유 기능 대조

| 기능 | Pinpoint 3.1 | OTel + Tempo/Jaeger | 실제 격차 |
|---|---|---|---|
| **ServerMap** | 자동 토폴로지, 엣지별 호출수·에러율, 클릭 드릴다운. 3.1 에서 ServerMap V3 로 재작성 | Tempo metrics-generator 의 service graph (`traces_service_graph_request_total` 등) | **부분적** — §3 참조 |
| **CallStack** | 메서드 단위 타이밍 | Jaeger/Tempo span waterfall | **격차 없음** (UI 는 오히려 나음) |
| **Inspector** (heap·GC·CPU·TPS·datasource) | 에이전트별 JVM 지표 내장. 단 3.1 은 Flink Inspector 를 폐기해 **새 Inspector 는 Pinot 필요** | **Prometheus + Grafana 추가 필요.** 지표 자체는 OTel 에이전트가 전부 방출(`jvm.memory.used`, `jvm.gc.duration`, `jvm.cpu.recent_utilization` — Stable semconv), 풀 지표는 HikariCP 계측 | **컴포넌트 추가 필요** |
| **실시간 액티브 스레드** | 초 단위 라이브 차트 | **없음.** 메트릭은 scrape 주기(10~15s)에 묶임 | **진짜 손실** |
| **Scatter chart** | 트랜잭션별 점, 드래그 → 느린 트레이스 드릴다운. 3.1 은 Heatmap 추가 | Grafana 레이턴시 히트맵 + **exemplar** 로 트레이스 점프 | **거의 대체** (점 단위 granularity 는 손실) |
| **SQL 바인드 값** | `profiler.jdbc.*` 로 실제 바인드 파라미터 캡처 | 기본 꺼짐. `capture-query-parameters` 존재하나 semconv 는 기본 수집을 SHOULD NOT 으로 규정 | **실질적 손실** — 아래 참조 |
| **샘플링** | `PERCENT` + rate, 런타임 변경 가능 | head/tail/parent. **Collector 의 tail sampling 이 훨씬 강력** | **OTel 우세** |
| **알람** | 3 분마다 배치, HEAP USAGE RATE 등 체커 제공. **MySQL 필수**, SMS 는 `SmsSender` 직접 구현 | Grafana Alerting / Alertmanager | **OTel 명백히 우세** |

## 2. OTel 이 나은 점

### Spring Boot 4 지원이 변경로그로 검증됨

**[문서]** `opentelemetry-java-instrumentation` CHANGELOG:

| 버전 | 내용 |
|---|---|
| `2.21.0` | Hibernate 7.2.0.CR1 지원 |
| `2.22.0` | Spring Framework 7.0 지원 |
| `2.23.0` | **Spring WebMVC / WebFlux / Spring starter — Boot 4 지원** |
| `2.24.0` | Spring Boot Starter — Boot 4 RestClient |
| `2.30.0` | 최신 (2026-07-22) |

**[실측]** `supported-libraries.md` 의 버전 범위:

| 라이브러리 | 지원 범위 | 비고 |
|---|---|---|
| Hibernate | **3.3+** | 상한 없음 → 7.4.5 커버 |
| HikariCP | **3.0+** | **Database Pool Metrics 제공** |
| Spring Web MVC | 3.1+ | |

**[실측]** 에이전트 README: "Java **8+**" — 상한 명시 없음.
Pinpoint 가 지원 버전을 명시적으로 열거하고 25 를 3.1.x 에서야 추가한 것과 대비된다.

→ **HikariCP Database Pool Metrics 는 이 프로젝트의 관측성 공백 4 번
(Hikari 풀 시계열 부재)을 별도 작업 없이 메운다.** Pinpoint 최소 구성에서는 포기해야 했다.

**[추정]** Java 25 는 명시적 지원 표기가 없으나, 에이전트가 이미 JDK 26 JEP 500
대응 패치를 넣고 있어 25 를 뒤따르는 게 아니라 앞서 추적 중이다.
**Phase 0 에서 실제 확인이 필요한 항목이다.**

### Spring Boot 4 가 OTel 을 1급으로 채택

**[문서]** Boot 4.0 부터 **`spring-boot-starter-opentelemetry`** 를 공식 제공하며
start.spring.io 에서 선택 가능하다. Micrometer 가 OTLP 로 신호를 내보낸다.
Pinpoint 에는 이런 1급 통합이 없다.

> ⚠️ **함정**: 이 스타터와 OTel Java Agent 를 **동시에 쓰면 메트릭이 중복 집계된다.**
> 둘 중 하나만 고른다. OTel 팀은 에이전트를 기본 선택지로 권한다.

### 세 신호 통합

Pinpoint 3.1 은 OTLP **메트릭 수신**만 추가했다. 트레이스는 여전히 Pinpoint 에이전트가
필요하고 **로그는 아예 없다.** OTel 은 trace·metric·log 를 함께 다루고
**exemplar** 로 상호 연결한다.

### arm64 — 전 스택 네이티브

**[실측]** `docker manifest inspect` 결과:

| 이미지 | arm64 |
|---|---|
| `grafana/tempo` | ✅ |
| `grafana/loki` | ✅ |
| `grafana/grafana` | ✅ |
| `prom/prometheus` | ✅ |
| `otel/opentelemetry-collector-contrib` | ✅ |
| `jaegertracing/jaeger` (v2) | ✅ |

Pinpoint 의 HBase 같은 구멍이 **하나도 없다.**

> ⚠️ **[추정]** `jaegertracing/all-in-one` 이 아니라 `jaegertracing/jaeger:2.x` 를 쓸 것.
> v1 라인은 1.76.0 에서 끝났고 all-in-one 은 9 개월째 푸시가 없다.
> v2.14.0 이 v1 collector/query/all-in-one 을 제거했다. (제거 노트 + 푸시 공백 기반 추론)

## 3. Tempo service graph 가 ServerMap 을 대체하는가

**근사하지만 한계가 분명하다.**

동작: `client`↔`server` 스팬(및 DB 호출은 `db.system`/`db.namespace`)을 메모리에서 짝지어
Prometheus 시계열로 방출한다.

**한계:**

1. **metrics-generator + Prometheus remote-write 대상이 필요하다.** Tempo 단독으로는 맵이 안 나온다
2. **계측 안 된 호출자는 `"user"` 로 뭉뚱그려진다.** Tempo generator 는 `peer_attributes` 로
   이름을 줄 수 있으나, **Alloy / Collector 의 `servicegraph` 커넥터는 항상 `user` 로 고정**
3. **샘플링이 수치를 왜곡한다.** 다운샘플링하면 호출 수가 과소 집계된다.
   `span_multiplier_key` 로 보정 가능하나 직접 설정해야 한다.
   **Pinpoint 는 에이전트 측에서 세므로 샘플링과 무관하게 정확하다 — 가장 뚜렷한 격차**
4. **카디널리티 위험** — 차원마다 시계열이 곱해진다. 한도 초과 시 `metric_overflow="true"`
5. **[문서]** Tempo 3.0 은 local-blocks processor 를 제거했다

**[문서]** Grafana 의 완성된 **Application Observability** 앱은 Cloud 전용이다.
셀프호스트에서는 service graph + span metrics + 대시보드를 직접 조립해야 한다.

## 4. 대체 아키텍처

**최소 구성 — 5 컨테이너:**
OTel Collector(contrib) → Tempo(트레이스 + metrics-generator) → Prometheus(메트릭)
→ Loki(로그) → Grafana(UI). **약 2~3GB RAM.**

**더 간단한 대안:** `grafana/otel-lgtm` 이 Loki/Grafana/Tempo/Mimir 를 한 컨테이너에 묶는다.
단일 노드 홈서버에 가장 적합(512Mi request / 2Gi limit).

어느 쪽이든 Pinpoint 의 HBase + ZooKeeper + MySQL + Redis + Collector + Web 보다 가볍고,
**에뮬레이션이 없다.**

## 5. 정직한 판정

### 진짜로 잃는 것

1. **SQL 바인드 값** — **후속 조사로 강등됨.**
   이 프로젝트는 `application-local.yml` 에 이미 `org.hibernate.orm.jdbc.bind: TRACE` 가 있어
   **바인드 값 자체는 확보돼 있다.** 잃는 것은 "값" 이 아니라 **"샘플링된 요청만 캡처하는 선택성"** 이다.
   Logback `TurboFilter` 로 게이팅하면 Pinpoint 급 절감이 가능하나 **검증되지 않은 조합**이라
   스파이크가 필요하다. 상세: [sampled-sql-bind-capture.md](sampled-sql-bind-capture.md)
   → **"해결 가능하나 직접 구현·검증 필요"** 로 재분류
2. **실시간 액티브 스레드** — **후속 조사로 철회됨.**
   Pinpoint 소스를 읽어보니 이 뷰는 스레드 프로파일링이 아니라
   **진행 중 요청을 경과시간 4 버킷(1s/3s/5s/Slow)으로 센 히스토그램**이었다.
   → Micrometer `http.server.requests.active`(LongTaskTimer)로 **동등 대체**되고,
   "그때 무슨 일이었나" 는 JFR 링버퍼로 **오히려 Pinpoint 보다 낫다**
   (Pinpoint 의 스레드 덤프는 온디맨드·요청 생존 시에만 동작).
   상세: [always-on-thread-visibility.md](always-on-thread-visibility.md)
   → **손실 아님**
3. **무설정 일관성.** Pinpoint 는 에이전트 1 개 + UI 1 개로 4 개 뷰가 나온다.
   OTel 은 5 컨테이너를 직접 배선·튜닝(service graph 설정, 카디널리티 한도, 보존, 대시보드)
4. **샘플링 하에서의 엣지 카운트 정확도** (§3-3)

### 진짜로 얻는 것

1. **전 스택 arm64 네이티브.** 이 프로젝트에서는 이게 결정적이다
2. **Boot 4 / Spring 7 / Hibernate 7 지원이 날짜와 함께 검증됨.**
   Pinpoint 3.1 내부 의존성은 아직 Boot 3.3.x
3. **로그.** Pinpoint 엔 없다. trace→log 상관관계는 일상 기능
4. **Tail sampling** — 에러·느린 트레이스 100% 보존, 나머지만 버림
5. **훨씬 나은 알람** — Pinpoint 는 MySQL 필수 + `SmsSender` 직접 구현
6. **락인 없음** — OTLP 로 한 번 계측하고 백엔드는 갈아끼운다

### 권고

**마이그레이션.** 근거는 기능 우열이 아니라 **제약 적합성**이다 —
홈서버가 arm64 이고(HBase 4 회 시도 전부 실패), 스택이 Boot 4.1 / Hibernate 7.4.5 /
Java 25 인데 Pinpoint 는 Hibernate 플러그인이 없고 Boot 4 지원이 미문서화다.

바인드 값 캡처와 실시간 스레드 뷰를 포기하고, 필요할 때 async-profiler/JFR 로 메운다.

**하이브리드 여지**: Pinpoint 3.1 collector 가 OTLP 메트릭을 받으므로 전환기에 병행 운용이
가능하다. 다만 HBase 를 에뮬레이션해야 하는 상황에서는 실익이 없다.

## 출처

- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/CHANGELOG.md>
- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md>
- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jdbc/README.md>
- <https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/>
- <https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/>
- <https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/>
- <https://grafana.com/docs/tempo/latest/metrics-from-traces/service_graphs/>
- <https://grafana.com/docs/tempo/latest/troubleshooting/metrics-generator/>
- <https://pinpoint-apm.gitbook.io/pinpoint/documents/alarm>
- <https://github.com/jaegertracing/jaeger/releases>
