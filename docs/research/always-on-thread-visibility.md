# 상시 스레드 가시성 — Pinpoint 액티브 스레드 뷰 대체

조사 시점: 2026-08-17

## 질문

Pinpoint 의 "실시간 액티브 스레드" 뷰를 OTel 스택에서 대체할 수 있는가?
특히 **"어젯밤 3시에 멈췄는데 그때 스레드가 뭘 하고 있었나"** 에 답할 수 있는가?
Datadog 같은 엔터프라이즈 APM 이 필요한가?

## [실측] 먼저 — 그 기능의 정체를 확인했다

Pinpoint 소스를 직접 읽었다. **액티브 스레드 뷰는 스레드 프로파일링이 아니다.**

`DefaultActiveTraceRepository.getActiveTraceHistogram()` 은 진행 중인 트레이스를 순회하며
`elapsedTime = currentTime - startTime` 을 계산해 `findHistogramSlot` 으로 버킷에 넣는다.
`DefaultActiveTraceHistogram` 이 보유한 것은 **정수 4 개뿐**이다.

```
fastCount / normalCount / slowCount / verySlowCount
```

임계값은 `HistogramSchemas.java` 에 **1s / 3s / 5s / Slow** 로 박혀 있다.

→ 즉 **"지금 실행 중인 요청 수를 경과시간별로 센 것"** 이다. 스레드 덤프 샘플링이 아니다.

별도의 **Active Thread Dump** 드릴다운은 성격이 다르다:
- **온디맨드** — 사람이 누를 때만 동작
- **best-effort** — 요청이 이미 끝났으면 "may be completed" 반환
- **기본 비활성** — `pinpoint.modules.realtime.enabled=true` + Redis pub/sub 필요

**결론: Pinpoint 는 "지금 진행 중" 은 잘 보여주지만 "그때 무슨 일이었나" 는 답하지 못한다.**

## 그래서 요구가 둘로 갈린다

| # | 요구 | Pinpoint | 대체 수단 |
|---|---|---|---|
| ① | 진행 중 요청의 경과시간 분포 | ✅ 잘함 | **Micrometer LongTaskTimer** |
| ② | 멈춘 시점의 스레드 상태 회고 | ❌ 못함 | **JFR 링버퍼** |

둘 다 **새 컨테이너 없이** 해결된다.

---

## 대체 ① — Micrometer LongTaskTimer

Spring Boot 가 **자동 설정**하는 `http.server.requests.active` 가 정확히 같은 것이다.

**[실측/문서]** LongTaskTimer 는 **진행 중인 작업만** 측정한다. percentile histogram 을 켜면
`_seconds_bucket` 이 OpenMetrics **GaugeHistogram** 으로 나오는데, 이는
**현재 실행 중인 작업을 duration 별로 센 값**이다 — Pinpoint 히스토그램과 구조적으로 동일하다.

SLO 버킷을 Pinpoint 와 같은 1s / 3s / 5s 로 맞추면 Grafana 패널이 같은 화면이 된다.

```yaml
management:
  metrics:
    distribution:
      slo:
        http.server.requests.active: 1s,3s,5s
```

> **OTel 쪽 대안은 부적합**: `http.server.active_requests` 는 experimental 옵트인
> (`OTEL_INSTRUMENTATION_HTTP_SERVER_EMIT_EXPERIMENTAL_TELEMETRY`)이고
> **duration 버킷이 없다.** 이 용도엔 Micrometer 가 맞다.

---

## 대체 ② — JFR 링버퍼 (Pinpoint 에 없던 기능)

**JFR 은 애초에 상시 운영용으로 설계됐다.** JDK 가 자기 설정 파일에 명시한 내용:

| 프로파일 | 라벨 | 설명 |
|---|---|---|
| `default.jfc` | **"Continuous"** | "운영 환경 **상시 사용에 안전**, 통상 **1% 미만** 오버헤드" |
| `profile.jfc` | "Profiling" | "통상 **2% 내외** 오버헤드" |

**[실측]** Java 25(Amazon Corretto) 컨테이너에서 직접 확인:
```sh
docker run --rm amazoncorretto:25-alpine \
  sh -c 'grep description= /usr/lib/jvm/java-25-amazon-corretto/lib/jfr/default.jfc'
# → "Low overhead configuration safe for continuous use in production
#     environments, typically less than 1 % overhead."
```

### "3am 문제" 를 푸는 것은 `maxage` 다

```
-XX:StartFlightRecording=disk=true,maxage=6h,settings=profile,dumponexit=true
```

`disk=true` + `maxage` 조합이 **디스크에 롤링 링버퍼**를 유지한다.
사람이 그 시점에 붙어 있을 필요가 없다 — 나중에 덤프해서 그 구간을 본다.

**[실측]** `-XX:StartFlightRecording=help` 로 확인한 옵션 의미:
- `maxage` — "디스크에 기록을 유지할 최대 시간. `disk=true` 일 때만 유효"
- `maxsize` — 디스크 상한(바이트)

### 스레드 상태 이벤트 — 오히려 Pinpoint 보다 깊다

**[실측]** Java 25 `profile.jfc` 에 포함된 이벤트:

