# 관측성 스택 홈서버 배포 매뉴얼

대상: 단일 홈서버(Apple Silicon / arm64 검증됨). 여러 Spring Boot 앱이 이 스택 하나를 공유한다.

> **이 스택은 앱과 수명주기가 분리돼 있다.** 한 번 띄우고 계속 둔다.
> 앱을 재배포해도 백엔드는 그대로이므로 다른 앱의 트레이스가 끊기지 않는다.

---

## 1. 사전 준비

### 자원 요구량

| 컴포넌트 | 메모리(권장 상한) | 비고 |
|---|---|---|
| Grafana | 512MB | |
| Prometheus | 1GB | 보존 기간·타겟 수에 비례 |
| Loki | 1GB | 로그량에 비례 |
| Tempo | 1GB | 트레이스량 + metrics-generator |
| OTel Collector | 512MB | 배치 버퍼 |
| **합계** | **약 4GB** | 앱·DB 제외 |

호스트에 **최소 8GB** 를 권장한다(앱 + MySQL + 여유 포함).

### 디스크

볼륨 4 개가 생성된다. 기본 보존 기간 기준 대략:

| 볼륨 | 보존 | 예상 |
|---|---|---|
| `observability-prometheus-data` | 15d | 수백 MB ~ 수 GB |
| `observability-loki-data` | 7d | 로그량에 직접 비례 |
| `observability-tempo-data` | 7d | 트레이스량에 직접 비례 |
| `observability-grafana-data` | — | 수십 MB (대시보드·설정) |

**보존 기간은 §5 에서 조정한다.** 홈서버 디스크가 작으면 먼저 줄여두는 편이 낫다.

### 포트

| 포트 | 용도 | 외부 노출 |
|---|---|---|
| 3000 | Grafana UI | **필요** (사람이 본다) |
| 4317 / 4318 | OTLP 수신 (gRPC / HTTP) | 호스트에서 앱을 직접 실행할 때만 |
| — | Prometheus / Loki / Tempo | **노출하지 않는다** (네트워크 내부 통신) |

Prometheus·Loki·Tempo 는 호스트 포트를 열지 않는다. Grafana 를 통해서만 접근한다.
디버깅이 필요하면 §7 의 임시 접근 방법을 쓴다.

---

## 2. 배포

```bash
# 1) 저장소를 홈서버로 가져온다 (observability/ 만 있으면 된다)
git clone <repo> && cd sakila-ddd-template

# 2) 앱과 공유할 네트워크를 만든다 (최초 1회)
docker network create observability-net

# 3) 운영값을 설정한다  ← 반드시 §3 을 먼저 읽을 것
cp .env.example .env
vi .env

# 4) 기동
docker compose -f observability/docker-compose.yml up -d
```

### 기동 확인

```bash
docker compose -f observability/docker-compose.yml ps
```

**Tempo 와 Loki 는 처음 수십 초간 503 을 반환한다.** 초기화 중이라 정상이다.
준비 완료는 다음으로 확인한다.

```bash
docker run --rm --network observability-net curlimages/curl:latest \
  sh -c 'for u in http://prometheus:9090/-/healthy http://tempo:3200/ready http://loki:3100/ready; do
           printf "%-34s " "$u"; curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 "$u"; done'
```

셋 다 `200` 이면 완료다. Grafana 는 http://<홈서버>:3000 으로 접속한다.

---

## 3. 운영 하드닝 (배포 전 필수)

기본값은 **로컬 검증용**이다. 홈서버에 올리기 전에 아래를 반드시 바꾼다.

### 3-1. Grafana 인증

`.env` 에서:

```bash
# 익명 접근을 끈다. 기본값 true 는 검증 편의용이다.
GRAFANA_ANONYMOUS=false

# 기본 비밀번호를 바꾼다.
GRAFANA_USER=admin
GRAFANA_PASSWORD=<충분히 긴 값>   # openssl rand -base64 24
```

> **외부에서 접근한다면 `GRAFANA_ANONYMOUS=false` 가 아니라 아예 노출하지 않는 편이 낫다.**
> Tailscale·WireGuard 같은 사설망이나 리버스 프록시 + 인증을 앞에 두는 것을 권한다.
> Grafana 를 인터넷에 직접 여는 것은 권장하지 않는다.

### 3-2. 보존 기간과 디스크

디스크가 찰 때까지 방치하면 로그·트레이스 수집이 조용히 멈춘다. 미리 정한다.

| 파일 | 항목 | 기본 |
|---|---|---|
| `.env` | `PROM_RETENTION` | `15d` |
| `observability/loki-config.yaml` | `limits_config.retention_period` | `168h` (7일) |
| `observability/tempo-config.yaml` | `compactor.compaction.block_retention` | `168h` (7일) |

