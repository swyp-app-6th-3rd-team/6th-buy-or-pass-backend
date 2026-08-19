# 샘플링 대상만 SQL 바인드 값 캡처하기

조사 시점: 2026-08-17

## 질문

Pinpoint 는 **샘플링된 트랜잭션만** SQL + 바인드 값을 캡처한다.
OTel 스택에서 같은 선택적 동작을 얻을 수 있는가?

배경: 이 프로젝트는 `application-local.yml` 에 이미
`org.hibernate.orm.jdbc.bind: TRACE` 가 있어 바인드 값을 확보하고 있다.
다만 이 방식은 **샘플링과 무관하게 전 요청**에 대해 로깅하므로 운영에서 켜기 부담스럽다.

## [실측] OTel 내장 옵션은 샘플링을 인식하지 않는다

`otel.instrumentation.jdbc.experimental.capture-query-parameters=true` 는
**Pinpoint 를 재현하지 못한다.** 소스로 확인했다.

### 근거 1 — 속성 추출이 샘플링 결정보다 먼저다

`Instrumenter.doStartImpl()` 의 순서:

```java
UnsafeAttributes attributes = new UnsafeAttributes();
for (AttributesExtractor extractor : attributesExtractors) {
    extractor.onStart(attributes, parentContext, request);   // ① 추출 — 가드 없음
}
...
spanBuilder.setAllAttributes(attributes);
Span span = spanBuilder.setParent(context).startSpan();      // ② 샘플러 결정
```

**①이 ②보다 먼저다.** 이는 의도된 설계로, 샘플러가 초기 속성을 보고 판단할 수 있어야
하기 때문이다(속성 기반 샘플링 지원). 대가로 "샘플링 탈락 시 추출 생략" 최적화를 포기했다.

### 근거 2 — 캡처 지점에 `isRecording()` 체크가 없다

`SqlClientAttributesExtractor`:

```java
if (captureQueryParameters && !isBatch) {
  Map<String, String> queryParameters = getter.getDbQueryParameters(request);
  if (queryParameters != null && !queryParameters.isEmpty()) {
    for (Map.Entry<String, String> entry : queryParameters.entrySet()) {
      attributes.put(DB_QUERY_PARAMETER.getAttributeKey(entry.getKey()), entry.getValue());
    }
  }
}
```

가드는 세 개뿐이다 — 캡처 옵션 켜짐 / 배치 아님 / 맵 비어있지 않음.
**샘플링은 참조조차 하지 않는다.**

`Span.setAttribute()` 자체는 미샘플링 시 no-op 이다(`PropagatedSpan` 이 `return this`).
하지만 그건 span 생성 **이후** 얘기이고, 시작 시점 속성은 이미 만들어진 뒤다.

### 결론

OTel 내장 옵션의 비용 구조는 Hibernate TRACE 로깅과 비슷하다.
로그 포맷팅·appender IO 는 아끼지만 **값 추출과 문자열화는 100% 트래픽에서 발생**한다.

### 추가로 확인된 제약

| 항목 | 내용 |
|---|---|
| 속성 이름 | `db.query.parameter.<key>` |
| **배치 미지원** | `isBatch` 면 캡처 안 함 → Hibernate 배치 INSERT/UPDATE 는 사각지대 |
| **sanitizer 강제 해제** | `this.querySanitizationEnabled = !captureQueryParameters && querySanitizationEnabled;` — 옵션이 아니라 부수효과다 |

**보안 주의**: CVE-2026-54704 는 sanitizer 가 **큰따옴표로 감싼** `CONNECT ... IDENTIFIED BY "secret"`
비밀번호를 마스킹하지 못해 `db.statement` 에 평문 노출된 건이다(2.28.0 에서 수정).
그런데 `capture-query-parameters` 가 **sanitizer 를 통째로 끄므로**, 2.28.0 이상으로 올려도
이 옵션을 켜면 보호받지 못한다. 별개의 하드닝 항목이다.

## Pinpoint 가 특이한 쪽이었다

| APM | 바인드 캡처 | 샘플링 인식 |
|---|---|---|
| **Pinpoint** | ✅ | ✅ **트랜잭션 시작 시점 결정** |
| Elastic APM | ❌ 정책적으로 캡처 안 함 | — |
| Datadog | ❌ APM 엔 없음 (별도 DBM 제품으로 분리) | — |
| New Relic | ✅ | **[미확인]** |
| OTel | ⚠️ 실험적 | ❌ |

