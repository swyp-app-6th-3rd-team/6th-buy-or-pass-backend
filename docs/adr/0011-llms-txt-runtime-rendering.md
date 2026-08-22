# ADR-0011 — API 문서를 LLM 친화 마크다운으로 런타임 렌더링한다

**상태**: Accepted

## 맥락

FE 개발자가 바이브 코딩 워크플로우를 돌릴 때 API 계약을 LLM 프롬프트에 붙여넣는다.
그런데 이 저장소가 제공하던 문서 표면 셋은 모두 **사람이 브라우저로 읽는 것**을 전제한다.

| 경로 | 형식 | LLM 프롬프트 적합성 |
|---|---|---|
| `/swagger-ui.html` | HTML(JS 렌더) | 긁으면 마크업이 딸려온다 |
| `/scalar` | HTML(JS 렌더) | 동일 |
| `/v3/api-docs` | OpenAPI JSON | `$ref` 로 정규화돼 스키마가 흩어지고 토큰이 비싸다 |

`$ref` 문제가 특히 크다. OAS 는 스키마를 `#/components/schemas/MeResponse` 로 정규화해 두는데,
LLM 에게는 그 참조를 펼쳐 한자리에 보여줘야 쓸모가 있다.

문서를 손으로 따로 관리하는 선택지도 있었으나, 그 순간 **코드와 문서가 어긋나기 시작한다.**
이 결정의 제1 요구사항은 포맷이 아니라 **드리프트가 구조적으로 불가능할 것**이다.

### springdoc 3.1.0 의 제약 (jar 디컴파일로 확인)

| 확인 항목 | 결과 |
|---|---|
| `AbstractOpenApiResource.getOpenApi(Locale)` | **protected** — 완성된 `OpenAPI` 객체를 밖에서 못 받는다 |
| `OpenApiWebMvcResource.openapiJson(HttpServletRequest, String, Locale)` | **public** — JSON 바이트는 받을 수 있다 |
| `OpenApiWebMvcResource` 자체 애노테이션 | **`@RestController` + `@GetMapping`** |
| 클래스패스의 Jackson | **2.21.4(`com.fasterxml`)와 3.1.4(`tools.jackson`)가 동시 존재** |

## 결정

**springdoc 이 만든 스펙을 런타임에 읽어 마크다운으로 렌더하는 엔드포인트를 둔다.**
`GET /llms.txt` (별칭 `/llms.md`), `Content-Type: text/plain;charset=UTF-8`.

```
@RestController / @GetMapping ...          ← 우리가 쓰는 코드
        │  springdoc 이 기동 시 스캔
        ▼
   OpenAPI 객체 (메모리, 단일 진실원천)
        ├──▶ /v3/api-docs (JSON) ──▶ Swagger UI · Scalar   [기존]
        └──▶ /llms.txt (Markdown) ──▶ FE 프롬프트           [추가분]
```

컨트롤러를 읽는 주체는 **springdoc** 이고, 추가한 코드는 그 결과를 마크다운으로
**직렬화만** 한다. Swagger UI 가 JSON 을 받아 HTML 을 그리는 것과 정확히 같은 자리에
출력 포맷만 하나 더 건 것이다.

세부 결정 셋:

- **OAS 획득은 `openapiJson()` 경유.** `getOpenApi()` 가 protected 라 public API 는 이것뿐이다.
- **파싱은 `io.swagger.v3.core.util.Json31.mapper()`.** 스프링이 관리하는 `ObjectMapper` 빈을
  주입하면 Jackson 3 이 오는데 springdoc 은 Jackson 2 로 직렬화한다. 두 라이브러리의
  `ObjectMapper` 는 이름이 같아 import 를 잘못 써도 컴파일이 통과하므로, 우리가 고르지 않고
  swagger 가 자기 모델과 버전을 맞춰 제공하는 매퍼에 위임한다.
- **`text/plain`.** `text/markdown` 을 주면 브라우저가 다운로드를 띄우는 경우가 있어
  FE 가 브라우저에서 열어 긁는 동선이 끊긴다.

## 결과

**얻은 것**

- 드리프트가 **구조적으로 불가능**하다. 컨트롤러를 고치고 재기동하면 문서가 저절로 따라온다.
  (검증: `@Operation(summary=...)` 를 한 줄 고치고 재기동 → 렌더러 코드 변경 0 으로 출력이 바뀜)
