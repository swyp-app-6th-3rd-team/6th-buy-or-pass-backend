# ADR-0002 — QueryDSL 은 OpenFeign 포크를 쓴다

**상태**: Accepted

## 맥락

요구사항은 Spring Boot 4 와 QueryDSL 을 함께 쓰라고 했다. 이 조합이 이 프로젝트에서
**가장 위험한 가정**이었다 — 실패하면 나머지 7개 요구사항을 아무리 잘 만들어도
빌드 첫 단계에서 무너지기 때문이다.

측정한 사실:

| 항목 | 값 | 확인 방법 |
|---|---|---|
| Spring Boot | 4.1.0 | 최신 stable, Java 25 first-class 지원 |
| Hibernate | 7.4.5.Final | Boot 4.1 BOM 관리 버전 |
| 본가 QueryDSL(`com.querydsl`) | 5.1.0 에서 정체 | Hibernate 6 전제, Jakarta EE 11 미지원 |

인과가 닫힌다:

```
Boot 4.1 → Hibernate 7.4.5 → 본가 QueryDSL 5.1.0(Hibernate 6 전제)은 부적합
```

널리 쓰이는 Boot 3 예제는 `com.querydsl:querydsl-jpa:5.1.0:jakarta` 를 쓴다.
**그대로 복사하면 깨진다.**

## 결정

**OpenFeign 포크(`io.github.openfeign.querydsl`) 7.5** 를 쓴다.
본가가 정체된 동안 Hibernate 7 지원 라인을 유지하는 유일한 현실적 경로다.

```gradle
ext { querydslVersion = '7.5' }

implementation "io.github.openfeign.querydsl:querydsl-jpa:${querydslVersion}"
annotationProcessor "io.github.openfeign.querydsl:querydsl-apt:${querydslVersion}:jpa"
annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
annotationProcessor 'jakarta.annotation:jakarta.annotation-api'

configurations.configureEach {
    exclude group: 'com.querydsl'
}
```

**반드시 지킬 네 가지**

1. **classifier 는 `:jpa` 다.** 본가에서 쓰던 `:jakarta` 가 아니다.
   잘못 쓰면 APT 가 붙지 않아 **빌드는 성공하는데 Q 클래스만 생성되지 않는다.**
   컴파일 에러가 나지 않으므로 알아채기 어렵다.
2. **classpath 중복을 선제 차단한다.** 포크와 본가가 같은 `com.querydsl.*` 패키지를 쓰므로
   전이 의존으로라도 둘 다 올라오면 클래스가 충돌한다. `exclude` 를 조건부가 아니라
   빌드 설정에 고정했다.
3. **패키지명은 `com.querydsl.*` 그대로다.** import 문과 코드는 본가와 동일하므로
   기존 QueryDSL *코드*는 그대로 쓸 수 있고, **build.gradle 만** 다르다.
4. Gradle 의 `annotationProcessor` 설정은 JDK 22+ 의 APT opt-in 을 이미 처리한다
   (Maven 은 별도 설정이 필요하다).

## 결과

**검증한 것** (문서가 아니라 실측)

| 검증 | 결과 |
|---|---|
| Q 클래스 생성 | `build/generated/.../QRentalEntity.java` 존재 확인 |
| 타입 매핑 | `DateTimePath<java.time.LocalDateTime>` 로 생성됨 |
| classpath 중복 | `io.github.openfeign.querydsl` 만, `com.querydsl` 그룹 0건 |
| 실제 쿼리 동작 | 동적 조건·keyset 스크롤 통합 테스트 14건 통과 |

**감수한 위험**
- 포크에 의존한다. 포크가 멈추면 다시 갈아타야 한다.
  다만 본가가 이미 멈춘 상태라 선택지가 없었다.
- 포크의 버전 정책이 본가와 다르다. 업그레이드 시 릴리스 노트를 직접 확인해야 한다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 본가 `com.querydsl:5.1.0:jakarta` | Hibernate 7 미지원. Boot 4 에서 동작하지 않는다 |
| QueryDSL 을 빼고 Spring Data Specification | 동적 쿼리는 되지만 타입 안전성이 약하고 가독성이 크게 떨어진다. 요구사항이 QueryDSL 을 명시했다 |
| JPQL `(:param is null or ...)` 패턴 | 실제로 동작하고 의존성도 없다. 다만 조건이 늘수록 쿼리가 비대해지고 컴파일 타임 검증이 없다. 요구사항 불충족 |
| Boot 3.5 로 낮춘다 | 요구사항이 Boot 4 를 명시했다. 문제를 푸는 게 아니라 회피하는 것 |
