package com.example.sakila.rental.service;

import com.example.sakila.common.ResponseCode;
import com.example.sakila.error.ApiException;
import com.example.sakila.rental.domain.Rental;
import com.example.sakila.rental.domain.RentalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 서비스 단위 테스트 — Store 를 mock 으로 대체해 컨테이너 없이 돌린다.
 * 도메인이 Store 인터페이스에 의존하도록 설계한 덕에 가능하다.
 *
 * <p>Clock 을 고정해 시간에 의존하는 로직을 결정적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RentalServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 12, 0);

    @Mock
    private RentalStore rentalStore;

    private RentalService rentalService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        rentalService = new RentalService(rentalStore, fixedClock);
    }

    @DisplayName("대여 — 재고가 비어 있으면 대여된다")
    @Test
    void rentsWhenInventoryAvailable() {
        given(rentalStore.existsUnreturnedByInventoryId(10)).willReturn(false);
        given(rentalStore.save(any(Rental.class))).willAnswer(inv -> inv.getArgument(0));

        Rental result = rentalService.rent(new RentalCommand.Rent(10, 20, 30));

        assertThat(result.inventoryId()).isEqualTo(10);
        assertThat(result.customerId()).isEqualTo(20);
        assertThat(result.isReturned()).isFalse();

        // 대여일이 고정한 Clock 값으로 찍혔는지 확인한다.
        ArgumentCaptor<Rental> captor = ArgumentCaptor.forClass(Rental.class);
        verify(rentalStore).save(captor.capture());
        assertThat(captor.getValue().rentalDate()).isEqualTo(NOW);
    }

    @DisplayName("대여 — 이미 대여 중인 재고는 거부한다")
    @Test
    void rejectsRentWhenInventoryBusy() {
        given(rentalStore.existsUnreturnedByInventoryId(10)).willReturn(true);

        assertThatThrownBy(() -> rentalService.rent(new RentalCommand.Rent(10, 20, 30)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 대여 중");

        verify(rentalStore, never()).save(any());
    }

    @DisplayName("반납 — 미반납 건을 반납하면 반납일이 찍힌다")
    @Test
    void returnsRental() {
        Rental rental = Rental.restore(1, 10, 20, 30, NOW.minusDays(3), null);
        given(rentalStore.findById(1)).willReturn(Optional.of(rental));
        given(rentalStore.save(any(Rental.class))).willAnswer(inv -> inv.getArgument(0));

        Rental result = rentalService.returnRental(1);

        assertThat(result.isReturned()).isTrue();
        assertThat(result.returnDate()).isEqualTo(NOW);
    }

    @DisplayName("반납 — 없는 대여는 404")
    @Test
    void rejectsReturnWhenNotFound() {
        given(rentalStore.findById(999)).willReturn(Optional.empty());

        assertThatThrownBy(() -> rentalService.returnRental(999))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.RENTAL_NOT_FOUND);
    }

    @DisplayName("반납 — 이미 반납된 건은 409")
    @Test
    void rejectsDoubleReturn() {
        Rental returned = Rental.restore(1, 10, 20, 30, NOW.minusDays(5), NOW.minusDays(1));
        given(rentalStore.findById(1)).willReturn(Optional.of(returned));

        assertThatThrownBy(() -> rentalService.returnRental(1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.RENTAL_ALREADY_RETURNED);

        verify(rentalStore, never()).save(any());
    }
}
