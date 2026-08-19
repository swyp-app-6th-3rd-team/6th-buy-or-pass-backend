package com.example.sakila.sakila.infra;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

/**
 * film.rating — MySQL enum('G','PG','PG-13','R','NC-17').
 *
 * <p>{@code PG-13}, {@code NC-17} 에 하이픈이 있어 Java enum 상수명으로 쓸 수 없다.
 * {@code @Enumerated(STRING)} 은 상수명을 그대로 저장하므로 이 값들을 매핑하지 못한다.
 * 그래서 DB 표현({@code dbValue})을 따로 들고 컨버터로 오간다.
 */
public enum FilmRating {

    G("G"),
    PG("PG"),
    PG_13("PG-13"),
    R("R"),
    NC_17("NC-17");

    private final String dbValue;

    FilmRating(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static FilmRating fromDbValue(String value) {
        return Arrays.stream(values())
                .filter(r -> r.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 등급입니다: " + value));
    }

    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<FilmRating, String> {

        @Override
        public String convertToDatabaseColumn(FilmRating attribute) {
            return attribute == null ? null : attribute.dbValue();
        }

        @Override
        public FilmRating convertToEntityAttribute(String dbData) {
            return dbData == null ? null : FilmRating.fromDbValue(dbData);
        }
    }
}