> **주의**: Loki 와 Tempo 설정 파일은 `${VAR}` 를 확장하지 않는다.
> compose 가 아니라 각 프로세스가 직접 읽기 때문이다. **파일을 직접 고쳐야 한다.**

### 3-3. 메모리 상한

컨테이너 하나가 폭주해 호스트를 마비시키는 것을 막는다.
`observability/docker-compose.yml` 의 각 서비스에 이미 `deploy.resources.limits` 가 있다.
호스트 사양에 맞춰 조정한다.

### 3-4. 방화벽

```bash
# 예시 (ufw). Grafana 만 열고 나머지는 막는다.
sudo ufw allow 3000/tcp     # Grafana
# 4317/4318 은 앱이 같은 호스트의 컨테이너면 열 필요가 없다
```

---

## 4. 앱 서버 연결

### 4-1. 같은 호스트의 컨테이너 앱 (기본)

앱이 `observability-net` 에 참여하면 서비스명으로 통신한다. 포트 노출이 불필요하다.

```bash
docker compose -f docker-compose-prod.yml -f docker-compose-otel.yml --profile app up -d --build
```

> **`--build` 를 빠뜨리지 말 것.** compose 는 이미지가 있으면 build arg 가 바뀌어도
> 재빌드하지 않는다. 그러면 `-javaagent` 는 주입되는데 이미지에 에이전트가 없어
> 진입점 가드가 경고를 남기고 **에이전트 없이** 뜬다(앱은 죽지 않는다).

### 4-2. 앱을 추가할 때

**두 곳**을 고쳐야 한다.

**(1) Prometheus scrape 타겟** — `observability/prometheus-config.yaml`

```yaml
  - job_name: spring-boot-apps
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - "sakila-app:9090"
        labels:
          application: sakila-ddd-template
      - targets:                      # ← 추가
          - "another-app:9090"
        labels:
          application: another-app
```

반영: `docker compose -f observability/docker-compose.yml restart prometheus`

**(2) 새 앱의 compose** — `docker-compose-otel.yml` 을 복사해 값만 바꾼다.

| 변수 | 규칙 |
|---|---|
| `OTEL_SERVICE_NAME` | **앱마다 고유.** ServerMap 의 노드 이름이 된다 |
| `service.instance.id` | **인스턴스마다 고유.** 같은 앱을 2대 띄우면 여기만 다르다 |
| `OTEL_ENDPOINT` | `http://otel-collector:4318` (동일) |

### 4-3. 호스트에서 직접 실행하는 앱 (IDE 등)

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
java -javaagent:opentelemetry-javaagent.jar -jar app.jar
```

이때는 Prometheus 가 앱을 못 긁는다(컨테이너명이 없다). 메트릭이 필요하면
`prometheus-config.yaml` 에 `host.docker.internal:9090` 을 타겟으로 추가한다.

### 4-4. 연결 확인

```bash
# 타겟이 up 인가
docker run --rm --network observability-net curlimages/curl:latest \
  -s "http://prometheus:9090/api/v1/targets?state=active" | grep -o '"health":"[a-z]*"'

# 트레이스가 도착하는가
docker run --rm --network observability-net curlimages/curl:latest \
  -s "http://tempo:3200/api/search?limit=5"
```

Grafana 에서는 Explore → Tempo → Search 로 확인한다.

---

## 5. 업그레이드

이미지 태그는 **전부 고정돼 있다**(`grafana/tempo:2.9.0` 등). `latest` 를 쓰지 않는다 —
어느 날 갑자기 설정 호환성이 깨지는 것을 막기 위해서다.

```bash
# 1) 백업 먼저 (§6)
# 2) docker-compose.yml 의 태그를 하나씩 올린다
# 3) 한 번에 하나씩 재기동해 영향 범위를 좁힌다
docker compose -f observability/docker-compose.yml up -d tempo
docker compose -f observability/docker-compose.yml logs -f tempo
```

**Loki·Tempo 는 스키마 변경이 있는 메이저 업그레이드에서 설정 호환성이 깨지기 쉽다.**
릴리스 노트를 먼저 읽는다. Loki 는 `schema_config` 를 추가하는 방식이라
기존 항목을 지우면 과거 데이터를 못 읽는다.

### 롤백

```bash
docker compose -f observability/docker-compose.yml down
# 태그를 되돌린 뒤
docker compose -f observability/docker-compose.yml up -d
```

볼륨은 유지되므로 데이터는 남는다. 단 **상위 버전이 볼륨 포맷을 바꿨다면 롤백이
실패할 수 있다** — 그래서 업그레이드 전 백업이 필요하다.

---

## 6. 백업과 복구

### 6-1. 무엇을 백업하는가

| 대상 | 중요도 | 이유 |
|---|---|---|
| **Grafana 볼륨** | **높음** | 대시보드·사용자·설정. 잃으면 다시 만들어야 한다 |
| `observability/` 설정 파일 | **높음** | git 에 있으므로 사실상 백업됨 |
| Prometheus / Loki / Tempo 볼륨 | 낮음 | 시계열·로그·트레이스. 잃어도 앞으로의 관측에는 지장 없다 |

**핵심은 Grafana 다.** 나머지는 보존 기간이 지나면 어차피 사라지는 데이터다.

### 6-2. 백업

```bash
BACKUP_DIR=~/backup/observability/$(date +%Y%m%d)
mkdir -p "$BACKUP_DIR"