Pinpoint 는 **SQL 실행 전에** 샘플링을 결정하고 탈락하면 span 을 아예 만들지 않는다.
JDBC 인터셉터가 값을 붙일 대상이 없으므로 포맷팅 비용이 통째로 사라진다.

## 대안 순위

### 1순위 — Logback `TurboFilter` 로 바인드 로거 게이팅 (권장)

**TurboFilter 가 적합한 이유: 로깅 이벤트 객체가 생성되기 *전에* 실행된다.**
미샘플링 요청은 메시지 포맷팅과 IO 를 통째로 건너뛴다 — Pinpoint 와 같은 종류의 절감이다.

```java
public class SampledOnlyTurboFilter extends TurboFilter {
  @Override
  public FilterReply decide(Marker marker, Logger logger, Level level,
                            String format, Object[] params, Throwable t) {
    SpanContext ctx = Span.current().getSpanContext();
    if (ctx.isValid() && !ctx.isSampled()) {
      return FilterReply.DENY;
    }
    return FilterReply.NEUTRAL;
  }
}
```

```xml
<configuration>
  <turboFilter class="com.example.sakila.config.SampledOnlyTurboFilter"/>
</configuration>
```

**필수 주의사항**

| 항목 | 이유 |
|---|---|
| **`ctx.isValid()` 가드 필수** | 없으면 배경 스레드·풀 워밍업·기동 코드(활성 span 없음)의 로그가 전부 조용히 사라진다 |
| **`org.hibernate.orm.jdbc.bind` 로 스코프 한정** | 전역 적용하면 미샘플링 요청의 WARN/ERROR 까지 사라진다 |
| **`decide()` 는 부작용 없이 멱등하게** | SLF4J 2.0 fluent API 사용 시 두 번 호출된다(logback-classic 1.5.21+). Boot 4.1 은 최신 Logback 을 쓴다 |
| **tail sampling 과 병행 금지** | `isSampled()` 는 head 결정만 반영하므로, collector 단 tail sampling 을 함께 쓰면 남는 로그와 남는 트레이스가 어긋난다 |
| **컨텍스트 전파 경계** | async/스레드풀에서 `Span.current()` 가 무효일 수 있다. 이때는 **"남긴다"를 기본값**으로 (침묵보다 낫다) |

`isSampled()` vs `isRecording()`: **로그 게이팅에는 `isSampled()`** 가 맞다(내보내지는 것과 일치시켜야 함).
**속성 설정에는 `isRecording()`** 이 맞다(설정한 속성이 유지되는지를 결정).

**비용·위험**: 약 20 줄 + 설정 한 줄. 새 의존성 없음(이미 OTel·Logback·MDC 보유).
완전히 되돌릴 수 있고, 트레이스 내보내기나 sanitizer 설정을 건드리지 않는다.

**이 프로젝트에 잘 맞는 이유**: 이미 `CorrelationIdFilter` 가 MDC 에 `correlationId` 를 넣고
레벨별 파일 appender 가 있다. 기존 로깅 자산을 그대로 두고 **샘플링 게이트만 얹는** 구조다.

> **[추정] 검증되지 않은 조합이다.** TurboFilter API 와
> `Span.current().getSpanContext().isSampled()` 관용구는 각각 문서화돼 있으나,
> **둘을 합친 공개 구현체를 찾지 못했다.** 스파이크로 확인해야 한다.

**검증 방법 (싸다)**: 샘플러를 1% 로 두고 부하를 준 뒤 바인드 로그 줄 수를 센다.
쿼리량의 **약 1%** 여야 한다. 100% 샘플링 대조군으로 before/after 절대값을 병기한다.

**잔여 비용**: Hibernate 가 로거를 해석하고 `isTraceEnabled()` 를 바인드마다 평가하는 것은 남는다.
현재보다 크게 싸지지만 문자 그대로 0 은 아니다.

### 2순위 — datasource-proxy `QueryExecutionListener`