| 이벤트 | 기본 활성 | 내용 |
|---|---|---|
| `jdk.ThreadDump` | ✅ | **jstack 형식 전체 덤프**(문자열 필드). `1s` 주기까지 조절 가능 |
| `jdk.JavaMonitorEnter` | ✅ 20ms 임계 (profile 은 10ms) | 락 대기 + **`previousOwner`** (누가 쥐고 있었는지) |
| `jdk.JavaMonitorWait` | ✅ | 대기 |
| `jdk.ThreadPark` | ✅ | park 대기 |
| `jdk.SocketRead` / `jdk.FileRead` | ✅ | I/O 대기 |
| `jdk.ThreadCPULoad` / `jdk.ThreadContextSwitchRate` | ✅ | 스레드별 CPU·컨텍스트 스위치 |

Pinpoint 의 액티브 스레드 뷰가 "몇 개가 얼마나 오래 실행 중인가" 라면,
JFR 은 **"누가 무엇 때문에 멈췄는가 + 그 시점 스택트레이스"** 까지 답한다.

---

## Datadog 이 필요한가 — 아니다

**[문서]** 다른 APM 들의 스레드 가시성:

| APM | 방식 | 비고 |
|---|---|---|
| **Datadog Continuous Profiler** | per-thread 타임라인 + 락 소유권 | **데이터 소스가 JFR 이다** |
| Pinpoint | 4 버킷 히스토그램 + 온디맨드 덤프 | 사후 조사 불가 |
| **JFR 직접** | 위 이벤트 전부 | **JDK 내장, 무료** |

Datadog 의 UI 가 확실히 더 편하지만, **역량의 차이가 아니라 편의성의 차이**다.
그 데이터는 Java 25 에 이미 무료로 들어 있다.

---

## 권장 구성 (순위)

### 1순위 — Micrometer LongTaskTimer + JFR 링버퍼

- **새 컨테이너 0 개.** 둘 다 이미 있는 것을 켜기만 한다
- JFR: `-XX:StartFlightRecording=disk=true,maxage=6h,settings=profile`
- 요구 ①②를 모두 커버
- **비용 0, 위험 낮음**

### 2순위 — Pyroscope 추가 (플레임그래프가 필요하면)

- **[실측]** `grafana/pyroscope` arm64 네이티브 (v2.2.1, 2026-08-06 릴리스)
- 설정: `PYROSCOPE_PROFILER_EVENT=wall` + `PYROSCOPE_FORMAT=jfr`
- **[문서]** **wall 모드는 Java 만 지원**한다(다른 언어는 CPU only).
  wall 은 블록·대기 중인 스레드까지 잡으므로 CPU 프로파일링보다 이 용도에 맞다.
  wall 활성 시 `cpu` 도 함께 수집된다
- 컨테이너 1 개 추가

### 기각 — Cryostat

- **[실측]** arm64 이미지는 있다 (`quay.io/cryostat/cryostat`)
- 하지만 **K8s 지향**이고 JVM 하나를 위해 **5 개 컴포넌트**를 띄운다
- **[문서]** 그 `jfr-datasource` 는 **deprecated**

### 기각 — Parca / eBPF 계열

- **CPU 스택만** 잡는다. **JVM 스레드 상태(BLOCKED/WAITING)를 모른다**
- Java 심볼화가 약하다
- 이 요구(스레드 상태)에 근본적으로 안 맞는다

### 기각 — Datadog

- 위 참조. 데이터 소스가 JFR 이라 역량 이득이 없다

---

## 유의사항

1. **[문서] JFR 보존은 chunk 단위다.** `maxage=6h` 가 정확히 6 시간을 보장하지 않고
   다소 적게 유지된다. 여유를 두고 잡을 것
2. **[실측] `jvm.thread.count` 는 플랫폼 스레드만 센다.**
   이 프로젝트는 현재 가상 스레드를 쓰지 않으므로 문제없으나, 도입하면 사각지대가 생긴다
3. **[실측] 이 머신은 OrbStack**(커널 7.0.14, aarch64) 이라 진짜 리눅스 VM 이다.
   JEP 509(`jdk.CPUTimeSample`)는 Linux 게이트이나 arch 게이트는 아니므로
   **arm64 컨테이너에서 사용 가능**하다(로컬 macOS 개발 중에는 불가)

## 판정

**손실 항목에서 "실시간 스레드 뷰" 를 제거한다.**

- 요구 ①(진행 중 요청 분포) → Micrometer LongTaskTimer 로 **동등 대체**
- 요구 ②(사후 회고) → JFR 링버퍼로 **Pinpoint 보다 우위**

Datadog 급 엔터프라이즈 APM 은 필요하지 않다.

## 출처

- <https://github.com/pinpoint-apm/pinpoint/blob/master/agent-module/profiler/src/main/java/com/navercorp/pinpoint/profiler/context/active/DefaultActiveTraceRepository.java>
- <https://github.com/pinpoint-apm/pinpoint/blob/master/commons/src/main/java/com/navercorp/pinpoint/common/trace/HistogramSchemas.java>
- <https://pinpoint-apm.gitbook.io/pinpoint/documents/realtime>
- <https://github.com/openjdk/jdk/blob/master/src/jdk.jfr/share/conf/jfr/default.jfc>
- <https://docs.micrometer.io/micrometer/reference/concepts/long-task-timers.html>
- <https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html>
- <https://grafana.com/docs/pyroscope/latest/configure-client/profile-types/>
- <https://openjdk.org/jeps/509> · <https://openjdk.org/jeps/518>
- <https://opentelemetry.io/docs/specs/semconv/http/http-metrics/>
- <https://opentelemetry.io/blog/2026/profiles-alpha/>
