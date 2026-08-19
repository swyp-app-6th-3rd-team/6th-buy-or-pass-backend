package com.example.sakila.sakila.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Sakila 예제 리포지토리. 새 프로젝트에서는 {@code sakila} 패키지를 통째로 지운다.
 * DDD 참조 구현은 {@code rental} 패키지를 보라.
 */
public interface LanguageRepository extends JpaRepository<LanguageEntity, Integer> {

}
