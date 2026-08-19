package com.example.sakila.rental.domain;

import java.time.LocalDateTime;

/**
 * 대여 검색 조건. 모든 필드가 선택적이며 null 이면 해당 조건을 걸지 않는다.
 */
public record RentalSearchCondition(
        Integer customerId,
        Integer staffId,
        Integer inventoryId,
        Boolean returned,
        LocalDateTime rentedFrom,
        LocalDateTime rentedTo) {

    public RentalSearchCondition {
        if (rentedFrom != null && rentedTo != null && rentedTo.isBefore(rentedFrom)) {
            throw new IllegalArgumentException(
                    "조회 종료일이 시작일보다 앞설 수 없습니다: from=" + rentedFrom + ", to=" + rentedTo);
        }
    }

    public static RentalSearchCondition empty() {
        return new RentalSearchCondition(null, null, null, null, null, null);
    }
}
