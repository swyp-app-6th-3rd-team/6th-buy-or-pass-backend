package com.example.sakila.rental.controller.dto;

import com.example.sakila.rental.domain.Rental;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public final class RentalDtos {

    private RentalDtos() {
    }

    @Schema(description = "대여 요청")
    public record RentRequest(
            @Schema(description = "재고 ID", example = "1")
            @NotNull @Positive Integer inventoryId,

            @Schema(description = "고객 ID", example = "1")
            @NotNull @Positive Integer customerId,

            @Schema(description = "직원 ID", example = "1")
            @NotNull @Positive Integer staffId) {
    }

    @Schema(description = "대여 응답")
    public record RentalResponse(
            Integer rentalId,
            Integer inventoryId,
            Integer customerId,
            Integer staffId,
            LocalDateTime rentalDate,
            LocalDateTime returnDate,
            boolean returned) {

        public static RentalResponse from(Rental rental) {
            return new RentalResponse(
                    rental.id(),
                    rental.inventoryId(),
                    rental.customerId(),
                    rental.staffId(),
                    rental.rentalDate(),
                    rental.returnDate(),
                    rental.isReturned());
        }
    }
}
