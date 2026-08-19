package com.example.sakila.sakila.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * film ↔ actor 다대다 조인 테이블. 복합 PK 를 {@code @EmbeddedId} 로 매핑한다.
 *
 * <p>{@code @MapsId} 는 연관 엔티티의 식별자를 복합키 필드에 그대로 쓰겠다는 선언이다.
 * 이게 없으면 같은 컬럼을 두 번 매핑하게 되어 기동 시 오류가 난다.
 */
@Getter
@Entity
@Table(name = "film_actor")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FilmActorEntity {

    @EmbeddedId
    private FilmActorId id;

    @MapsId("actorId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private ActorEntity actor;

    @MapsId("filmId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "film_id", nullable = false)
    private FilmEntity film;

    @Column(name = "last_update", insertable = false, updatable = false)
    private LocalDateTime lastUpdate;

    /**
     * 복합키. {@code equals}/{@code hashCode} 가 반드시 있어야 하며
     * {@link Serializable} 이어야 한다 — JPA 명세 요구사항이다.
     */
    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class FilmActorId implements Serializable {

        @Column(name = "actor_id")
        private Integer actorId;

        @Column(name = "film_id")
        private Integer filmId;

        public FilmActorId(Integer actorId, Integer filmId) {
            this.actorId = actorId;
            this.filmId = filmId;
        }
    }
}
