package com.example.sakila.rental.infra;

import com.example.sakila.common.ResponseCode;
import com.example.sakila.error.ApiException;
import com.example.sakila.rental.domain.RentalSearchCondition;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.support.PageableExecutionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sakila.rental.infra.QRentalEntity.rentalEntity;

@RequiredArgsConstructor
class RentalQueryRepositoryImpl implements RentalQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<RentalEntity> search(RentalSearchCondition condition, Pageable pageable) {
        List<RentalEntity> content = baseQuery(condition)
                .orderBy(toOrderSpecifiers(pageable))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count 쿼리를 항상 날리지 않는다. 마지막 페이지이거나 첫 페이지가 덜 찼으면
        // 이미 총 건수를 알 수 있으므로 PageableExecutionUtils 가 생략한다.
        JPAQuery<Long> countQuery = queryFactory
                .select(rentalEntity.count())
                .from(rentalEntity)
                .where(toPredicates(condition));

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    /**
     * keyset(cursor) 스크롤.
     *
     * <p>정렬 키는 {@code (rentalDate, rentalId)} 다. rentalDate 만으로는 동률이 생기고
     * 동률 구간에서 행이 누락되거나 중복되므로, <b>고유한 PK 를 마지막 키로 덧붙여</b>
     * 전체 순서를 유일하게 만든다. 이건 keyset 페이징의 필수 조건이다.
     *
     * <p>{@code limit + 1} 건을 읽어 다음 조각 존재 여부를 판단한 뒤 초과분을 버린다.
     */
    @Override
    public Window<RentalEntity> scroll(RentalSearchCondition condition, ScrollPosition position, int limit) {
        BooleanExpression keysetPredicate = toKeysetPredicate(position);

        List<RentalEntity> rows = baseQuery(condition)
                .where(keysetPredicate)
                .orderBy(rentalEntity.rentalDate.asc(), rentalEntity.id.asc())
                .limit(limit + 1L)
                .fetch();

        boolean hasNext = rows.size() > limit;
        List<RentalEntity> content = hasNext ? new ArrayList<>(rows.subList(0, limit)) : rows;

        return Window.from(content, index -> keysetOf(content.get(index)), hasNext);
    }

    private JPAQuery<RentalEntity> baseQuery(RentalSearchCondition condition) {
        return queryFactory.selectFrom(rentalEntity).where(toPredicates(condition));
    }

    private BooleanExpression[] toPredicates(RentalSearchCondition condition) {
        return new BooleanExpression[]{
                customerIdEq(condition.customerId()),
                staffIdEq(condition.staffId()),
                inventoryIdEq(condition.inventoryId()),
                returnedEq(condition.returned()),
                rentedFrom(condition.rentedFrom()),
                rentedTo(condition.rentedTo())
        };
    }

    // null 을 반환하면 QueryDSL 이 해당 조건을 무시한다. 조건 조합이 늘어도 분기가 생기지 않는다.
    private BooleanExpression customerIdEq(Integer customerId) {
        return customerId == null ? null : rentalEntity.customerId.eq(customerId);
    }

    private BooleanExpression staffIdEq(Integer staffId) {
        return staffId == null ? null : rentalEntity.staffId.eq(staffId);
    }

    private BooleanExpression inventoryIdEq(Integer inventoryId) {
        return inventoryId == null ? null : rentalEntity.inventoryId.eq(inventoryId);
    }

    private BooleanExpression returnedEq(Boolean returned) {
        if (returned == null) {
            return null;
        }
        return returned ? rentalEntity.returnDate.isNotNull() : rentalEntity.returnDate.isNull();
    }

    private BooleanExpression rentedFrom(LocalDateTime from) {
        return from == null ? null : rentalEntity.rentalDate.goe(from);
    }

    private BooleanExpression rentedTo(LocalDateTime to) {
        return to == null ? null : rentalEntity.rentalDate.loe(to);
    }

