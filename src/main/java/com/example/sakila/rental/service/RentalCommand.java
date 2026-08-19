package com.example.sakila.rental.service;

/**
 * 서비스 입력. 컨트롤러의 요청 DTO 와 분리해 웹 계층 변경이 서비스로 번지지 않게 한다.
 */
public final class RentalCommand {

    private RentalCommand() {
    }

    public record Rent(Integer inventoryId, Integer customerId, Integer staffId) {
    }
}
