package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.QProduct;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.user.entity.QUser;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * QueryDSL 기반 상품 동적 조회를 담당한다.
 * 커서 기반 페이징과 다중 필터(스토어, 카테고리)를 지원한다.
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {

    private final JPAQueryFactory queryFactory;

    // 스토어 필터만 적용 (카테고리 없이 조회)
    public List<Product> findProductsWithCursor(Long cursor, Long storeId, int size) {
        return findProductsWithCursor(cursor, storeId, null, null, size);
    }

    public List<Product> findProductsWithCursor(Long cursor, Long storeId,
                                                 MainCategory mainCategory,
                                                 SubCategory subCategory, int size) {
        QProduct product = QProduct.product;
        QUser user = QUser.user;

        BooleanBuilder builder = new BooleanBuilder();

        if (cursor != null) {
            builder.and(product.productId.lt(cursor));
        }
        if (storeId != null) {
            builder.and(product.user.userId.eq(storeId));
        }
        if (mainCategory != null) {
            builder.and(product.mainCategory.eq(mainCategory));
        }
        if (subCategory != null) {
            builder.and(product.subCategory.eq(subCategory));
        }

        return queryFactory
                .selectFrom(product)
                .join(product.user, user).fetchJoin()
                .where(builder)
                .orderBy(product.productId.desc())
                .limit(size)
                .fetch();
    }
}
