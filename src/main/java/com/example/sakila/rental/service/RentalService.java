package com.example.sakila.rental.service;

import com.example.sakila.common.ResponseCode;
import com.example.sakila.error.ApiException;
import com.example.sakila.rental.domain.Rental;
import com.example.sakila.rental.domain.RentalStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 대여 상태를 바꾸는 유스케이스.
 *
 * <p>비즈니스 규칙 자체는 {@link Rental} 안에 있다. 이 클래스는 조립만 한다 —
 * 저장소에서 꺼내고, 도메인 메서드를 부르고, 다시 저장한다.
 * 규칙이 서비스로 새면 도메인이 빈 껍데기가 되므로 경계를 지킨다.
 */
@Service
@RequiredArgsConstructor
public class RentalService {

    private final RentalStore rentalStore;
    private final Clock clock;

    @Transactional
    public Rental rent(RentalCommand.Rent command) {
        if (rentalStore.existsUnreturnedByInventoryId(command.inventoryId())) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "이미 대여 중인 재고입니다: " + command.inventoryId());
        }
        Rental rental = new Rental(
                command.inventoryId(),
                command.customerId(),
                command.staffId(),
                LocalDateTime.now(clock));
        return rentalStore.save(rental);
    }

    @Transactional
    public Rental returnRental(Integer rentalId) {
        Rental rental = rentalStore.findById(rentalId)
                .orElseThrow(() -> new ApiException(ResponseCode.RENTAL_NOT_FOUND));

        if (rental.isReturned()) {
            throw new ApiException(ResponseCode.RENTAL_ALREADY_RETURNED);
        }
        // 상태 전이 규칙은 도메인이 강제한다.
        rental.returnAt(LocalDateTime.now(clock));
        return rentalStore.save(rental);
    }
}
