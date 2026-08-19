package com.example.sakila.sakila.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sakila film 테이블.
 *
 * <p>이 패키지의 엔티티들은 <b>스키마 커버리지를 위한 얇은 매핑</b>이다.
 * 도메인 모델·Store·매퍼가 없다. DDD 참조 구현은 {@code rental} 패키지를 보라.
 */
@Getter
@Entity
@Table(name = "film")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilmEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "film_id")
    private Integer id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * MySQL 의 {@code YEAR} 타입. Java 에 대응 타입이 없어 Integer 로 받는다.
     * columnDefinition 을 명시해야 ddl-auto=validate 가 통과한다.
     */
    @Column(name = "release_year", columnDefinition = "year")
    private Integer releaseYear;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "language_id", nullable = false)
    private LanguageEntity language;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_language_id")
    private LanguageEntity originalLanguage;

    /** tinyint unsigned. 실제 값은 3~7 이라 Byte 로 충분하다. */
    @Column(name = "rental_duration", nullable = false)
    private Byte rentalDuration;

    @Column(name = "rental_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal rentalRate;

    /** smallint unsigned. Java 에 unsigned 가 없어 Short 로 받는다(최대 32767 로 충분). */
    @Column(name = "length")
    private Short length;

    @Column(name = "replacement_cost", nullable = false, precision = 5, scale = 2)
    private BigDecimal replacementCost;

    @Column(name = "rating", columnDefinition = "enum('G','PG','PG-13','R','NC-17')")
    private FilmRating rating;

    @Convert(converter = SpecialFeaturesConverter.class)
    @Column(name = "special_features",
            columnDefinition = "set('Trailers','Commentaries','Deleted Scenes','Behind the Scenes')")
    private List<String> specialFeatures;

    @Column(name = "last_update", insertable = false, updatable = false)
    private LocalDateTime lastUpdate;
}
