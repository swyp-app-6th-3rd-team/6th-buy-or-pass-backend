package com.example.sakila.sakila.infra;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;

/**
 * film.special_features — MySQL {@code SET('Trailers','Commentaries',...)}.
 *
 * <p>SET 은 한 컬럼에 여러 값을 쉼표로 이어 저장하는 MySQL 고유 타입이다.
 * JDBC 는 이를 문자열로 주므로 {@code List<String>} 으로 풀어준다.
 *
 * <p>autoApply 를 켜지 않았다. {@code List<String>} 필드 전부에 적용되면
 * 의도치 않은 곳까지 변환되므로 해당 필드에만 명시적으로 붙인다.
 */
@Converter
public class SpecialFeaturesConverter implements AttributeConverter<List<String>, String> {

    private static final String DELIMITER = ",";

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return String.join(DELIMITER, attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbData.split(DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
