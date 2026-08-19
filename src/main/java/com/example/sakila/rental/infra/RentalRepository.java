package com.example.sakila.rental.infra;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data 리포지토리.
 *
 * <p><b>package-private 이다.</b> 이 인터페이스가 infra 패키지 밖으로 나갈 수 없도록
 * 컴파일러가 강제한다. ArchUnit 규칙 이전의 1차 방어선이며,
 * 서비스가 실수로 리포지토리를 직접 주입받는 일을 원천 차단한다.
 */
interface RentalRepository extends JpaRepository<RentalEntity, Integer>, RentalQueryRepository {

    Page<RentalEntity> findByReturnDateIsNullOrderByRentalDateAsc(Pageable pageable);

    boolean existsByInventoryIdAndReturnDateIsNull(Integer inventoryId);
}
