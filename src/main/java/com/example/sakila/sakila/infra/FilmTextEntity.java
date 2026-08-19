package com.example.sakila.sakila.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FULLTEXT 검색 전용 테이블.
 *
 * <p>원본 Sakila 는 film 테이블의 트리거로 이 테이블을 동기화하지만,
 * 그 트리거는 JPA 쓰기 작업에 예상치 못한 부작용을 만들어 마이그레이션에서 제외했다.
 * film 을 수정하는 애플리케이션이라면 동기화 책임을 애플리케이션 쪽에 두어야 한다.
 *
 * <p>PK 에 {@code @GeneratedValue} 가 없다. film_id 를 그대로 받아 쓰기 때문이다.
 */
@Getter
@Entity
@Table(name = "film_text")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilmTextEntity {

    @Id
    @Column(name = "film_id")
    private Integer filmId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;
}
