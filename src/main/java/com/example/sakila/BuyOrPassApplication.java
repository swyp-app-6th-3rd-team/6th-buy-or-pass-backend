package com.example.sakila;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 애플리케이션 진입점.
 *
 * <p><b>패키지는 아직 {@code com.example.sakila} 다.</b> 클래스 이름만 템플릿 잔재를 걷어냈고
 * 패키지 이동은 하지 않았다 — 56개 소스와 로깅 레벨 키({@code application.yml} 의
 * {@code com.example.sakila}), {@code ArchitectureTest} 의 {@code BASE} 상수처럼
 * 리팩터링 도구가 따라오지 않는 문자열 참조까지 걸려 있어 범위가 다르기 때문이다.
 * 패키지까지 정리할 때 이 주석을 지운다.
 */
@SpringBootApplication
public class BuyOrPassApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuyOrPassApplication.class, args);
    }
}
