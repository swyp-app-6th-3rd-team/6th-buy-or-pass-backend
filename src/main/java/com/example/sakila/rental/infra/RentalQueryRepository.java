package com.example.sakila.rental.infra;

import com.example.sakila.rental.domain.RentalSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

/**
 * QueryDSL 로 구현하는 동적 조회. Spring Data 의 커스텀 리포지토리 확장 지점이다.
 */
interface RentalQueryRepository {

    Page<RentalEntity> search(RentalSearchCondition condition, Pageable pageable);

    Window<RentalEntity> scroll(RentalSearchCondition condition, ScrollPosition position, int limit);
}
