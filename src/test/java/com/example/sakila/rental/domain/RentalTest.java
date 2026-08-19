package com.example.sakila.rental.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * 도메인 단위 테스트 — 컨테이너도 스프링 컨텍스트도 필요 없다.
 * 도메인이 프레임워크에 의존하지 않으므로 이렇게 빠르게 검증할 수 있다.
 */
class RentalTest {

    private static final LocalDateTime RENTED_AT = LocalDateTime.of(2026, 8, 1, 10, 0);

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("생성 직후에는 반납되지 않은 상태다")
        void newRentalIsNotReturned() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);

            assertThat(rental.isReturned()).isFalse();
            assertThat(rental.returnDate()).isNull();
            assertThat(rental.rentalDate()).isEqualTo(RENTED_AT);
        }

        @Test
        @DisplayName("식별자가 0 이하면 만들 수 없다")
        void rejectsNonPositiveIds() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Rental(0, 2, 3, RENTED_AT))
                    .withMessageContaining("inventoryId");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Rental(1, -1, 3, RENTED_AT))
                    .withMessageContaining("customerId");
        }

        @Test
        @DisplayName("대여일이 없으면 만들 수 없다")
        void rejectsNullRentalDate() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Rental(1, 2, 3, null))
                    .withMessageContaining("rentalDate");
        }
    }

    @Nested
    @DisplayName("반납")
    class Returning {

        @Test
        @DisplayName("반납하면 반납일이 기록된다")
        void returnsSuccessfully() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);
            LocalDateTime returnedAt = RENTED_AT.plusDays(3);

            rental.returnAt(returnedAt);

            assertThat(rental.isReturned()).isTrue();
            assertThat(rental.returnDate()).isEqualTo(returnedAt);
        }

        @Test
        @DisplayName("이미 반납된 건은 다시 반납할 수 없다")
        void rejectsDoubleReturn() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);
            rental.returnAt(RENTED_AT.plusDays(1));

            assertThatIllegalStateException()
                    .isThrownBy(() -> rental.returnAt(RENTED_AT.plusDays(2)))
                    .withMessageContaining("이미 반납된");
        }

        @Test
        @DisplayName("반납일이 대여일보다 앞설 수 없다")
        void rejectsReturnBeforeRental() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rental.returnAt(RENTED_AT.minusDays(1)))
                    .withMessageContaining("앞설 수 없습니다");
        }
    }

    @Nested
    @DisplayName("복원")
    class Restoration {

        @Test
        @DisplayName("반납된 상태를 그대로 복원한다")
        void restoresReturnedState() {
            LocalDateTime returnedAt = RENTED_AT.plusDays(5);

            Rental restored = Rental.restore(100, 1, 2, 3, RENTED_AT, returnedAt);

            // 생성자로는 이 상태를 만들 수 없다. 복원 전용 경로가 필요한 이유다.
            assertThat(restored.id()).isEqualTo(100);
            assertThat(restored.isReturned()).isTrue();
            assertThat(restored.returnDate()).isEqualTo(returnedAt);
        }
    }

    @Nested
    @DisplayName("기간과 연체")
    class Duration {

        @Test
        @DisplayName("반납 전이면 기준 시각까지의 일수를 센다")
        void countsDaysUntilNow() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);

            assertThat(rental.rentedDays(RENTED_AT.plusDays(4))).isEqualTo(4);
        }

        @Test
        @DisplayName("반납했으면 반납일까지의 일수를 센다")
        void countsDaysUntilReturn() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);
            rental.returnAt(RENTED_AT.plusDays(2));

            // 기준 시각을 뒤로 밀어도 이미 반납했으므로 값이 변하지 않는다.
            assertThat(rental.rentedDays(RENTED_AT.plusDays(10))).isEqualTo(2);
        }

        @Test
        @DisplayName("허용 일수를 넘기고 미반납이면 연체다")
        void detectsOverdue() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);

            assertThat(rental.isOverdue(3, RENTED_AT.plusDays(5))).isTrue();
            assertThat(rental.isOverdue(3, RENTED_AT.plusDays(2))).isFalse();
        }

        @Test
        @DisplayName("반납했으면 아무리 늦어도 연체가 아니다")
        void returnedIsNeverOverdue() {
            Rental rental = new Rental(1, 2, 3, RENTED_AT);
            rental.returnAt(RENTED_AT.plusDays(30));

            assertThat(rental.isOverdue(3, RENTED_AT.plusDays(100))).isFalse();
        }
    }
}