    /**
     * {@code (rentalDate, rentalId) > (마지막 rentalDate, 마지막 rentalId)} 를 만든다.
     * 튜플 비교를 직접 쓰지 않고 풀어쓰는 이유는 MySQL 이 튜플 비교에서
     * 인덱스를 잘 타지 않는 경우가 있기 때문이다.
     */
    private BooleanExpression toKeysetPredicate(ScrollPosition position) {
        if (!(position instanceof KeysetScrollPosition keyset) || keyset.getKeys().isEmpty()) {
            return null;
        }
        Map<String, Object> keys = keyset.getKeys();
        LocalDateTime lastRentalDate;
        Integer lastId;
        try {
            lastRentalDate = asLocalDateTime(keys.get("rentalDate"));
            lastId = asInteger(keys.get("id"));
        } catch (RuntimeException e) {
            // 클라이언트가 조작했거나 예전 형식의 커서다. 서버 오류가 아니라 잘못된 요청이다.
            throw new ApiException(ResponseCode.INVALID_REQUEST, "커서 형식이 올바르지 않습니다.");
        }

        if (lastRentalDate == null || lastId == null) {
            return null;
        }
        return rentalEntity.rentalDate.gt(lastRentalDate)
                .or(rentalEntity.rentalDate.eq(lastRentalDate).and(rentalEntity.id.gt(lastId)));
    }

    /**
     * 커서는 JSON 을 거쳐 오므로 값의 타입이 보존되지 않는다.
     * 같은 프로세스 안에서 만든 위치면 {@link LocalDateTime} 이지만,
     * 클라이언트가 돌려준 커서를 복원하면 ISO-8601 문자열이다. 둘 다 받는다.
     */
    private LocalDateTime asLocalDateTime(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDateTime dateTime -> dateTime;
            case CharSequence text -> LocalDateTime.parse(text);
            default -> throw new IllegalArgumentException(
                    "커서의 rentalDate 형식을 해석할 수 없습니다: " + value.getClass());
        };
    }

    private Integer asInteger(Object value) {
        return switch (value) {
            case null -> null;
            case Number number -> number.intValue();
            case CharSequence text -> Integer.valueOf(text.toString());
            default -> throw new IllegalArgumentException(
                    "커서의 id 형식을 해석할 수 없습니다: " + value.getClass());
        };
    }

    private ScrollPosition keysetOf(RentalEntity entity) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("rentalDate", entity.getRentalDate());
        keys.put("id", entity.getId());
        return ScrollPosition.forward(keys);
    }

    /**
     * Pageable 의 Sort 를 QueryDSL OrderSpecifier 로 옮긴다.
     * 정렬 키가 없으면 대여일 내림차순을 기본으로 하고, 동률을 깨기 위해 PK 를 덧붙인다.
     */
    private OrderSpecifier<?>[] toOrderSpecifiers(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return new OrderSpecifier<?>[]{rentalEntity.rentalDate.desc(), rentalEntity.id.desc()};
        }
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();
        for (var order : pageable.getSort()) {
            OrderSpecifier<?> specifier = switch (order.getProperty()) {
                case "rentalDate" -> order.isAscending() ? rentalEntity.rentalDate.asc() : rentalEntity.rentalDate.desc();
                case "returnDate" -> order.isAscending() ? rentalEntity.returnDate.asc() : rentalEntity.returnDate.desc();
                case "id", "rentalId" -> order.isAscending() ? rentalEntity.id.asc() : rentalEntity.id.desc();
                // 허용 목록에 없는 필드는 무시한다. 클라이언트가 임의 컬럼으로 정렬해
                // 인덱스 없는 풀스캔을 유발하는 것을 막는다.
                default -> null;
            };
            if (specifier != null) {
                specifiers.add(specifier);
            }
        }
        if (specifiers.isEmpty()) {
            specifiers.add(rentalEntity.rentalDate.desc());
        }
        specifiers.add(rentalEntity.id.desc());
        return specifiers.toArray(OrderSpecifier<?>[]::new);
    }
}
