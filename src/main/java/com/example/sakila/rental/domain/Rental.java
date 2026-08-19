package com.example.sakila.rental.domain;

import java.time.LocalDateTime;

/**
 * 대여 애그리거트 — 순수 도메인 모델.
 *
 * <p>이 클래스는 프레임워크에 전혀 의존하지 않는다. JPA·Spring·Lombok 어느 것도 쓰지 않는다.
 * ArchitectureTest 가 이 규칙을 강제한다.
 *
 * <p><b>Lombok 을 쓰지 않는 이유</b> — {@code @Builder} 는 생성자 검증을 통째로 건너뛰고,
 * {@code @NoArgsConstructor} 는 불변식을 우회하는 기본 생성자를 열고,
 * {@code @Setter} 는 반납된 대여를 바깥에서 되돌릴 수 있게 만든다.
 * 생성자가 불변식을 강제하므로 유효하지 않은 Rental 객체는 존재할 수 없다.
 */
public class Rental {

    private final Integer id;
    private final Integer inventoryId;
    private final Integer customerId;
    private final Integer staffId;
    private final LocalDateTime rentalDate;
    private LocalDateTime returnDate;

    /**
     * 새 대여를 시작한다. 반납일은 아직 없다.
     */
    public Rental(Integer inventoryId, Integer customerId, Integer staffId, LocalDateTime rentalDate) {
        this(null, inventoryId, customerId, staffId, rentalDate, null);
    }

    private Rental(Integer id, Integer inventoryId, Integer customerId, Integer staffId,
                   LocalDateTime rentalDate, LocalDateTime returnDate) {
        if (inventoryId == null || inventoryId <= 0) {
            throw new IllegalArgumentException("inventoryId 는 양수여야 합니다: " + inventoryId);
        }
        if (customerId == null || customerId <= 0) {
            throw new IllegalArgumentException("customerId 는 양수여야 합니다: " + customerId);
        }
        if (staffId == null || staffId <= 0) {
            throw new IllegalArgumentException("staffId 는 양수여야 합니다: " + staffId);
        }
        if (rentalDate == null) {
            throw new IllegalArgumentException("rentalDate 는 필수입니다.");
        }
        if (returnDate != null && returnDate.isBefore(rentalDate)) {
            throw new IllegalArgumentException(
                    "반납일이 대여일보다 앞설 수 없습니다: rentalDate=" + rentalDate + ", returnDate=" + returnDate);
        }
        this.id = id;
        this.inventoryId = inventoryId;
        this.customerId = customerId;
        this.staffId = staffId;
        this.rentalDate = rentalDate;
        this.returnDate = returnDate;
    }

    /**
     * 저장된 상태를 그대로 복원한다. <b>검증을 우회하는 경로</b>이므로 일반 생성과 구분한다.
     *
     * <p>이미 반납된 대여를 "생성자로 만든 뒤 returnBy() 호출"로는 복원할 수 없다.
     * 그렇게 하면 반납 시각이 지금으로 덮이기 때문이다. 인프라 계층만 이 메서드를 쓴다.
     */
    public static Rental restore(Integer id, Integer inventoryId, Integer customerId, Integer staffId,
                                 LocalDateTime rentalDate, LocalDateTime returnDate) {
        return new Rental(id, inventoryId, customerId, staffId, rentalDate, returnDate);
    }

    /**
     * 반납 처리. 이미 반납된 건은 다시 반납할 수 없다.
     */
    public void returnAt(LocalDateTime when) {
        if (when == null) {
            throw new IllegalArgumentException("반납 시각은 필수입니다.");
        }
        if (isReturned()) {
            throw new IllegalStateException("이미 반납된 대여입니다: rentalId=" + id + ", returnDate=" + returnDate);
        }
        if (when.isBefore(rentalDate)) {
            throw new IllegalArgumentException(
                    "반납일이 대여일보다 앞설 수 없습니다: rentalDate=" + rentalDate + ", returnDate=" + when);
        }
        this.returnDate = when;
    }

    public boolean isReturned() {
        return returnDate != null;
    }

    /**
     * 대여 기간(일). 반납 전이면 기준 시각까지의 경과일을 센다.
     */
    public long rentedDays(LocalDateTime now) {
        LocalDateTime end = isReturned() ? returnDate : now;
        return java.time.Duration.between(rentalDate, end).toDays();
    }

    /**
     * 연체 여부. Sakila 에는 대여 기간이 film.rental_duration 에 있으나
     * 이 애그리거트는 일수를 인자로 받아 정책을 바깥에 둔다.
     */
    public boolean isOverdue(int allowedDays, LocalDateTime now) {
        return !isReturned() && rentedDays(now) > allowedDays;
    }

    public Integer id() {
        return id;
    }

    public Integer inventoryId() {
        return inventoryId;
    }

    public Integer customerId() {
        return customerId;
    }

    public Integer staffId() {
        return staffId;
    }

    public LocalDateTime rentalDate() {
        return rentalDate;
    }

    public LocalDateTime returnDate() {
        return returnDate;
    }
}
