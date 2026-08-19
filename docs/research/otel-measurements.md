# OTel 도입 효과 측정 (before / after)

측정 시점: 2026-08-17
환경: Apple Silicon(OrbStack), Java 25 Corretto, Spring Boot 4.1.0, MySQL 8.4

`tech-adoption-gate` 규약이 요구하는 산출물이다.
**"XX% 개선" 단독 표기를 쓰지 않고 절대값을 병기한다.**

---

## 1. N+1 방어 검증 — 관측성 공백 2번에 대한 답

### 문제 제기

`application.yml:24` 에 `default_batch_fetch_size: 100` 이 처음부터 있었지만
**정말 듣는지 확인한 적이 없었다.** 확인 수단이 `show-sql` 로그를 눈으로 세는 것뿐이었고,
그래서 이 도입의 목적 중 하나가 정확히 이 검증이었다.

### 실험 설계

| 항목 | 값 |
|---|---|
| 대상 | `PaymentEntity → CustomerEntity` (LAZY `@ManyToOne`) |
| 조회 | `paymentRepository.findAll(PageRequest.of(0, 30))` |
| 결제 건수 | 30 (각 건이 **서로 다른** customer 를 가리킨다) |
| 측정 | Hibernate `Statistics.getPrepareStatementCount()` |
| 테스트 | `src/test/java/com/example/sakila/observability/NPlusOneQueryCountTest.java` |

**대상 선택 이유**
- `rental` 슬라이스는 쓸 수 없다 — `RentalEntity` 는 FK 가 전부 스칼라라
  JPA 연관관계가 하나도 없어 N+1 이 발생하지 않는다
- `Film → Language` 는 부적합하다 — Sakila 의 언어가 6 개뿐이라 1 차 캐시가
  부모 조회를 중복 제거해 N+1 이 드러나지 않는다
- `Payment → Customer` 는 부모가 전부 달라 캐시가 개입하지 않는다

### 결과

| `default_batch_fetch_size` | 지연 로딩 쿼리 | 전체 쿼리 |
|---|---|---|
| **`-1` (끔)** | **30 회** | 32 회 |
| **`100` (현재 설정)** | **1 회** | 3 회 |

**30 회 → 1 회.** 배치 페치가 부모 30 건 조회를 `IN` 절 하나로 묶는다.

→ **`default_batch_fetch_size: 100` 은 실제로 작동한다.** 추측이 아니라 측정으로 확인했다.

### 재현 방법

```sh
# 현재 설정(방어 있음)
./gradlew test --tests "*NPlusOneQueryCountTest*" --rerun

# 대조군: application-test.yml 의 hibernate 아래에 다음을 넣고 재실행
#   default_batch_fetch_size: -1
# → 테스트가 실패하며 "지연 로딩 쿼리 30회" 를 출력한다
```

---

## 2. 에이전트 오버헤드

### 방법

부하 도구(k6·gatling)가 프로젝트에 없으므로 curl 반복으로 측정했다.
**표본 수와 방법을 명시하는 것이 조건이므로 그대로 적는다.**

| 항목 | 값 |
|---|---|
| 대상 | `GET /actuator/health` |
| 표본 | **200 회** (측정 전 워밍업 30 회 별도) |
| 워밍업 이유 | JIT 컴파일·커넥션 풀 초기화를 측정에서 제외 |
| 스크립트 | `bench.sh` (scratchpad) |

### 결과

| | p50 | p95 | p99 | 평균 |
|---|---|---|---|---|
| **에이전트 없음** | 2.06ms | 2.50ms | 3.15ms | 2.07ms |
| **에이전트 있음** | 2.20ms | 3.14ms | 4.98ms | 2.29ms |
| **차이** | **+0.14ms** | **+0.64ms** | **+1.83ms** | **+0.22ms** |

**해석**
- p50 기준 오버헤드는 0.14ms 로 무시할 만하다
- p99 에서 1.83ms 벌어지는데, 이는 에이전트의 배치 전송이 주기적으로 개입하기 때문으로 보인다
  (**[추정]** — 원인을 분리 측정하지는 않았다)
- 절대값이 2ms 대라 이 정도 증가가 사용자 체감에 미치는 영향은 없다

**한계 (명시)**
- 단일 엔드포인트, 단일 동시성(순차 요청) 측정이다
- `/actuator/health` 는 DB 를 거치지 않는 가벼운 경로다.
  DB 호출이 많은 실제 API 에서는 JDBC 계측 비용이 더해지므로 이 수치보다 클 수 있다
