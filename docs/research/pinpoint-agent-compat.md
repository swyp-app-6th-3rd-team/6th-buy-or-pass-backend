# Pinpoint 3.1.0 에이전트 — 이 스택 계측 호환성

조사 시점: 2026-08-17

## 질문

이 프로젝트 스택에서 Pinpoint 에이전트가 **실제로 SQL 을 계측하는가?**

| 항목 | 버전 |
|---|---|
| Spring Boot | 4.1.0 |
| Java | 25 (Amazon Corretto) |
| Hibernate | 7.4.5 |
| MySQL Connector/J | 9.7.0 |
| Jackson | 3.1.4 (`tools.jackson`) |
| QueryDSL | OpenFeign 포크 7.5 |

이 질문이 중요한 이유는 **Pinpoint 가 계측에 실패해도 조용히 실패**하기 때문이다.
앱은 정상 기동하고, 에이전트도 붙고, Web UI 에 노드도 보이는데 CallStack 만 비어 있다.
그래서 "설치됐다" 와 "쓸모 있다" 사이에 큰 간극이 있다.

## 왜 Hibernate 가 아니라 JDBC 가 관건인가

계획 초기에는 Hibernate 7.4.5 를 최대 리스크로 봤으나, 계측 경로를 파고들면 무게중심이 이동한다.

**[문서]** Pinpoint 공식 문서: "Pinpoint 는 MySQL 서버를 직접 모니터링하지 않고
**MySQL JDBC 클라이언트의 호출을 가로챈다**." `profiler.jdbc.mysql=true` 가 기본값이라
별도 설정이 필요 없다. — <https://pinpoint-apm.gitbook.io/pinpoint/faq>

**[실측]** 에이전트 tarball 의 `plugin/` 디렉터리에 **Hibernate 플러그인이 아예 없다.**
SQL 추적이 Hibernate 에 의존하지 않는다는 방증이다.

→ 따라서 확인해야 할 것은 Hibernate 7 지원이 아니라 **Connector/J 9.7.0 지원 여부**다.

## [실측] 정적 검증 결과

`pinpoint-agent-3.1.0.tar.gz` (`git.commit.id=e3940e5`, `build.time=2026-05-20`) 를
직접 풀어 바이트코드를 분석했다. 문서가 아니라 실물 기준이다.

### 1. Java 25

| 확인 | 결과 |
|---|---|
| `JvmVersion` enum (`boot/pinpoint-commons-3.1.0.jar`) | `JAVA_25` 상수 존재 (enum 은 `JAVA_32` 까지, 별도 `UNSUPPORTED` 존재) |
| 구버전의 `JavaVersionValidator` 클래스 | 부트스트랩 jar 에 **없음** — 하드 버전 게이트 제거됨 |

```sh
javap -p JvmVersion.class | grep -oE 'JAVA_[0-9]+|UNSUPPORTED' | sort -u -V | tail
# → JAVA_25 ... JAVA_32 UNSUPPORTED
```

→ Java 25 를 `UNSUPPORTED` 로 튕길 근거가 코드에 없다.

**[문서]** 공식 호환성 표상 에이전트 지원 Java 는 3.0.x 가 8~21, **3.1.x 가 8~25**.
즉 이 프로젝트에서 3.1.x 는 선택이 아니라 **필수**다.

### 2. MySQL 플러그인 후킹 대상

`plugin/pinpoint-mysql-jdbc-driver-plugin-3.1.0.jar` 를 디스어셈블해 추출.

```sh
javap -p -c com/navercorp/pinpoint/plugin/jdbc/mysql/MySqlPlugin.class \
  | grep -E '// String com\.mysql'
```

후킹하는 클래스와 Connector/J 9.7.0 존재 여부:

| 후킹 대상 | 9.7.0 에 존재 |
|---|---|
| `com.mysql.cj.jdbc.ConnectionImpl` | ✅ |
| `com.mysql.cj.jdbc.StatementImpl` | ✅ |
| `com.mysql.cj.jdbc.ClientPreparedStatement` | ✅ |
| `com.mysql.cj.jdbc.ServerPreparedStatement` | ✅ |
| `com.mysql.cj.jdbc.NonRegisteringDriver` | ✅ |
| `com.mysql.cj.jdbc.CallableStatement` | ✅ |

검증 방법:
```sh
JAR=~/.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j/9.7.0/*/mysql-connector-j-9.7.0.jar
unzip -l "$JAR" | grep "com/mysql/cj/jdbc/ConnectionImpl.class"
```

플러그인은 구 네임스페이스(`com.mysql.jdbc.*`)와 신 네임스페이스(`com.mysql.cj.jdbc.*`)를
둘 다 등록한다. 9.7.0 은 신 네임스페이스이며 **6/6 전부 존재한다.**

### 3. 후킹 메서드 — 전부 JDBC 표준 API

```
executeQuery(String)
executeUpdate(String) / (String,int) / (String,int[]) / (String,String[])
execute(String)      / (String,int)
```

