# ADR-0004 — 페이징은 Spring Data 타입을 그대로 쓰고 응답 경계에서만 변환한다

**상태**: Accepted

## 맥락

처음에는 `PageQuery`/`PageResult` 라는 자체 record 를 만들어
Spring Data 타입이 서비스·도메인 계층으로 새는 것을 막았다.

그런데 이 템플릿에서는 대가가 이득보다 컸다.

- 래퍼는 Spring Data 가 이미 주는 것을 **재구현하거나 잃는다** — 정렬(`Sort`),
  count 쿼리 생략 최적화(`PageableExecutionUtils`), keyset 페이징(`Window`).
- 특히 **무한 스크롤**을 직접 만들면 커서 인코딩·동률 처리·양방향 스크롤을 전부 손으로 짜야 한다.
  Spring Data 3.1+ 의 `Window`/`ScrollPosition` 이 이미 해결한 문제다.
- 템플릿 사용자 입장에서 표준 타입이 학습 비용이 낮다.

## 결정

**계층 내부에서는 Spring Data 타입을 그대로 쓴다.**

| 용도 | 입력 | 출력 |
|---|---|---|
| 번호 페이징 | `Pageable` (`PageRequest`) | `Page<T>` |
| 무한 스크롤 | `ScrollPosition` | `Window<T>` |

도메인의 `RentalStore` 인터페이스도 이 타입들을 직접 받는다.

**단, 응답 경계에서는 변환한다.** 컨트롤러는 `Page`/`Window` 를 그대로 내보내지 않고
`PageResponse`/`ScrollResponse` DTO 로 바꾼다.

이유는 **직렬화 계약** 때문이다. `Page` 를 그대로 JSON 으로 내보내면
`pageable.sort.sorted`, `pageable.offset` 같은 **Spring 내부 구조가 API 계약이 되어버린다.**
Spring 이 그 구조를 바꾸면 클라이언트가 깨진다. Spring Boot 3.3+ 는 `Page` 직렬화 시
경고를 낸다.

ArchUnit 규칙 2개가 이 경계를 강제한다 — 컨트롤러의 public 메서드는
`Page` 도 `Window` 도 반환할 수 없다.

## 결과

**얻은 것**
- `Window` 로 keyset 페이징을 얻었다. offset 을 쓰지 않으므로 뒤쪽 조각에서도 성능이 일정하다.
  Sakila rental 이 16,044건이라 실제로 차이가 난다.
- `PageableExecutionUtils.getPage(...)` 로 count 쿼리를 조건부로 생략한다.
- `@PageableDefault` 로 컨트롤러 파라미터 바인딩이 자동으로 된다.

**포기한 것**
- 도메인·서비스가 `org.springframework.data` 에 의존한다. 완전한 순수성을 잃었다.
  Spring Data 를 걷어내는 날이 오면 이 계층들을 고쳐야 한다.
  **의도적으로 감수한 결합**이며, 그 대가로 프레임워크가 이미 푼 문제를 다시 풀지 않는다.
- 도메인 순수성 규칙에서 Spring Data 만 예외가 된다. ArchUnit 규칙이 다소 덜 깔끔해졌다.

**커서 관련 함정** (실측으로 발견)
- 커서는 JSON 을 거치므로 **타입이 보존되지 않는다.** `LocalDateTime` 이 문자열로 돌아온다.
  `RentalQueryRepositoryImpl` 이 양쪽을 모두 받도록 되어 있다.
- 정렬 키가 `rentalDate` 하나면 동률 구간에서 행이 누락된다.
  `(rentalDate, id)` 로 전체 순서를 유일하게 만들어야 한다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| `PageQuery`/`PageResult` 자체 래퍼 | 도메인 순수성은 얻지만 `Window`·count 최적화·`Sort` 를 직접 만들어야 한다. 무한 스크롤 요구가 있어 비용이 특히 컸다 |
| `Page` 를 응답까지 그대로 노출 | 가장 간단하지만 Spring 내부 구조가 API 계약이 된다. 프레임워크 업그레이드가 클라이언트를 깨뜨린다 |
| offset 페이징만 제공 | 16,044건에서 뒤쪽 페이지가 느려진다. 무한 스크롤 UI 에 부적합 |