- `$ref` 가 인라인 전개돼 LLM 이 스키마를 한자리에서 본다.
- `ApiResponse` 봉투를 머리말에 한 번만 적어 오퍼레이션마다 반복되던 3필드를 없앴다.
- 기존 문서 표면(`/v3/api-docs`·`/swagger-ui.html`·`/scalar`)에 회귀 없음.
- 곁가지 이득 — 제목을 채우려 `OpenAPI` 빈을 추가하면서 Swagger UI·Scalar 의 제목도
  "OpenAPI definition" 에서 "Buy or Pass API" 로 함께 고쳐졌다.

**포기한 것**

- 직렬화(OpenAPI→JSON) 후 역파싱(JSON→트리) 왕복이 요청당 한 번 생긴다.
  문서 생성 경로는 요청당 ms 가 아쉬운 자리가 아니라 **public API 만 쓰는 안정성**을 샀다.
- **오퍼레이션별 인증 여부를 표시하지 못한다.** `SecurityScheme` 빈이 없어 스펙의
  `operation.security` 가 비어 있다. 없는 메타데이터를 지어내면 그게 바로 이 기능이 막으려는
  드리프트이므로, 인증 규약은 머리말에 전역으로만 적는다.
- 캐싱하지 않는다. 측정 없이 최적화하지 않는다는 원칙에 따르며,
  `/v3/api-docs` 도 매 요청 생성이고 문서 경로는 호출 빈도가 낮다.

**보안**

`/llms.txt` 는 이미 공개된 `/v3/api-docs` 와 **같은 내용을 다른 포맷으로** 낼 뿐이라
새로 노출되는 정보가 없다. `SecurityConfig` 의 `PUBLIC_GET` 배열에 기존 문서 경로와
**같은 자리**에 넣어, 나중에 운영에서 문서를 잠글 때 함께 잠기고 갈라지지 않도록 했다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| **가시성 shim 으로 protected `getOpenApi()` 를 뚫어 타입 객체를 직접 순회** | JSON 왕복도 Jackson 의존도 없어 더 깔끔해 보인다. 그러나 `OpenApiWebMvcResource` 에는 `@RestController` 와 `@GetMapping` 이 붙어 있어 **하위 클래스를 빈으로 등록하는 순간 `/v3/api-docs` 매핑이 중복돼 기동이 깨진다.** 빈으로 올리지 않는 우회가 가능하긴 하나 springdoc 내부 7인자 생성자에 결합돼 마이너 업그레이드에서 깨지고, "빈으로 올리면 안 되는 클래스"라는 함정을 코드에 남긴다 |
| **빌드타임에 정적 파일로 굽기**(`static/llms.txt`) | 서버 없이도 문서를 볼 수 있지만, 빌드가 앱 기동에 의존해 매 빌드가 느려진다. 무엇보다 **로컬에서 컨트롤러를 고치고 재기동해도 파일이 안 바뀌어** 드리프트가 되살아난다. 릴리즈 아티팩트에 문서를 굳혀야 할 필요가 실제로 생기면 같은 렌더러를 재사용해 나중에 추가한다 |
| **스프링의 `ObjectMapper` 빈을 주입해 파싱** | 클래스패스에 Jackson 2·3 이 공존하고 `ObjectMapper` 이름이 같아, 잘못 주입해도 컴파일이 통과하는 조용한 함정이 된다 |
| **`text/markdown` 으로 서빙** | 브라우저가 파일 다운로드를 띄우는 경우가 있어 FE 가 브라우저에서 열어 긁는 동선이 끊긴다 |
| **태그별 분할 엔드포인트**(`/llms/{tag}.md`) 동시 도입 | `springdoc.paths-to-match: /api/**` 라 현재 스펙은 **태그 1개·오퍼레이션 3개**다. 전체 문서와 사실상 같은 것을 위해 라우트·슬러그 정규화·404 를 지는 셈이라 측정 없는 최적화다. 렌더러에 `render(oas, tag)` 오버로드만 만들어 두고 라우트는 노출하지 않는다 |
| **문서를 손으로 관리** | 코드와 어긋나기 시작한다. 이 결정의 제1 요구사항을 정면으로 위반한다 |
| **아무것도 하지 않음** | FE 가 매번 JSON 을 손으로 정리하거나 문서를 복사해 쓰게 되고, 그 복사본이 낡는다 |