`QueryExecutionListener`(또는 `JdbcLifecycleEventListenerAdapter`)를 구현해
`beforeQuery` 에서 span 컨텍스트로 게이팅하고, `ExecutionInfo` 의 `QueryInfo` 목록에서
파라미터를 읽는다.

- **충실도 최고** — 바인드 값이 **span 자체에** 들어가고(진짜 Pinpoint 시맨틱),
  "에러일 때만 / N ms 초과일 때만" 같은 조건도 붙일 수 있다. 1·3순위로는 불가능하다.
- 샘플러가 파라미터를 보게 하려면 `afterQuery` 가 아니라 **`beforeQuery`** 에서 설정해야 한다.
- 비싼 추출은 `isRecording()` 으로 게이팅한다.
- **[추정]** 기성 datasource-proxy↔OTel 샘플링 연동을 찾지 못했다. 직접 만들어 유지해야 한다.
- p6spy 로도 가능하다(`logMessageFormat` 커스텀 + 빈 메시지 DENY 필터). 다만 datasource-proxy 가
  더 가볍고 리스너 프레임워크가 목적에 맞다.

**선택 기준**: 바인드를 **트레이스 안에** 원하면 2순위, **로그로 충분하면** 1순위.

### 3순위 — OTel `capture-query-parameters` (권장하지 않음)

- 비용: 프로퍼티 한 줄
- **위험 높음**: sanitizer 강제 해제, 실험적 상태, 고 QPS 에서 span 속성 팽창
- **충실도 낮음**: 샘플링 무관하게 100% 캡처. 배치 미지원
- → **스테이징/디버그 도구로는 합리적.** 비용 절감이 목적이라면 문제를 해결하지 못한다.

### 채택 불가 — tail sampling

**목적에 역행한다.** tail sampling 은 SDK 에서 100% 캡처를 전제하므로 줄이려던
에이전트측 비용이 오히려 **늘어난다**. 게다가 `isSampled()` 와 실제 보존분이 어긋나
1·2 순위와 결합하면 정합성이 깨진다. (backend 비용 최적화 수단이지 agent 비용 최적화가 아니다.)

### 채택 불가 — Hibernate `StatementInspector`

**바인드 값을 볼 수 없다.** Hibernate 7 회귀가 아니라 원래 계약이다 —
JDBC statement 가 **prepare 되기 전에** 훅이 걸리므로 파라미터가 아직 존재하지 않는다.
SQL + 바인드 값을 트레이스 조건부로 함께 주는 Hibernate 훅은 없다.
공식 가이드도 DataSource 프록시 또는 `org.hibernate.orm.jdbc.bind` 로거 둘 중 하나를 안내한다.

## 판정

**"운영에서 바인드 값" 은 해결 가능하다.** TurboFilter 게이팅으로 Pinpoint 급 절감을 얻는다.
다만 **검증되지 않은 조합**이라 스파이크가 필요하며, 이것이 OTel 전환 시 추가로 떠안는 작업이다.

→ 손실 목록에서 "SQL 바인드 값" 은 **"해결 가능하나 직접 구현·검증 필요"** 로 강등한다.

## 출처

- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation-api/src/main/java/io/opentelemetry/instrumentation/api/instrumenter/Instrumenter.java>
- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation-api-incubator/src/main/java/io/opentelemetry/instrumentation/api/incubator/semconv/db/SqlClientAttributesExtractor.java>
- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/jdbc/README.md>
- <https://advisories.gitlab.com/maven/io.opentelemetry.javaagent/opentelemetry-javaagent/CVE-2026-54704/>
- <https://logback.qos.ch/manual/filters.html> · <https://logback.qos.ch/apidocs/ch.qos.logback.classic/ch/qos/logback/classic/turbo/TurboFilter.html>
- <https://jdbc-observations.github.io/datasource-proxy/docs/snapshot/user-guide/index.html>
- <https://docs.jboss.org/hibernate/orm/7.2/javadocs/org/hibernate/resource/jdbc/spi/StatementInspector.html>
- <https://opentelemetry.io/blog/2022/tail-sampling/>
- <https://www.elastic.co/guide/en/apm/get-started/7.15/data-security.html> · <https://docs.datadoghq.com/database_monitoring/connect_dbm_and_apm/>
- <https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/7413> · <https://github.com/elastic/apm/issues/220>
