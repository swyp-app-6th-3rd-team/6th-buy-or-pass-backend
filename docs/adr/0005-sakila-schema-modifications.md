# ADR-0005 — Sakila 원본 스키마를 손봐서 마이그레이션한다

**상태**: Accepted

## 맥락

Sakila 는 2006년 MySQL AB 가 만든 샘플 DB 다. 원본 스크립트를 Flyway 마이그레이션으로
그대로 넣으면 **여러 곳에서 깨진다.** 실제로 적용해보며 확인한 것들:

| 문제 | 증상 |
|---|---|
| `DROP SCHEMA` / `CREATE SCHEMA` / `USE` | Flyway 는 이미 연결된 DB 에 적용한다. 스키마를 갈아엎으면 `flyway_schema_history` 까지 날아간다 |
| `DELIMITER` (10곳) | MySQL 클라이언트 명령어지 SQL 이 아니다. JDBC 로 보내면 문법 오류 |
| 순환 FK (`staff` ↔ `store`) | 알파벳 순으로 정렬해도 해결 불가. `ERROR 1824: Failed to open the referenced table` |
| `CHARSET=utf8` (16개 전부) | `utf8` 은 `utf8mb3` 별칭. MySQL 8.4 에서 deprecated 이고 3바이트 한계라 이모지를 저장하지 못한다 |
| `film_text` 가 MyISAM | 2006년엔 InnoDB 가 FULLTEXT 를 지원하지 않아서다. MyISAM 은 트랜잭션·FK 를 지원하지 않아 JPA 와 섞으면 롤백이 되지 않는다 |
| 시드 데이터의 teardown 블록 | 시작 시 FK 를 `ALTER TABLE` 로 DROP 하고 끝에서 다시 ADD 한다. 중간에 실패하면 제약이 사라진 채 남는다 |

## 결정

원본을 보존하지 않고 **명시적으로 손봐서** 마이그레이션한다. 무엇을 왜 바꿨는지는
각 SQL 파일 상단 주석에 남긴다.

| 변경 | 이유 |
|---|---|
| 스키마 레벨 구문 제거 | Flyway 가 연결·스키마를 관리한다 |
| 트리거 3 · 뷰 7 · 프로시저/함수 6 제외 | 템플릿 범위 밖. 특히 `film_text` 동기화 트리거는 JPA 쓰기에 예상치 못한 부작용을 만든다 |
| `SET FOREIGN_KEY_CHECKS = 0 → 1` 로 감쌈 | 순환 FK 해결. **세션 범위**라 스키마를 변형하지 않는다 |
| `utf8` → `utf8mb4` | 이모지·4바이트 문자 저장 |
| `film_text`: MyISAM → InnoDB | MySQL 5.6부터 InnoDB 도 FULLTEXT 를 지원한다. 트랜잭션·FK 도 얻는다 |
| 시드의 `ALTER TABLE` teardown → `FOREIGN_KEY_CHECKS` | 같은 목적을 스키마 변형 없이 달성 |
| 시드 끝에 `film_text` 백필 추가 | 트리거를 제외했으므로 직접 채운다 |
| 시드를 `db/seed` 로 분리 | 9MB 를 매번 넣으면 TestContainers 기동이 견딜 수 없이 느려진다. `local` 프로파일에서만 로드 |

## 결과

**검증한 것** (MySQL 8.4 컨테이너에 실제 적용)

| 항목 | 결과 |
|---|---|
| 테이블 | 18개 (Sakila 16 + 인증 2) |
| 엔진·인코딩 | 전부 InnoDB + `utf8mb4_0900_ai_ci` |
| FK 제약 | 23개 유효 (순환 `staff`↔`store` 포함) |
| FULLTEXT | `film_text.idx_title_description` 동작 확인 (`MATCH ... AGAINST`) |
| 행 수 | actor 200 · film 1000 · customer 599 · rental 16044 · payment 16049 — Sakila 공식값과 일치 |
| 고아 행 | 0건 |
| 시드 적재 시간 | 90초 |

**포기한 것**
- 원본 Sakila 와 100% 동일하지 않다. 뷰·프로시저를 쓰는 기존 Sakila 예제 코드는 동작하지 않는다.
- `film_text` 는 트리거가 없으므로 `film` 을 수정해도 자동 동기화되지 않는다.
  전문검색을 쓰려면 애플리케이션이 동기화 책임을 지거나 트리거를 직접 추가해야 한다.
  이 사실을 엔티티 주석과 시드 파일에 명시했다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 원본을 그대로 Flyway 에 넣는다 | 첫 줄부터 실패한다. 실제로 확인함 |
| docker-compose `initdb.d` 로 초기화 | 간단하지만 스키마 변경 이력이 남지 않고 TestContainers 에 별도 설정이 필요하다 |
| 뷰·프로시저까지 전부 이식 | Flyway 가 `DELIMITER` 를 처리하도록 분리 작성해야 하고, 템플릿에 쓰이지 않는 자산이다 |
| `ddl-auto=update` 로 스키마 자동 생성 | 기존 Sakila 스키마와 충돌하고 운영에 부적합하다 |