- 부하 상태(동시 요청 다수)의 오버헤드는 측정하지 않았다

---

## 3. 관측성 공백 4번 — Hikari 풀 시계열

**이전**: 데이터 없음. `maximum-pool-size: 10` 이 적정한지 판단할 수단이 없었다.

**이후**: `/actuator/prometheus` 에 노출된다.

```
hikaricp_connections_active{application="sakila-ddd-template",pool="HikariPool-1"} 0.0
hikaricp_connections_idle{application="sakila-ddd-template",pool="HikariPool-1"} 10.0
hikaricp_connections_max{application="sakila-ddd-template",pool="HikariPool-1"} 10.0
```

`pending`(대기 중) 지표도 함께 나오므로 풀 고갈을 사전에 감지할 수 있다.

---

## 4. Pinpoint 액티브 스레드 뷰 대체

Micrometer LongTaskTimer 가 **Pinpoint 와 같은 버킷**으로 진행 중 요청을 센다.

```
http_server_requests_active_seconds_bucket{...,le="1.0"} 0
http_server_requests_active_seconds_bucket{...,le="3.0"} 0
http_server_requests_active_seconds_bucket{...,le="5.0"} 0
```

Pinpoint 의 `fastCount/normalCount/slowCount/verySlowCount`(1s/3s/5s)와 구조가 같다.
대시보드: `observability/grafana/dashboards/active-requests.json`

---

## 5. JFR 상시 녹화 — 사후 회고 능력

`jcmd <pid> JFR.dump` 로 4.0MB 덤프를 뜬 결과:

| 이벤트 | 건수 | 크기 |
|---|---|---|
| `jdk.ThreadDump` | 1 | 70,806 bytes (jstack 형식 전체 덤프) |
| `jdk.ThreadPark` | 634 | 24,886 |
| `jdk.JavaMonitorWait` | 51 | 1,404 |
| `jdk.SocketRead` | 36 | 1,565 |
| **`jdk.JavaMonitorEnter`** | 2 | 46 (락 대기 + `previousOwner`) |
| `jdk.ExecutionSample` | 198 | 2,179 |

`maxage=6h` 링버퍼이므로 **사람이 그 시점에 붙어 있지 않아도** 6 시간 내 상황을 조회할 수 있다.
Pinpoint 의 스레드 덤프는 요청이 살아 있을 때만 동작하므로, 이 점에서는 JFR 이 우위다.

---

## 5-1. 에이전트 jar 부재 시 방어 (실사용 함정)

**증상**: OTel 없이 한 번 띄운 뒤 `docker-compose-otel.yml` 을 얹으면 앱이 crash-loop 한다.

```
Error opening zip file or JAR manifest missing : /otel/opentelemetry-javaagent.jar
agent library failed Agent_OnLoad: instrument
```

**원인**: `docker compose` 는 이미지가 이미 있으면 **build arg 가 바뀌어도 재빌드하지 않는다.**
`-javaagent` 는 주입되는데 이미지 안에 jar 이 없는 상태가 된다.
관측성을 켜려다 서비스를 내리는 셈이라 옵트인 설계의 취지에 반한다.

**해결**: 진입점 스크립트(`docker-entrypoint.sh`)가 jar 존재를 확인하고,
없으면 `-javaagent` 만 떼어낸 뒤 해결 방법을 경고로 알린다.

**검증**

| 상황 | 결과 |
|---|---|
| stale 이미지 + override | 경고 3줄 출력 후 **4.25초 정상 기동** (이전에는 crash-loop) |
| `--build` 로 재빌드 후 | 경고 0줄, 에이전트 2.30.0 부착, 5.08초 기동 |

## 6. 파이프라인 도달 확인

| 신호 | 확인 |
|---|---|
| 트레이스 → Tempo | `SELECT sakila` 등 5 건 수신 확인 |
| 로그 → Loki | `service_name: sakila-ddd-template` 라벨 확인 |
| 메트릭 → Prometheus | `/actuator/prometheus` scrape 대상 등록 |

## 7. 보안 — 메트릭 노출 경계

| 경로 | 응답 |
|---|---|
| 관리 포트 `/actuator/prometheus` | **200** |
| 서비스 포트 `/actuator/prometheus` | **401** |

관리 포트는 compose 네트워크 안에만 열리므로 JVM·DB 내부 지표가 외부에 노출되지 않는다.
