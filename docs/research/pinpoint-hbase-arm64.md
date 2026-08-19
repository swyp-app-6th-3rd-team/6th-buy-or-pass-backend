# Pinpoint 백엔드를 Apple Silicon 에서 띄우기 — 실패 기록

조사 시점: 2026-08-17
결론: **미해결.** 4 회 시도 전부 HBase 초기화 단계에서 실패.

## 왜 HBase 가 문제인가

**[실측]** Pinpoint 3.1.0 이미지의 아키텍처 지원:

```sh
docker manifest inspect pinpointdocker/pinpoint-collector:3.1.0 \
  | grep -oE '"architecture": "[^"]*"' | sort -u
```

| 이미지 | arm64 |
|---|---|
| `pinpointdocker/pinpoint-collector:3.1.0` | ✅ 있음 |
| `pinpointdocker/pinpoint-web:3.1.0` | ✅ 있음 |
| `pinpointdocker/pinpoint-hbase:3.1.0` | ❌ **amd64 단일 아키텍처** (manifest list 없음) |

**[문서]** HBase 이미지는 2.4.1~2.5.4 에는 arm64 가 있었으나 3.x 에서 빠졌다.

**[문서]** HBase 는 대체 불가능하다. Pinpoint 3.x 는 이중 저장소 구조로
**HBase = 트레이스/스팬(필수)**, Pinot = 시계열 메트릭이다. Pinot 도입은 HBase 부하를
덜었을 뿐 트레이스 저장은 그대로 HBase 다. 커뮤니티의 Elasticsearch·Cassandra 요청
(이슈 #284, #4872)은 모두 수용되지 않았다.

→ 즉 arm64 홈서버에서 Pinpoint 를 쓰려면 **HBase 한 컨테이너만 에뮬레이션**해야 한다.
그런데 그 한 컨테이너가 뜨지 않았다.

## 실제 최소 구성은 6 컨테이너

**[실측]** 계획 단계의 "3 컨테이너(HBase + Collector + Web)" 는 틀린 산정이었다.

| 컨테이너 | 왜 필요한가 |
|---|---|
| `zoo1` (Zookeeper) | HBase 가 요구 |
| `pinpoint-hbase` | 트레이스 저장소 |
| `pinpoint-redis` | collector·web 이 참조 |
| `pinpoint-mysql` | web 의 사용자·알람 메타데이터 (트레이스와 무관) |
| `pinpoint-collector` | 에이전트 수신 |
| `pinpoint-web` | UI |

공식 compose 는 여기에 `zoo2`·`zoo3`·`flink`·`pinot`·`kafka`·`batch` 까지 얹는다.

## 시도 이력

| # | 설정 | 결과 | 최대 JVM 정지 |
|---|---|---|---|
| 1 | 기본 힙 + CMS + 256 리전 | 8 개에서 정지 | 190s |
| 2 | 4G 힙 + CMS + 256 리전 | 5 개에서 master 사망 | 59s |
| 3 | 2G 힙 + **ParallelGC** + 256 리전 | 5 개에서 master 사망 | 213s |
| 4 | 2G + ParallelGC + **4 리전** + ZK 타임아웃 180s | 7 개에서 master 사망 | **225s** |

**JVM 레벨 조정(힙·GC)과 리전 수 감축이 전부 무효했다.** 오히려 정지 시간이 늘었다.

**[실측]** 4 차 시도 시점의 자원 여유:

| 항목 | 값 |
|---|---|
| HBase 힙 사용 | 1.8G / 2G |
| 전체 컨테이너 메모리 | 4.1G / 15.66G |
| 호스트 CPU | 10% 미만 |

자원이 남는데 225 초 정지가 났다. **힙·GC 가설은 이 측정으로 기각된다.**

**[추정]** 원인은 QEMU 에뮬레이션 계층에서 멀티스레드 JVM 의 스케줄링이 무너지는 것.
근거는 ①JVM 레벨 조정이 전부 무효 ②자원 여유 충분 ③HBase 자신이
`JvmPauseMonitor: Detected pause in JVM **or host machine**` 으로 기록
④Docker 공식 문서가 QEMU 에뮬레이션을 "best effort" 로 규정하고 JVM 계열의
랜덤 행(hang)을 알려진 실패 유형으로 언급.
**단정하지 않는다** — 에뮬레이션 계층 내부를 직접 계측하지는 않았다.

## 발견한 함정 6 개

아래는 **[실측]** 이지만, **x86 네이티브에서도 동일하게 발생하는지는 확인하지 못했다.**
1·2·3·5·6 은 아키텍처와 무관한 설정/스크립트 문제라 x86 에서도 유효할 가능성이 높고
(**[추정]**), 4 는 에뮬레이션 특유일 가능성이 높다.

### 1. HBase 이미지가 ZK 3 노드를 하드코딩

`hbase-site.xml` 에 `hbase.zookeeper.quorum=zoo1,zoo2,zoo3` 이 박혀 있다.
1 노드로 줄이면 `UnknownHostException: zoo2` 로 테이블 생성이 끝나지 않는다.
→ 기동 시 `sed` 로 덮어쓴다.

### 2. ZK 세션 타임아웃은 서버 상한이 이긴다

ZK 서버는 클라이언트 요청을 `maxSessionTimeout = 20 × tickTime` 으로 잘라낸다.
기본 `tickTime=2000` 이면 상한 40 초라, HBase 가 90 초를 요청해도 **40 초로 협상된다.**

`hbase-site.xml` 에 `zookeeper.session.timeout=180000` 을 넣어도 **협상 결과는 40000 그대로다** —
설정이 파일에 들어간 것과 효과가 있는 것은 다르다.

**[실측]** `zookeeper:3.4.13` 이미지는 `ZOO_MAX_SESSION_TIMEOUT` 을 지원하지 않는다
(entrypoint 가 읽는 변수 목록에 없음). `ZOO_TICK_TIME=10000` 으로 우회한다.

진짜 확인 방법:
```sh
docker logs <zoo> | grep -oE "negotiated timeout [0-9]+"   # 40000 이면 안 먹은 것
```

### 3. `HBASE_OPTS` 는 환경변수로 주입되지 않는다

`hbase-env.sh` 44 행이 `export HBASE_OPTS="-XX:+UseConcMarkSweepGC"` 로 덮어쓴다.
compose 의 `environment:` 에 넣어도 무시되므로 파일을 직접 고쳐야 한다.
(`HBASE_HEAPSIZE` 는 주석 처리돼 있어 환경변수가 먹는다 — 둘의 동작이 다르다.)

확인:
```sh
docker exec <c> sh -c "cat /proc/*/cmdline | tr '\0' '\n' | grep -E '^-XX:\+Use|^-Xmx'"
```

### 4. 리전 256 개 동시 생성 (에뮬레이션 특유로 추정)

`hbase-create.hbase` 는 `TraceV2` 를 `NUMREGIONS => 256, SPLITALGO => UniformSplit` 으로
만들고, 16 스레드(`pool22-t1`~`t16`)가 동시에 생성한다. 프로덕션 클러스터 기준값이라
단일 노드에는 과하다. **운영 x86 에서는 256 을 그대로 쓰는 것이 맞다.**

### 5. `sleep 15` 후 무작정 create (가장 헷갈리는 실패)

`check-table.sh` 가 master 준비를 확인하지 않는다.

```bash
else
    sleep 15                       # ← 무작정 15초
    ${HBASE_HOME}/bin/hbase shell ${BASE_DIR}/hbase-create.hbase
fi
```

master 초기화가 15 초를 넘기면 모든 create 가
`PleaseHoldException: Master is initializing` 으로 튕긴다. 컨테이너는 Up 이고 에러도
stdout 에 잠깐 스치므로 **"조용히 아무것도 안 만들어진" 상태**가 된다.

이것이 시도별 결과가 8→5→0 개로 오락가락한 이유다. 원인이 각각 달랐던 게 아니라,
설정을 바꿀 때마다 master 초기화 시간이 달라져 `sleep 15` 안에 몇 개가 통과하느냐가
바뀐 것이다. **하나의 타이밍 문제를 세 개의 다른 원인으로 오독했다.**

### 5-1. 준비 확인을 `list` 로 하면 안 된다

첫 래퍼는 `list` 성공을 준비 신호로 봤다가 `READY after 1 checks` 직후 create 4 건이
전부 실패했다. **`list`(읽기)는 master 초기화 도중에도 성공하지만 `create`(DDL)는 거부된다** —
master 가 단계적으로 초기화돼 읽기 경로가 먼저 열리기 때문이다.

→ **준비 확인은 실제로 하려는 작업(DDL)으로 한다.** 임시 테이블을 만들어보고 지운다.
구현: `pinpoint/hbase-init.sh` (커밋 `4ee634a` — 워킹 트리에서는 제거됨)

일반화하면 — **읽기가 된다고 쓰기가 되는 게 아니다.**

### 6. 타임아웃을 올리는 게 항상 안전하지 않다

ZK 타임아웃을 올리며 `hbase.master.wait.on.regionservers.timeout` 도 180000 으로 올렸더니
**master 활성화가 3 분 지연됐다.**

```
Waiting on regionserver count=1; waited=119221ms,
  expecting min=1 server(s), timeout=180000ms
```

`count=1 ≥ min=1` 로 조건이 충족됐는데도 타임아웃까지 기다린다.
이 설정은 "실패 판정 상한" 이 아니라 **"리전서버가 더 올라오길 기다리는 시간"** 이다.

**타임아웃에는 두 종류가 있다.**
- **상한형** — 실패 판정까지의 최대 대기(`zookeeper.session.timeout`). 올리면 안전
- **대기형** — 조건 충족과 무관하게 소진. 올리면 그만큼 느려진다

단일 노드에서는 대기형을 짧게 잡는다(`15000`).

## 멈춤인지 느림인지 구분하는 법

이 조사에서 가장 많이 오판한 지점이다. 판별법:

| 확인 | 멈춤 판정 |
|---|---|
| master 로그 마지막 시각 vs **컨테이너 내부 시각** | 수 분 이상 벌어지면 정지 |
| 최근 로그가 하트비트뿐인가 (`LruBlockCacheStatsExecutor` 는 5 분마다 찍힘) | 작업 로그가 없으면 정지 |
| 리전 생성 카운트가 30 초간 증가하는가 | 증가 0 이면 정지 |
| CPU | 2~3% 는 "일하는 중" 이 아니라 "노는 중" |

컨테이너는 UTC 이므로 `docker exec <c> date` 로 **내부 시각**과 비교해야 한다.
호스트 로컬 시각과 비교하면 시차만큼 착시가 생긴다.

## 남은 선택지

1. **HBase 이미지를 arm64 로 직접 빌드** — `docker buildx` + aarch64 JDK.
   가능성은 있으나 이미지 유지보수를 떠안는다. Hadoop 네이티브 라이브러리가 걸릴 수 있다
   (`NativeCodeLoader` 경고가 이미 관측됨)
2. **백엔드만 x86 원격/클라우드에** — 에이전트는 앱 JVM(arm64 네이티브)에서 도니 무관.
   작성해둔 독립 스택 + external network 구조가 그대로 재사용된다
3. **OpenTelemetry 스택으로 전환** — [otel-vs-pinpoint.md](otel-vs-pinpoint.md) 참조

## 산출물 — git 히스토리에 보존

작업물은 워킹 트리에서 제거했지만 **커밋 `4ee634a` 에 그대로 남아 있다.**
위 함정 1·2·3·5·6 을 모두 반영한 상태이므로, **x86 환경에서 Pinpoint 를 띄운다면
그대로 되살려 쓸 수 있다.**

```sh
git show 4ee634a --stat                      # 무엇이 있었는지
git checkout 4ee634a -- pinpoint/ docker-compose-pinpoint.yml   # 되살리기
```

- `pinpoint/docker-compose.yml` — 독립 백엔드 스택(6 컨테이너)
- `pinpoint/hbase-init.sh` — DDL 프로브 기반 초기화 래퍼
- `docker-compose-pinpoint.yml` — 에이전트 부착 override

템플릿 오염을 피하려고 워킹 트리에서는 뺐다. arm64 에서 뜨지 않는 스택을
기본 산출물로 두면 이 템플릿을 쓰는 사람이 혼란스럽다.

## 출처

- <https://hub.docker.com/r/pinpointdocker/pinpoint-hbase/tags> — arm64 부재 확인
- <https://deepwiki.com/pinpoint-apm/pinpoint> — 이중 저장소 구조
- <https://github.com/pinpoint-apm/pinpoint/issues/4872> — 다른 저장소 지원 요청(미수용)
- <https://github.com/pinpoint-apm/pinpoint/issues/284> — Cassandra 대체 요청(미수용)
- <https://docs.docker.com/desktop/troubleshoot-and-support/troubleshoot/known-issues/> — QEMU best effort