# Grafana 볼륨 (대시보드·설정)
docker run --rm \
  -v observability-grafana-data:/data:ro \
  -v "$BACKUP_DIR":/backup \
  alpine tar czf /backup/grafana-data.tar.gz -C /data .

# 대시보드를 JSON 으로도 뽑아둔다 (버전 간 이식이 쉽다)
curl -s -u admin:$GRAFANA_PASSWORD \
  "http://localhost:3000/api/search?type=dash-db" \
  | python3 -c "import sys,json;[print(d['uid']) for d in json.load(sys.stdin)]" \
  | while read uid; do
      curl -s -u admin:$GRAFANA_PASSWORD \
        "http://localhost:3000/api/dashboards/uid/$uid" \
        > "$BACKUP_DIR/dashboard-$uid.json"
    done
```

> **provisioning 으로 등록한 대시보드는 백업이 필요 없다.**
> `observability/grafana/dashboards/*.json` 이 git 에 있고 기동 시 자동 로드된다.
> 백업 대상은 **UI 에서 손으로 만든 것**뿐이다.

### 6-3. 복구

```bash
docker compose -f observability/docker-compose.yml down

docker volume create observability-grafana-data
docker run --rm \
  -v observability-grafana-data:/data \
  -v "$BACKUP_DIR":/backup \
  alpine sh -c "cd /data && tar xzf /backup/grafana-data.tar.gz"

docker compose -f observability/docker-compose.yml up -d
```

### 6-4. 홈서버 재설치 시

1. `docker network create observability-net`
2. 저장소를 clone 하고 `.env` 를 복원한다
3. Grafana 볼륨을 복구한다(§6-3)
4. `docker compose -f observability/docker-compose.yml up -d`

시계열·로그·트레이스는 복구하지 않아도 된다. 새로 쌓인다.

---

## 7. 장애 대응

### 확인 순서

```bash
# 1) 컨테이너 상태
docker compose -f observability/docker-compose.yml ps

# 2) 재시작 루프인지
docker inspect <container> --format '{{.RestartCount}}'

# 3) 로그
docker compose -f observability/docker-compose.yml logs --tail 50 <service>
```

### 자주 겪는 것

| 증상 | 원인 | 조치 |
|---|---|---|
| Tempo·Loki 가 `Restarting` | 설정 파일 문법 오류 | `docker logs` 첫 줄에 파싱 에러가 그대로 나온다 |
| 설정의 `${VAR}` 가 그대로 문자열로 | **Loki·Tempo 설정은 환경변수를 확장하지 않는다** | 파일에 실제 값을 직접 쓴다 |
| Grafana 는 뜨는데 데이터가 없음 | datasource 는 등록됐으나 앱이 안 보냄 | §4-4 로 타겟·트레이스 도달을 확인 |
| Prometheus 타겟이 `down` | 앱 관리 포트 미도달 | 앱이 `observability-net` 에 있는지, `MANAGEMENT_PORT` 가 맞는지 |
| 트레이스는 오는데 service graph 가 비어 있음 | metrics-generator 가 Prometheus 에 못 씀 | Prometheus 의 `--web.enable-remote-write-receiver` 확인 |
| 디스크 가득 참 | 보존 기간 과다 | §3-2 로 줄인 뒤 `docker volume` 정리 |

### 내부 서비스에 임시 접근

Prometheus·Loki·Tempo 는 호스트 포트를 열지 않는다. 디버깅이 필요하면:

```bash
# 임시 포트포워딩 (조사 후 반드시 종료)
docker run --rm -d --name tmp-fwd --network observability-net \
  -p 9090:9090 alpine/socat \
  TCP-LISTEN:9090,fork TCP:prometheus:9090
# 조사 끝나면
docker rm -f tmp-fwd
```

---

## 8. 스택을 내릴 때

```bash
# 컨테이너만 (데이터 유지)
docker compose -f observability/docker-compose.yml down

# 데이터까지 삭제 — 되돌릴 수 없다
docker compose -f observability/docker-compose.yml down -v
docker network rm observability-net
```

앱은 백엔드가 없어도 정상 동작한다. OTel 에이전트는 전송 실패를 로그로 남길 뿐
요청 처리를 막지 않는다.
