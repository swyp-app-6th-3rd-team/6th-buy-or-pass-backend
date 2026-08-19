package com.example.sakila.rental.service;

import com.example.sakila.common.ResponseCode;
import com.example.sakila.error.ApiException;
import com.example.sakila.rental.domain.Rental;
import com.example.sakila.rental.domain.RentalSearchCondition;
import com.example.sakila.rental.domain.RentalStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RentalQueryService {

    /**
     * 한 번에 내보낼 수 있는 최대 건수. 이건 <b>정책</b>이지 불변식이 아니므로
     * 도메인이 아니라 여기(서비스)에 둔다. 정책이 바뀔 때 도메인이 따라 바뀌면 안 된다.
     */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_SCROLL_LIMIT = 20;

    private final RentalStore rentalStore;

    public Rental getById(Integer rentalId) {
        return rentalStore.findById(rentalId)
                .orElseThrow(() -> new ApiException(ResponseCode.RENTAL_NOT_FOUND));
    }

    public Page<Rental> search(RentalSearchCondition condition, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new ApiException(ResponseCode.INVALID_REQUEST,
                    "페이지 크기는 " + MAX_PAGE_SIZE + " 이하여야 합니다: " + pageable.getPageSize());
        }
        return rentalStore.search(condition, pageable);
    }

    /**
     * 무한 스크롤. offset 을 쓰지 않으므로 뒤쪽 조각에서도 성능이 일정하다.
     */
    public Window<Rental> scroll(RentalSearchCondition condition, ScrollPosition position, Integer limit) {
        int effectiveLimit = (limit == null) ? DEFAULT_SCROLL_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_PAGE_SIZE) {
            throw new ApiException(ResponseCode.INVALID_REQUEST,
                    "limit 은 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다: " + effectiveLimit);
        }
        return rentalStore.scroll(condition, position, effectiveLimit);
    }

    public Page<Rental> findOutstanding(Pageable pageable) {
        return rentalStore.findOutstanding(pageable);
    }
}