`java.sql.Statement` 인터페이스 메서드다. **Connector/J 가 JDBC 규약을 깨지 않는 한
시그니처를 바꿀 수 없다.** 당초 우려한 "내부 클래스 시그니처 변경으로 인한 계측 실패"
위험이 예상보다 낮다는 뜻이다.

### 4. 필요한 플러그인 존재 여부

| 플러그인 | 존재 | 용도 |
|---|---|---|
| `pinpoint-tomcat-plugin` | ✅ | HTTP 진입점 (Boot 4 내장 Tomcat) |
| `pinpoint-spring-plugin` / `-spring-boot-plugin` | ✅ | Spring MVC 계층 |
| `pinpoint-mysql-jdbc-driver-plugin` | ✅ | **SQL 트레이스 (핵심)** |
| `pinpoint-hikaricp-plugin` | ✅ | 커넥션 풀 |
| `pinpoint-logback-plugin` | ✅ | 로그 MDC 연동 |
| Hibernate 플러그인 | ❌ 없음 | 애초에 불필요 (위 참조) |

### 5. 에이전트 구조

```
pinpoint-agent-3.1.0/
├── pinpoint-bootstrap.jar         ← -javaagent 대상 (버전 없는 이름)
├── pinpoint-bootstrap-3.1.0.jar   ← 위와 동일 파일 (MD5 96c5251d… 일치)
├── pinpoint-root.config           ← 최상위 설정. 여기서 프로파일을 고른다
├── boot/ lib/ plugin/
├── profiles/{local,release}/pinpoint.config
└── log4j2-agent.xml
```

**`-javaagent` 는 버전 없는 `pinpoint-bootstrap.jar` 를 쓴다.** 버전이 박힌 쪽과
바이트 단위로 동일하므로 업그레이드 시 경로를 고칠 필요가 없다.

설정 우선순위: `pinpoint-root.config` → `profiles/<profile>/pinpoint.config`
→ JVM `-Dprofiler.*` (최우선)

### 6. 검증 시 주의할 기본값

**[실측]** `profiles/release/pinpoint.config`:

| 설정 | 기본값 | 의미 |
|---|---|---|
| `profiler.sampling.counting.sampling-rate` | **20** | 20 건 중 1 건만 수집 |
| `profiler.transport.grpc.span.sender.type` | `BATCH` | **3.1.0+ collector 필요** |
| `profiler.logback.logging.transactioninfo` | `false` | MDC 연동 꺼짐 |

→ 검증할 때 샘플링을 **1 로 강제**하지 않으면 단발 요청이 통째로 누락돼
"계측 실패" 로 오판한다.

## 판정

**가설 A(계측 성립) 쪽으로 확률이 크게 이동했다.** 사전 추정 A 60% / B 25% / C 15% 에서:

- **가설 C (에이전트 기동 실패)** — `JAVA_25` 상수 존재 + 하드 게이트 부재로 **사실상 배제**
- **가설 B (SQL 미계측)** — 후킹 클래스 6/6 존재 + 후킹 메서드가 JDBC 표준 API 라 **크게 낮아짐**

### 다만 이것으로 완료 선언하지 않는다

**클래스·메서드 존재는 필요조건이지 충분조건이 아니다.** Pinpoint 는 클래스를 찾은 뒤
바이트코드를 주입하는데, 주입 자체가 런타임에 실패할 수 있다(모듈 시스템, 클래스로더
격리, Boot 4 의 로딩 방식 등). 정적 분석으로는 여기까지가 한계다.

**미완**: 실제 요청을 흘려 CallStack 에 SQL 이 찍히는지 확인하는 실행 검증.
백엔드(HBase)를 띄우지 못해 수행하지 못했다 — [pinpoint-hbase-arm64.md](pinpoint-hbase-arm64.md) 참조.

## 실행 검증을 재개한다면

판정 기준(전부 충족해야 함):

1. ServerMap 에 `sakila-ddd-template → MySQL` 엣지가 그려진다
2. **`GET /api/rentals` 호출 후 CallStack 에 실행된 SQL 문자열과 소요시간(ms)이 보인다** ← 핵심
3. 로그 한 줄에서 `correlationId` 와 `PtxId` 가 동시에 확인된다

**대리지표로 판정하지 않는다**: 컨테이너 Up · "Pinpoint agent started" 로그 ·
Web UI 접속 성공 · 빌드/테스트 통과.

## 출처

- <https://pinpoint-apm.gitbook.io/pinpoint/main> — 버전별 Java 호환성 표
- <https://pinpoint-apm.gitbook.io/pinpoint/faq> — JDBC 클라이언트 가로채기 방식
- <https://github.com/pinpoint-apm/pinpoint/releases/tag/v3.1.0> — 3.1.0 릴리스 노트
- <https://github.com/pinpoint-apm/pinpoint/tree/master/agent-module/plugins/mysql-jdbc>
