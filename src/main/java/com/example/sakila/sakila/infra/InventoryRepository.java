package com.example.sakila.sakila.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InventoryRepository extends JpaRepository<InventoryEntity, Integer> {

    List<InventoryEntity> findByFilmIdAndStoreId(Integer filmId, Integer storeId);

    /**
     * 특정 매장에서 대여 가능한 재고. 미반납 대여가 없는 것만 고른다.
     */
    @Query("""
            select i from InventoryEntity i
            where i.film.id = :filmId and i.store.id = :storeId
              and not exists (
                select 1 from RentalEntity r
                where r.inventoryId = i.id and r.returnDate is null)
            """)
    List<InventoryEntity> findAvailable(@Param("filmId") Integer filmId, @Param("storeId") Integer storeId);
}
