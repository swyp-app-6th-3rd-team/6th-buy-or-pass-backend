# ADR-0007 — Scalar 는 자동설정 대신 직접 등록한다

**상태**: Accepted

## 맥락

API 문서 UI 로 Scalar(`com.scalar.maven:scalar-webmvc:0.6.61`)를 쓰기로 했다.
의존성을 넣고 `application.yml` 에 `scalar.*` 설정도 채웠는데 `/scalar` 가 **404** 였다.

조사 결과:

| 확인 | 결과 |
|---|---|
| jar 의 `META-INF/spring/...AutoConfiguration.imports` | 존재하고 경로도 표준 |
| 전이 의존 `scalar-core` | 정상 해석됨 |
| `--debug` 조건 평가 보고서에서 "scalar" | **0건** — 조건 불일치가 아니라 후보에조차 오르지 않음 |
| `/v3/api-docs`, `/swagger-ui.html` | 정상 (springdoc 은 동작) |

라이브러리가 Boot 3 기준으로 빌드되어 Boot 4 의 모듈 재편과 맞지 않는 것으로 보인다.
(같은 이유로 springdoc 도 2.8.13 → 3.1.0 으로 올려야 했다. Boot 4 는 생태계 라이브러리
버전을 개별 확인해야 한다.)

## 결정

**자동설정에 의존하지 않고 컨트롤러를 직접 등록한다.**

렌더러(`scalar-core`)는 프레임워크와 무관하게 동작하므로 그것만 쓴다.

```java
@Configuration
@RestController
@ConditionalOnProperty(prefix = "scalar", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScalarConfig {

    @GetMapping(value = "${scalar.path:/scalar}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> docs() throws IOException {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(ScalarHtmlRenderer.render(properties));
    }

    @GetMapping("${scalar.path:/scalar}/scalar.js")
    public ResponseEntity<byte[]> scalarJs() throws IOException { ... }
}
```

설정 키(`scalar.url`, `scalar.path`, `scalar.page-title`, `scalar.dark-mode`)는
라이브러리 자동설정과 **같은 이름**을 쓴다. Boot 4 지원 버전이 나오면
이 클래스만 지우고 설정은 그대로 두면 된다.

## 결과

**얻은 것** — `/scalar` 200, `/scalar/scalar.js` 200, 페이지 타이틀 정상 렌더.
Swagger UI(`/swagger-ui.html`)와 병행 제공한다.

**포기한 것**
- 라이브러리 업그레이드 시 이 클래스가 중복 매핑을 일으킬 수 있다.
  `@ConditionalOnMissingBean` 을 쓸 수도 있었지만, 자동설정이 아예 안 뜨는 상황이라
  조건이 무의미해 명시적으로 두고 클래스 주석에 제거 조건을 적었다.
- Scalar 의 고급 설정(플러그인·커스텀 CSS 등)은 노출하지 않았다. 필요하면
  `ScalarProperties` 에 setter 가 있으므로 추가하면 된다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| Scalar 를 제거하고 Swagger UI 만 | 요구사항으로 명시적으로 확인받은 기능이라 제거하지 않는다 |
| Boot 4 지원 버전을 기다린다 | 언제 나올지 모르고, 그동안 문서 UI 가 비어 있다 |
| `AutoConfiguration.imports` 를 직접 추가 | 라이브러리 내부 클래스에 의존하는 더 깨지기 쉬운 방식이다. 컨트롤러 직접 등록이 명시적이고 짧다 |
| Redoc 등 다른 UI 로 교체 | 요구사항이 Scalar 를 지목했다 |
