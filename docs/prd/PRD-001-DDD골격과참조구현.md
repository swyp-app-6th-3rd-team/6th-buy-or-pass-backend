# PRD-001 — DDD 골격과 참조 구현

**상태**: 완료

## 무엇을 왜

DDD 를 문서가 아니라 **돌아가는 코드**로 시연한다. 다만 Sakila 16개 테이블을 전부
풀 DDD 로 만들면 템플릿이 아니라 Sakila 애플리케이션이 되므로
`rental` 애그리거트 하나만 참조 구현으로 삼는다([ADR-0001](../adr/0001-skeleton-plus-one-reference.md)).

`rental` 을 고른 이유는 **상태 전이가 실재하기 때문**이다 — 대여 → 반납은 되돌릴 수 없고,
이미 반납된 건을 다시 반납하면 안 된다. 이런 규칙이 있어야 도메인 모델이 왜 필요한지 설명된다.

## 범위

**포함**
- 공통 자산 — `ApiResponse` · `ResponseCode` · `ApiException` · `GlobalExceptionHandler` ·
  `CorrelationIdFilter` · `ClockConfig`
- `rental` 애그리거트 풀 DDD — 순수 도메인 · JPA 엔티티 · Store 인터페이스/구현 ·
  QueryDSL 동적 조회 · 컨트롤러
- 페이징 — `Page`(번호) · `Window`(무한 스크롤) · 커서 인코딩

**제외**
- 애그리거트 간 관계 (애그리거트가 하나뿐)
- Sakila 나머지 15개 (PRD-002)

## 완료 판정

| # | 판정 | 검증 방법 | 결과 |
|---|---|---|---|
| 1 | 도메인이 프레임워크에 의존하지 않는다 | ArchUnit — JPA·Lombok·Spring·infra 의존 금지 | ✅ 6규칙 통과 |
| 2 | 유효하지 않은 Rental 을 만들 수 없다 | 단위 테스트 — 음수 ID·null 대여일 거부 | ✅ |
| 3 | 이미 반납된 건을 다시 반납할 수 없다 | 단위 테스트 + API 통합 테스트 | ✅ 409 응답 |
| 4 | 저장된 상태를 그대로 복원한다 | `Rental.restore(...)` 로 반납 상태 복원 | ✅ |
| 5 | QueryDSL 동적 조건이 조합별로 정확하다 | 통합 테스트 — 고객·반납여부·기간·복합 | ✅ 6건 |
| 6 | 정렬 허용 목록 밖 필드는 무시된다 | `?sort=nonexistentColumn` 으로 조회 | ✅ 기본 정렬로 동작 |
| 7 | 무한 스크롤이 중복·누락 없이 전체를 훑는다 | 커서를 따라 끝까지 조회 후 집합 크기 확인 | ✅ 5/5 |
| 8 | 대여일이 같아도 누락되지 않는다 | 같은 시각 5건으로 스크롤 | ✅ 5/5 |
| 9 | 응답에 Spring Page 내부 구조가 새지 않는다 | `$.returnObject.pageable` 부재 확인 | ✅ |
| 10 | 조작된 커서는 400 이다 | `?cursor=!!!not-base64!!!` | ✅ INVALID_REQUEST |

## 열린 질문 → 해소됨

- **자체 페이징 래퍼를 쓸 것인가?** → 아니오. Spring Data 타입 직접 사용.
  [ADR-0004](../adr/0004-spring-data-paging-types.md)

## 발견한 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| 스크롤 2페이지에서 500 | 커서가 JSON 왕복 후 `LocalDateTime` → `String` 인데 캐스팅함 | 양쪽 타입을 모두 받도록 변환 헬퍼 추가 |
| 스크롤에서 3건 중 2건만 조회 | `datetime(0)` vs 나노초 정밀도 불일치로 커서 조건이 행을 걸러냄 | `Clock` 을 초 단위로 끊음. [ADR-0003](../adr/0003-localdatetime-over-instant.md) |
