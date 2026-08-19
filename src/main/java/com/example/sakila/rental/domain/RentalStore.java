package com.example.sakila.rental.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.util.Optional;

/**
 * 대여 저장소 계약.
 *
 * <p><b>인터페이스는 도메인이, 구현은 인프라가</b> 소유한다.
 * 도메인은 "무엇이 필요한가"만 말하고, "어떻게 저장하는가"는 모른다.
 * 구현({@code JpaRentalStore})이 도메인을 향하므로 의존이 역전된다.
 * ArchitectureTest 가 이 배치를 강제한다.
 *
 * <p>페이징은 Spring Data 의 {@link Pageable}·{@link Page}·{@link Window} 를 그대로 쓴다.
 * 별도 래퍼를 두지 않는 대신, 컨트롤러가 이 타입들을 그대로 응답으로
 * 내보내지 못하게 막는다({@code PageResponse}/{@code ScrollResponse} 로 변환).
 */
public interface RentalStore {

    Rental save(Rental rental);

    Optional<Rental> findById(Integer id);

    /**
     * 번호 기반 페이징. 총 건수와 페이지 수가 필요할 때 쓴다.
     */
    Page<Rental> search(RentalSearchCondition condition, Pageable pageable);

    /**
     * keyset 기반 스크롤. 무한 스크롤 UI 에 쓴다.
     * offset 을 쓰지 않으므로 뒤쪽 조각에서도 성능이 일정하다.
     */
    Window<Rental> scroll(RentalSearchCondition condition, ScrollPosition position, int limit);

    /**
     * 미반납 대여 중 가장 오래된 것부터.
     */
    Page<Rental> findOutstanding(Pageable pageable);

    boolean existsUnreturnedByInventoryId(Integer inventoryId);
}
