# PRD-002 — Sakila 엔티티

**상태**: 완료

## 무엇을 왜

Sakila 16개 테이블을 JPA 로 매핑해 스키마 커버리지를 확보한다.
`rental` 을 뺀 15개는 **얇게** 만든다 — 도메인 모델·Store·매퍼 없이
`@Entity` + `JpaRepository` 만. 새 프로젝트에서 통째로 지울 예제이기 때문이다.

## 범위

**포함**
- 엔티티 15개 — film · actor · category · language · country · city · address ·
  customer · staff · store · inventory · payment · film_actor · film_category · film_text
- 리포지토리 15개 (파일 분리)
- MySQL 고유 타입 컨버터 — `enum('G','PG','PG-13',...)`, `set(...)`

**제외**
- 도메인 모델·Store (참조 구현은 `rental` 만)
- 컨트롤러·서비스 (엔티티와 리포지토리까지만)

## 완료 판정

| # | 판정 | 검증 방법 | 결과 |
|---|---|---|---|
| 1 | 16개 엔티티가 실제 스키마와 일치한다 | `ddl-auto=validate` 로 기동 | ✅ 기동 성공 |
| 2 | 복합키 테이블이 매핑된다 | `film_actor`·`film_category` `@EmbeddedId` + `@MapsId` | ✅ |
| 3 | `rating` enum 이 하이픈 값을 처리한다 | `PG-13`·`NC-17` 매핑 | ✅ AttributeConverter |
| 4 | 연관관계가 전부 LAZY 다 | 엔티티 코드 검토 | ✅ |
| 5 | 리포지토리 빈이 등록된다 | 기동 시 의존성 주입 성공 | ✅ |
| 6 | FULLTEXT 네이티브 쿼리가 동작한다 | `FilmTextRepository.searchFullText` | ✅ |

## 발견한 문제

`ddl-auto=validate` 가 **안전망 역할을 정확히 수행했다.** 손으로 만든 매핑에서 버그 2건을
기동 시점에 잡았다.

| 문제 | 원인 | 조치 |
|---|---|---|
| `wrong column type ... [length]; found [smallint unsigned], but expecting [integer]` | `film.length` 를 `Integer` 로 매핑 | `Short` 로 변경 |
| `wrong column type ... [rental_duration]; found [tinyint unsigned], but expecting [smallint]` | `film.rental_duration` 을 `Short` 로 매핑 | `Byte` 로 변경 |

전 테이블의 `tinyint`/`smallint` 컬럼을 선제 조사해 나머지(`customer.active`,
`staff.active` = `tinyint(1)`)는 `Boolean` 매핑이 맞음을 확인했다.

**부수적으로 발견한 것** — 중첩 인터페이스로 선언한 Spring Data 리포지토리는
빈이 생성되지 않는다. 최상위 타입으로 분리해야 한다.
