# research

결정 **이전**의 조사 기록. 무엇을 알아봤고, 무엇이 사실로 확인됐고,
무엇이 추정으로 남았는지를 남긴다.

ADR 과 역할이 다르다.

| | research | ADR |
|---|---|---|
| 시점 | 결정 **전** | 결정 **시점** |
| 내용 | 조사·측정·실패 기록 | 결정과 기각 사유 |
| 생명주기 | 갱신됨(사실이 바뀌면 고친다) | 불변(바뀌면 새 번호) |

여기 있는 문서가 ADR 의 "검토한 대안과 기각 사유" 항목의 **근거 자료**가 된다.

## 표기 규칙

주장마다 근거 등급을 구분한다. 이게 이 폴더의 존재 이유다.

- **[실측]** — 직접 실행해서 확인함. 명령어·출력값을 함께 남긴다
- **[문서]** — 공식 문서·릴리스 노트에 명시됨. 출처 URL 필수
- **[추정]** — 정황상 그럴 것으로 보이나 **확인 안 됨**. 반드시 이렇게 표기한다

## 목록

| 문서 | 내용 | 상태 |
|---|---|---|
| [pinpoint-agent-compat.md](pinpoint-agent-compat.md) | Pinpoint 3.1.0 에이전트가 이 스택(Boot 4.1 / Hibernate 7.4.5 / Java 25 / Connector-J 9.7.0)을 계측하는가 — 에이전트 tarball 정적 분석 | 정적 검증 완료, 실행 검증 미완 |
| [pinpoint-hbase-arm64.md](pinpoint-hbase-arm64.md) | Pinpoint 백엔드를 Apple Silicon 에서 띄우려다 실패한 기록. HBase 초기화 함정 6 개 | 미해결(환경 제약) |
| [otel-vs-pinpoint.md](otel-vs-pinpoint.md) | OpenTelemetry + Grafana 스택이 Pinpoint 를 대체 가능한가 — 기능 대조와 트레이드오프 | 조사 완료 |
| [sampled-sql-bind-capture.md](sampled-sql-bind-capture.md) | 샘플링된 요청만 SQL 바인드 값을 캡처하는 방법 — 손실 1건의 후속 조사 | 조사 완료, 스파이크 필요 |
| [always-on-thread-visibility.md](always-on-thread-visibility.md) | Pinpoint 액티브 스레드 뷰 대체 — 상시 스레드 가시성과 사후 회고. 손실 1건의 후속 조사 | 조사 완료 |
| [otel-phase0-verification.md](otel-phase0-verification.md) | OTel 에이전트가 이 스택을 실제로 계측하는가 — 백엔드 0개 실행 검증 | **통과** (2026-08-17) |
| [otel-measurements.md](otel-measurements.md) | 도입 효과 before/after 측정 — N+1 방어 검증(30회→1회), 에이전트 오버헤드, JFR | 측정 완료 |
