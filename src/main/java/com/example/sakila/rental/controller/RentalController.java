package com.example.sakila.rental.controller;

import com.example.sakila.common.ApiResponse;
import com.example.sakila.common.CursorCodec;
import com.example.sakila.common.PageResponse;
import com.example.sakila.common.ResponseCode;
import com.example.sakila.common.ScrollResponse;
import com.example.sakila.rental.controller.dto.RentalDtos.RentRequest;
import com.example.sakila.rental.controller.dto.RentalDtos.RentalResponse;
import com.example.sakila.rental.domain.Rental;
import com.example.sakila.rental.domain.RentalSearchCondition;
import com.example.sakila.rental.service.RentalCommand;
import com.example.sakila.rental.service.RentalQueryService;
import com.example.sakila.rental.service.RentalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 대여 API — DDD 참조 구현의 웹 진입점.
 *
 * <p>페이징 응답은 Spring 의 {@code Page}/{@code Window} 를 그대로 내보내지 않고
 * {@code PageResponse}/{@code ScrollResponse} 로 변환한다. 내부 구조가 API 계약이
 * 되는 것을 막기 위해서다. ArchitectureTest 가 이를 강제한다.
 */
@Tag(name = "Rental", description = "대여 (DDD 참조 구현)")
@RestController
@RequestMapping("/api/rentals")
@RequiredArgsConstructor
public class RentalController {

    private final RentalService rentalService;
    private final RentalQueryService rentalQueryService;

    @Operation(summary = "대여 시작")
    @PostMapping
    public ResponseEntity<ApiResponse<RentalResponse>> rent(@Valid @RequestBody RentRequest request) {
        Rental rental = rentalService.rent(
                new RentalCommand.Rent(request.inventoryId(), request.customerId(), request.staffId()));
        return ResponseEntity
                .status(ResponseCode.CREATED.status())
                .body(ApiResponse.of(ResponseCode.CREATED, RentalResponse.from(rental)));
    }

    @Operation(summary = "반납")
    @PostMapping("/{rentalId}/return")
    public ApiResponse<RentalResponse> returnRental(@PathVariable Integer rentalId) {
        return ApiResponse.success(RentalResponse.from(rentalService.returnRental(rentalId)));
    }

    @Operation(summary = "단건 조회")
    @GetMapping("/{rentalId}")
    public ApiResponse<RentalResponse> get(@PathVariable Integer rentalId) {
        return ApiResponse.success(RentalResponse.from(rentalQueryService.getById(rentalId)));
    }

    @Operation(summary = "목록 조회 (번호 페이징)",
            description = "총 건수와 페이지 수가 필요한 화면에 쓴다. 뒤쪽 페이지일수록 느려진다.")
    @GetMapping
    public ApiResponse<PageResponse<RentalResponse>> search(
            @RequestParam(required = false) Integer customerId,
            @RequestParam(required = false) Integer staffId,
            @RequestParam(required = false) Integer inventoryId,
            @RequestParam(required = false) Boolean returned,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime rentedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime rentedTo,
            @PageableDefault(size = 20, sort = "rentalDate", direction = Sort.Direction.DESC) Pageable pageable) {

        RentalSearchCondition condition =
                new RentalSearchCondition(customerId, staffId, inventoryId, returned, rentedFrom, rentedTo);
        Page<Rental> page = rentalQueryService.search(condition, pageable);
        return ApiResponse.success(PageResponse.of(page, RentalResponse::from));
    }

    @Operation(summary = "목록 조회 (무한 스크롤)",
            description = "cursor 를 비우면 첫 조각. 응답의 nextCursor 를 그대로 다음 요청에 실어 보낸다. "
                    + "offset 을 쓰지 않아 뒤쪽 조각에서도 성능이 일정하다.")
    @GetMapping("/scroll")
    public ApiResponse<ScrollResponse<RentalResponse>> scroll(
            @RequestParam(required = false) Integer customerId,
            @RequestParam(required = false) Boolean returned,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        RentalSearchCondition condition =
                new RentalSearchCondition(customerId, null, null, returned, null, null);
        Window<Rental> window =
                rentalQueryService.scroll(condition, CursorCodec.decode(cursor), limit);
        return ApiResponse.success(ScrollResponse.of(window, RentalResponse::from));
    }

    @Operation(summary = "미반납 목록", description = "오래된 대여부터")
    @GetMapping("/outstanding")
    public ApiResponse<PageResponse<RentalResponse>> outstanding(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Rental> result = rentalQueryService.findOutstanding(PageRequest.of(page, size));
        return ApiResponse.success(PageResponse.of(result, RentalResponse::from));
    }
}
