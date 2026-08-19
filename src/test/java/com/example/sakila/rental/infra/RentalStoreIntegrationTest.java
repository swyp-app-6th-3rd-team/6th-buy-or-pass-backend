package com.example.sakila.rental.infra;

import com.example.sakila.rental.domain.Rental;
import com.example.sakila.rental.domain.RentalSearchCondition;
import com.example.sakila.rental.domain.RentalStore;
import com.example.sakila.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryDSL 동적 조건과 keyset 스크롤을 실제 MySQL 로 검증한다.
 *
 * <p>시드 데이터를 쓰지 않고 필요한 픽스처만 직접 만든다.
 * rental 은 FK 로 customer·staff·inventory 를 참조하므로 최소한의 부모 행을 함께 넣는다.
 */
@IntegrationTest
@Transactional
class RentalStoreIntegrationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 1, 9, 0);

    @Autowired
    private RentalStore rentalStore;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpFixtures() {
        // FK 를 만족시킬 최소 부모 행. 순서가 중요하다(country -> city -> address -> store/staff).
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("INSERT IGNORE INTO country(country_id, country) VALUES (1, 'Korea')");
        jdbc.update("INSERT IGNORE INTO city(city_id, city, country_id) VALUES (1, 'Seoul', 1)");
        jdbc.update("""
                INSERT IGNORE INTO address(address_id, address, district, city_id, phone)
                VALUES (1, 'Gangnam 1', 'Gangnam', 1, '02-0000-0000')""");
        jdbc.update("""
                INSERT IGNORE INTO store(store_id, manager_staff_id, address_id) VALUES (1, 1, 1)""");
        jdbc.update("""
                INSERT IGNORE INTO staff(staff_id, first_name, last_name, address_id, store_id, active, username)
                VALUES (1, 'Test', 'Staff', 1, 1, 1, 'tester')""");
        jdbc.update("INSERT IGNORE INTO language(language_id, name) VALUES (1, 'English')");
        jdbc.update("""
                INSERT IGNORE INTO film(film_id, title, language_id, rental_duration, rental_rate, replacement_cost)
                VALUES (1, 'TEST FILM', 1, 3, 4.99, 19.99)""");
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT IGNORE INTO inventory(inventory_id, film_id, store_id) VALUES (?, 1, 1)", i);
        }
        for (int i = 1; i <= 3; i++) {
            jdbc.update("""
                    INSERT IGNORE INTO customer(customer_id, store_id, first_name, last_name, address_id, active, create_date)
                    VALUES (?, 1, 'C', ?, 1, 1, ?)""", i, String.valueOf(i), BASE);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("DELETE FROM rental");
    }

    private Rental persist(int inventoryId, int customerId, LocalDateTime rentedAt, LocalDateTime returnedAt) {
        Rental rental = rentalStore.save(new Rental(inventoryId, customerId, 1, rentedAt));
        if (returnedAt != null) {
            rental.returnAt(returnedAt);
            rentalStore.save(rental);
        }
        return rental;
    }

    @Test
    @DisplayName("저장 후 ID 로 다시 읽으면 같은 값이다")
    void savesAndReadsBack() {
        Rental saved = persist(1, 1, BASE, null);

        assertThat(saved.id()).isNotNull();
        Rental found = rentalStore.findById(saved.id()).orElseThrow();

        assertThat(found.inventoryId()).isEqualTo(1);
        assertThat(found.customerId()).isEqualTo(1);
        assertThat(found.rentalDate()).isEqualTo(BASE);
        assertThat(found.isReturned()).isFalse();
    }

    @Test
    @DisplayName("반납 상태가 DB 에 반영된다")
    void persistsReturn() {
        Rental rental = persist(1, 1, BASE, null);
        rental.returnAt(BASE.plusDays(2));
        rentalStore.save(rental);

        Rental reloaded = rentalStore.findById(rental.id()).orElseThrow();

        assertThat(reloaded.isReturned()).isTrue();
        assertThat(reloaded.returnDate()).isEqualTo(BASE.plusDays(2));
    }

    @Test
    @DisplayName("QueryDSL — 조건을 주지 않으면 전부 조회한다")
    void searchWithoutCondition() {
        persist(1, 1, BASE, null);
        persist(2, 2, BASE.plusDays(1), BASE.plusDays(3));

        var page = rentalStore.search(RentalSearchCondition.empty(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("QueryDSL — 고객으로 거른다")
    void searchByCustomer() {
        persist(1, 1, BASE, null);
        persist(2, 2, BASE, null);
        persist(3, 2, BASE.plusDays(1), null);

        var page = rentalStore.search(
                new RentalSearchCondition(2, null, null, null, null, null),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(r -> r.customerId() == 2);
    }

    @Test
    @DisplayName("QueryDSL — 반납 여부로 거른다")
    void searchByReturned() {
        persist(1, 1, BASE, null);                      // 미반납
        persist(2, 1, BASE, BASE.plusDays(1));          // 반납
        persist(3, 1, BASE, BASE.plusDays(2));          // 반납

        var unreturned = rentalStore.search(
                new RentalSearchCondition(null, null, null, false, null, null), PageRequest.of(0, 10));
        var returned = rentalStore.search(
                new RentalSearchCondition(null, null, null, true, null, null), PageRequest.of(0, 10));

        assertThat(unreturned.getTotalElements()).isEqualTo(1);
        assertThat(returned.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("QueryDSL — 기간으로 거른다")
    void searchByPeriod() {
        persist(1, 1, BASE, null);
        persist(2, 1, BASE.plusDays(5), null);
        persist(3, 1, BASE.plusDays(10), null);

        var page = rentalStore.search(
                new RentalSearchCondition(null, null, null, null, BASE.plusDays(4), BASE.plusDays(6)),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).rentalDate()).isEqualTo(BASE.plusDays(5));
    }

    @Test
    @DisplayName("QueryDSL — 조건 여러 개를 동시에 건다")
    void searchByMultipleConditions() {
        persist(1, 1, BASE, null);
        persist(2, 1, BASE.plusDays(1), BASE.plusDays(2));
        persist(3, 2, BASE.plusDays(1), null);

        var page = rentalStore.search(
                new RentalSearchCondition(1, 1, null, false, BASE.minusDays(1), BASE.plusDays(5)),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).inventoryId()).isEqualTo(1);
    }

    @Test
    @DisplayName("정렬 — 허용 목록에 없는 필드는 무시한다")
    void ignoresUnknownSortField() {
        persist(1, 1, BASE, null);
        persist(2, 1, BASE.plusDays(1), null);

        // 인덱스 없는 임의 컬럼으로 정렬해 풀스캔을 유발하는 것을 막는다.
        var page = rentalStore.search(RentalSearchCondition.empty(),
                PageRequest.of(0, 10, Sort.by("nonexistentColumn")));

        assertThat(page.getTotalElements()).isEqualTo(2);   // 터지지 않고 기본 정렬로 동작
    }

    @Test
    @DisplayName("페이징 — 마지막 페이지에서 hasNext 가 false 다")
    void paginates() {
        for (int i = 1; i <= 5; i++) {
            persist(i, 1, BASE.plusDays(i), null);
        }

        var first = rentalStore.search(RentalSearchCondition.empty(), PageRequest.of(0, 2));
        var last = rentalStore.search(RentalSearchCondition.empty(), PageRequest.of(2, 2));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getContent()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    @DisplayName("스크롤 — 커서를 따라가면 전체를 중복·누락 없이 읽는다")
    void scrollsThroughAll() {
        for (int i = 1; i <= 5; i++) {
            persist(i, 1, BASE.plusDays(i), null);
        }

        List<Integer> collected = new ArrayList<>();
        ScrollPosition position = ScrollPosition.keyset();
        for (int guard = 0; guard < 10; guard++) {
            Window<Rental> window = rentalStore.scroll(RentalSearchCondition.empty(), position, 2);
            window.forEach(r -> collected.add(r.id()));
            if (!window.hasNext()) {
                break;
            }
            position = window.positionAt(window.size() - 1);
        }

        assertThat(collected).hasSize(5);
        assertThat(new HashSet<>(collected)).hasSize(5);   // 중복 없음
    }

    @Test
    @DisplayName("스크롤 — 대여일이 같아도 누락·중복이 없다")
    void scrollHandlesTiedSortKeys() {
        // 정렬 키가 rentalDate 뿐이면 동률 구간에서 행이 새거나 겹친다.
        // (rentalDate, id) 로 전체 순서를 유일하게 만든 이유를 고정하는 테스트다.
        for (int i = 1; i <= 5; i++) {
            persist(i, 1, BASE, null);       // 전부 같은 시각
        }

        Set<Integer> collected = new HashSet<>();
        ScrollPosition position = ScrollPosition.keyset();
        int total = 0;
        for (int guard = 0; guard < 10; guard++) {
            Window<Rental> window = rentalStore.scroll(RentalSearchCondition.empty(), position, 2);
            for (Rental r : window) {
                collected.add(r.id());
                total++;
            }
            if (!window.hasNext()) {
                break;
            }
            position = window.positionAt(window.size() - 1);
        }

        assertThat(total).isEqualTo(5);        // 누락 없음
        assertThat(collected).hasSize(5);      // 중복 없음
    }

    @Test
    @DisplayName("스크롤 — 조건과 함께 쓸 수 있다")
    void scrollsWithCondition() {
        persist(1, 1, BASE, null);
        persist(2, 2, BASE.plusDays(1), null);
        persist(3, 1, BASE.plusDays(2), null);

        Window<Rental> window = rentalStore.scroll(
                new RentalSearchCondition(1, null, null, null, null, null),
                ScrollPosition.keyset(), 10);

        assertThat(window.getContent()).hasSize(2);
        assertThat(window.getContent()).allMatch(r -> r.customerId() == 1);
    }

    @Test
    @DisplayName("미반납 목록은 오래된 순이다")
    void findsOutstandingOldestFirst() {
        persist(1, 1, BASE.plusDays(3), null);
        persist(2, 1, BASE.plusDays(1), null);
        persist(3, 1, BASE.plusDays(2), BASE.plusDays(5));   // 반납됨

        var page = rentalStore.findOutstanding(PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).rentalDate()).isEqualTo(BASE.plusDays(1));
    }

    @Test
    @DisplayName("재고 중복 대여 방지 — 미반납 건이 있으면 true 다")
    void detectsBusyInventory() {
        persist(1, 1, BASE, null);
        persist(2, 1, BASE, BASE.plusDays(1));     // 반납 완료

        assertThat(rentalStore.existsUnreturnedByInventoryId(1)).isTrue();
        assertThat(rentalStore.existsUnreturnedByInventoryId(2)).isFalse();
        assertThat(rentalStore.existsUnreturnedByInventoryId(3)).isFalse();
    }
}
