package com.example.sakila.sakila.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FilmTextRepository extends JpaRepository<FilmTextEntity, Integer> {

    /**
     * MySQL FULLTEXT 검색. JPQL 로는 표현할 수 없어 네이티브 쿼리를 쓴다.
     * film_text 는 InnoDB 이며 FULLTEXT 인덱스를 갖는다.
     */
    @Query(value = """
            select * from film_text
            where match(title, description) against (:keyword in natural language mode)
            """, nativeQuery = true)
    List<FilmTextEntity> searchFullText(@Param("keyword") String keyword);
}
