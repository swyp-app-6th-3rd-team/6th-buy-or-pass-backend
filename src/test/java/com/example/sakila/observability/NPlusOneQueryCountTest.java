package com.example.sakila.observability;

import com.example.sakila.sakila.infra.PaymentEntity;
import com.example.sakila.sakila.infra.PaymentRepository;
import com.example.sakila.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N+1 방어가 실제로 작동하는지 쿼리 횟수로 검증한다.
 *
 * <p>이 테스트가 존재하는 이유 — {@code application.yml} 의
 * {@code default_batch_fetch_size: 100} 은 처음부터 있었지만
 * <strong>정말 듣는지 확인한 적이 없었다.</strong> 관측성 도입의 목적 중 하나가
 * 정확히 이 검증이었으므로, 추측 대신 숫자로 남긴다.
 *
 * <p>측정 수단은 Hibernate {@code Statistics} 다. 애플리케이션 코드가 아니라
 * 테스트에서만 켜므로 운영 성능에 영향이 없다.
 *
 * <p>대상 선택 이유:
 * <ul>
 *   <li>{@code rental} 슬라이스는 쓸 수 없다 — {@code RentalEntity} 는 FK 가 전부
 *       스칼라라 JPA 연관관계가 하나도 없어서 N+1 이 발생하지 않는다.</li>
 *   <li>{@code Payment → Customer} 를 쓴다. 부모가 서로 달라 1 차 캐시가 중복을
 *       제거하지 못하므로 N+1 이 그대로 드러난다.
 *       ({@code Film → Language} 는 언어가 6 개뿐이라 캐시에 걸려 부적합하다.)</li>
 * </ul>
 */
@IntegrationTest
@Transactional
@DisplayName("N+1 방어 검증 (쿼리 횟수 측정)")
class NPlusOneQueryCountTest {

    /** 조회할 결제 건수. 각 건이 서로 다른 customer 를 가리킨다. */
    private static final int PAYMENT_COUNT = 30;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 1, 9, 0);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUpFixtures() {
        // FK 순서가 중요하다: country -> city -> address -> store/staff -> customer -> payment
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("INSERT IGNORE INTO country(country_id, country) VALUES (1, 'Korea')");
        jdbc.update("INSERT IGNORE INTO city(city_id, city, country_id) VALUES (1, 'Seoul', 1)");
        jdbc.update("""
                INSERT IGNORE INTO address(address_id, address, district, city_id, phone)
                VALUES (1, 'Gangnam 1', 'Gangnam', 1, '02-0000-0000')""");
        jdbc.update("INSERT IGNORE INTO store(store_id, manager_staff_id, address_id) VALUES (1, 1, 1)");
        jdbc.update("""
                INSERT IGNORE INTO staff(staff_id, first_name, last_name, address_id, store_id, active, username)
                VALUES (1, 'Test', 'Staff', 1, 1, 1, 'tester')""");

        // 결제 1건당 customer 1명 — 부모가 전부 달라야 N+1 이 드러난다.
        for (int i = 1; i <= PAYMENT_COUNT; i++) {
            jdbc.update("""
                    INSERT IGNORE INTO customer(customer_id, store_id, first_name, last_name,
                                                address_id, active, create_date)
                    VALUES (?, 1, 'C', ?, 1, 1, ?)""", i, String.valueOf(i), BASE);
            jdbc.update("""
                    INSERT IGNORE INTO payment(payment_id, customer_id, staff_id, amount, payment_date)
                    VALUES (?, ?, 1, ?, ?)""", i, i, BigDecimal.valueOf(1.99), BASE);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");

        // 픽스처 INSERT 가 통계에 섞이지 않게 초기화한다.
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
    }

    @Test
    @DisplayName("배치 페치가 지연 로딩을 묶어 쿼리 폭증을 막는다")
    void batchFetchPreventsQueryExplosion() {
        var payments = paymentRepository.findAll(PageRequest.of(0, PAYMENT_COUNT));
        assertThat(payments).hasSize(PAYMENT_COUNT);

        long afterList = statistics().getPrepareStatementCount();

        // 지연 로딩을 실제로 유발한다.
        // open-in-view: false 이므로 트랜잭션 경계 안에서만 가능하다(@Transactional).
        payments.forEach(p -> p.getCustomer().getFirstName());

        long total = statistics().getPrepareStatementCount();
        long lazyLoadQueries = total - afterList;

        // 배치 페치가 없으면 부모 30건을 하나씩 읽어 30회가 된다.
        // default_batch_fetch_size: 100 이면 IN 절로 묶여 1회로 접힌다.
        assertThat(lazyLoadQueries)
                .as("""
                        지연 로딩 쿼리 %d회 (전체 %d회).
                        배치 페치가 꺼져 있으면 %d회여야 하므로, 이보다 훨씬 적으면 방어가 동작한 것이다.
                        default_batch_fetch_size 설정: application.yml""".formatted(
                        lazyLoadQueries, total, PAYMENT_COUNT))
                .isLessThan(PAYMENT_COUNT / 2);

        System.out.printf(
                "[N+1 측정] 목록 조회 후 쿼리=%d, 지연 로딩 쿼리=%d, 전체=%d (배치 미적용 시 예상=%d)%n",
                afterList, lazyLoadQueries, total, PAYMENT_COUNT);
    }

    private Statistics statistics() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }
}
