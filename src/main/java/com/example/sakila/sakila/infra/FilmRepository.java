package com.example.sakila.sakila.infra;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Sakila 예제 리포지토리. 새 프로젝트에서는 {@code sakila} 패키지를 통째로 지운다.
 * DDD 참조 구현은 {@code rental} 패키지를 보라.
 */
public interface FilmRepository extends JpaRepository<FilmEntity, Integer> {

    Page<FilmEntity> findByTitleContainingIgnoreCase(String keyword, Pageable pageable);

    List<FilmEntity> findByRating(FilmRating rating);

    /**
     * N+1 을 피하려면 연관을 함께 읽어야 한다.
     * {@code default_batch_fetch_size} 로도 완화되지만 명시적 fetch join 이 확실하다.
     */
    @Query("select f from FilmEntity f join fetch f.language where f.id = :id")
    Optional<FilmEntity> findByIdWithLanguage(@Param("id") Integer id);
}
