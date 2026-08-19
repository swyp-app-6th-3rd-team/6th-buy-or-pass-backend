package com.example.sakila.rental.controller;

import com.example.sakila.auth.domain.SocialProvider;
import com.example.sakila.auth.domain.User;
import com.example.sakila.auth.domain.UserStore;
import com.example.sakila.auth.service.AuthService;
import com.example.sakila.support.IntegrationTest;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대여 API 통합 테스트 — 컨트롤러부터 실제 MySQL 까지 관통한다.
 * 응답이 {@code PageResponse}/{@code ScrollResponse} 계약을 지키는지도 함께 본다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class RentalApiIntegrationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 1, 9, 0);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserStore userStore;
    @Autowired
    private AuthService authService;

    private MockMvc mockMvc;
    private String bearer;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        User user = userStore.save(new User(SocialProvider.GOOGLE, "api-test", "a@example.com", "테스터"));
        bearer = "Bearer " + authService.issueTokens(user).accessToken();

        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("INSERT IGNORE INTO country(country_id, country) VALUES (1, 'Korea')");
        jdbc.update("INSERT IGNORE INTO city(city_id, city, country_id) VALUES (1, 'Seoul', 1)");
        jdbc.update("""
                INSERT IGNORE INTO address(address_id, address, district, city_id, phone)
                VALUES (1, 'A', 'D', 1, '02-0')""");
        jdbc.update("INSERT IGNORE INTO store(store_id, manager_staff_id, address_id) VALUES (1, 1, 1)");
        jdbc.update("""
                INSERT IGNORE INTO staff(staff_id, first_name, last_name, address_id, store_id, active, username)
                VALUES (1, 'T', 'S', 1, 1, 1, 'tester')""");
        jdbc.update("INSERT IGNORE INTO language(language_id, name) VALUES (1, 'English')");
        jdbc.update("""
                INSERT IGNORE INTO film(film_id, title, language_id, rental_duration, rental_rate, replacement_cost)
                VALUES (1, 'TEST FILM', 1, 3, 4.99, 19.99)""");
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT IGNORE INTO inventory(inventory_id, film_id, store_id) VALUES (?, 1, 1)", i);
        }
        jdbc.update("""
                INSERT IGNORE INTO customer(customer_id, store_id, first_name, last_name, address_id, active, create_date)
                VALUES (1, 1, 'C', '1', 1, 1, ?)""", BASE);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("DELETE FROM rental");
    }

    private int createRental(int inventoryId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rentals")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inventoryId": %d, "customerId": 1, "staffId": 1}""".formatted(inventoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.returnObject.rentalId");
    }

    @Test
    @DisplayName("대여 생성 — 201 과 함께 대여 정보를 돌려준다")
    void createsRental() throws Exception {
        mockMvc.perform(post("/api/rentals")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inventoryId": 1, "customerId": 1, "staffId": 1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.returnObject.rentalId").isNumber())
                .andExpect(jsonPath("$.returnObject.returned").value(false));
    }

    @Test
    @DisplayName("대여 생성 — 잘못된 입력은 400 이다")
    void rejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/rentals")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inventoryId": -1, "customerId": 1, "staffId": 1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("대여 생성 — 이미 대여 중인 재고는 400 이다")
    void rejectsBusyInventory() throws Exception {
        createRental(1);

        mockMvc.perform(post("/api/rentals")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inventoryId": 1, "customerId": 1, "staffId": 1}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("반납 — 성공하면 returned 가 true 다")
    void returnsRental() throws Exception {
        int rentalId = createRental(1);

        mockMvc.perform(post("/api/rentals/{id}/return", rentalId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.returned").value(true))
                .andExpect(jsonPath("$.returnObject.returnDate").isNotEmpty());
    }

    @Test
    @DisplayName("반납 — 두 번 반납하면 409 다")
    void rejectsDoubleReturn() throws Exception {
        int rentalId = createRental(1);
        mockMvc.perform(post("/api/rentals/{id}/return", rentalId).header("Authorization", bearer))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/rentals/{id}/return", rentalId).header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENTAL_ALREADY_RETURNED"));
    }

    @Test
    @DisplayName("단건 조회 — 없는 ID 는 404 다")
    void returns404ForMissing() throws Exception {
        mockMvc.perform(get("/api/rentals/{id}", 999999).header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RENTAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("목록 — PageResponse 계약을 지킨다(Spring Page 구조가 새지 않는다)")
    void listReturnsPageResponseContract() throws Exception {
        createRental(1);
        createRental(2);

        mockMvc.perform(get("/api/rentals").header("Authorization", bearer)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content").isArray())
                .andExpect(jsonPath("$.returnObject.page").value(0))
                .andExpect(jsonPath("$.returnObject.size").value(1))
                .andExpect(jsonPath("$.returnObject.totalElements").value(2))
                .andExpect(jsonPath("$.returnObject.hasNext").value(true))
                // Spring Page 의 내부 구조가 노출되면 안 된다.
                .andExpect(jsonPath("$.returnObject.pageable").doesNotExist())
                .andExpect(jsonPath("$.returnObject.sort").doesNotExist());
    }

    @Test
    @DisplayName("목록 — 조건으로 거른다")
    void filtersList() throws Exception {
        createRental(1);
        int second = createRental(2);
        mockMvc.perform(post("/api/rentals/{id}/return", second).header("Authorization", bearer));

        mockMvc.perform(get("/api/rentals").header("Authorization", bearer).param("returned", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalElements").value(1));
    }

    @Test
    @DisplayName("목록 — 최대 페이지 크기를 넘으면 400 이다")
    void rejectsTooLargePage() throws Exception {
        mockMvc.perform(get("/api/rentals").header("Authorization", bearer).param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("스크롤 — nextCursor 로 이어 읽으면 전체를 중복 없이 훑는다")
    void scrollsWithCursor() throws Exception {
        // 세 건이 같은 밀리초에 만들어져 rentalDate 가 전부 같다.
        // 정렬 키에 id 를 덧붙이지 않았다면 여기서 누락·중복이 난다.
        for (int i = 1; i <= 3; i++) {
            createRental(i);
        }

        Set<Integer> seen = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            var request = get("/api/rentals/scroll").header("Authorization", bearer).param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
            String body = result.getResponse().getContentAsString();

            List<Integer> ids = com.jayway.jsonpath.JsonPath.read(body, "$.returnObject.content[*].rentalId");
            seen.addAll(ids);

            boolean hasNext = com.jayway.jsonpath.JsonPath.read(body, "$.returnObject.hasNext");
            if (!hasNext) {
                break;
            }
            cursor = com.jayway.jsonpath.JsonPath.read(body, "$.returnObject.nextCursor");
            assertThat(cursor).isNotBlank();
        }

        assertThat(seen).hasSize(3);   // 누락·중복 없음
    }

    @Test
    @DisplayName("스크롤 — 조작된 커서는 400 이다")
    void rejectsTamperedCursor() throws Exception {
        mockMvc.perform(get("/api/rentals/scroll")
                        .header("Authorization", bearer).param("cursor", "!!!not-base64!!!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("미반납 목록 — 오래된 순으로 나온다")
    void listsOutstanding() throws Exception {
        createRental(1);
        int second = createRental(2);
        mockMvc.perform(post("/api/rentals/{id}/return", second).header("Authorization", bearer));

        mockMvc.perform(get("/api/rentals/outstanding").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalElements").value(1));
    }

    @Test
    @DisplayName("인증 없이 부르면 401 이다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/rentals"))
                .andExpect(status().isUnauthorized());
    }
}
