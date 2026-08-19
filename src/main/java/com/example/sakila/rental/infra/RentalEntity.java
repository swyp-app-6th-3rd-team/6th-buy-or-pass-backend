package com.example.sakila.rental.infra;

import com.example.sakila.rental.domain.Rental;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * rental 테이블의 한 행.
 *
 * <p>비즈니스 규칙은 여기 없다. 이 클래스의 책임은 "테이블 한 행을 표현하는 것"뿐이고,
 * JPA 가 요구하는 것(기본 생성자·가변 필드)을 전부 받아들인다.
 * 규칙은 {@link Rental} 도메인 객체가 갖는다.
 *
 * <p><b>변환 방향은 엔티티 → 도메인</b>이다. 엔티티가 도메인을 알고, 도메인은 엔티티를 모른다.
 * 반대로 하면 {@code domain → infra} 역참조가 되어 아키텍처 규칙을 위반한다.
 */
@Getter
@Entity
@Table(name = "rental")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rental_id")
    private Integer id;

    @Column(name = "rental_date", nullable = false)
    private LocalDateTime rentalDate;

    @Column(name = "inventory_id", nullable = false)
    private Integer inventoryId;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Column(name = "return_date")
    private LocalDateTime returnDate;

    @Column(name = "staff_id", nullable = false)
    private Integer staffId;

    /**
     * DB 가 {@code ON UPDATE CURRENT_TIMESTAMP} 로 직접 갱신하는 컬럼이다.
     * JPA 가 쓰기에 관여하면 DB 자동 갱신과 충돌하므로 읽기 전용으로 매핑한다.
     */
    @Column(name = "last_update", insertable = false, updatable = false)
    private LocalDateTime lastUpdate;

    private RentalEntity(Rental rental) {
        this.id = rental.id();
        this.rentalDate = rental.rentalDate();
        this.inventoryId = rental.inventoryId();
        this.customerId = rental.customerId();
        this.staffId = rental.staffId();
        this.returnDate = rental.returnDate();
    }

    public static RentalEntity from(Rental rental) {
        return new RentalEntity(rental);
    }

    /**
     * 가변 상태만 다시 적용한다. 대여일·재고·고객은 한 번 정해지면 바뀌지 않는다.
     */
    public void applyState(Rental rental) {
        this.returnDate = rental.returnDate();
    }

    public Rental toDomain() {
        return Rental.restore(id, inventoryId, customerId, staffId, rentalDate, returnDate);
    }
}
