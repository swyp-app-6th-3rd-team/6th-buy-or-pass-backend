package com.example.sakila.rental.infra;

import com.example.sakila.rental.domain.Rental;
import com.example.sakila.rental.domain.RentalSearchCondition;
import com.example.sakila.rental.domain.RentalStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link RentalStore} 의 JPA 구현.
 *
 * <p>도메인 ↔ 엔티티 변환이 일어나는 유일한 지점이다.
 * 바깥(서비스)에는 도메인 객체만 나가고, 엔티티는 이 패키지를 벗어나지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JpaRentalStore implements RentalStore {

    private final RentalRepository repository;

    @Override
    public Rental save(Rental rental) {
        if (rental.id() == null) {
            return repository.save(RentalEntity.from(rental)).toDomain();
        }
        // 이미 존재하는 대여는 가변 상태만 반영한다.
        // 영속 상태의 엔티티를 수정하므로 더티 체킹으로 UPDATE 가 나간다.
        RentalEntity entity = repository.findById(rental.id())
                .orElseThrow(() -> new IllegalStateException("대여를 찾을 수 없습니다: rentalId=" + rental.id()));
        entity.applyState(rental);
        return entity.toDomain();
    }

    @Override
    public Optional<Rental> findById(Integer id) {
        return repository.findById(id).map(RentalEntity::toDomain);
    }

    @Override
    public Page<Rental> search(RentalSearchCondition condition, Pageable pageable) {
        return repository.search(condition, pageable).map(RentalEntity::toDomain);
    }

    @Override
    public Window<Rental> scroll(RentalSearchCondition condition, ScrollPosition position, int limit) {
        return repository.scroll(condition, position, limit).map(RentalEntity::toDomain);
    }

    @Override
    public Page<Rental> findOutstanding(Pageable pageable) {
        return repository.findByReturnDateIsNullOrderByRentalDateAsc(pageable).map(RentalEntity::toDomain);
    }

    @Override
    public boolean existsUnreturnedByInventoryId(Integer inventoryId) {
        return repository.existsByInventoryIdAndReturnDateIsNull(inventoryId);
    }
}
