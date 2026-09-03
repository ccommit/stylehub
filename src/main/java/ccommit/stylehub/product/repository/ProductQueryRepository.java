package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.entity.QProduct;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.user.entity.QUser;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/24 by WonJin - perf: 엔티티 전체 조회 대신 Projections.constructor 로 8개 컬럼만 DTO 직접 투영 (31→8 컬럼, 과다 컬럼 투영 해소)
 *
 * <p>
 * QueryDSL 기반 상품 동적 조회를 담당한다.
 * 커서 기반 페이징과 다중 필터(스토어, 카테고리)를 지원한다.
 * 목록 조회에서 엔티티 전체를 가져오는 대신 ProductListResponse 로 직접 투영해 SELECT 컬럼 수를 최소화한다.
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {

    private final JPAQueryFactory queryFactory;

    // 스토어 필터만 적용 (카테고리 없이 조회)
    public List<ProductListResponse> findProductsWithCursor(Long cursor, Long storeId, int size) {
        return findProductsWithCursor(cursor, storeId, null, null, size);
    }

    public List<ProductListResponse> findProductsWithCursor(Long cursor, Long storeId,
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
                .select(Projections.constructor(ProductListResponse.class,
                        product.productId,
                        user.userId,            // storeId
                        user.storeName,         // storeName
                        product.name,
                        product.mainCategory,
                        product.subCategory,
                        product.price,
                        product.imageUrl))
                .from(product)
                .join(product.user, user)
                .where(builder)
                .orderBy(product.productId.desc())
                .limit(size)
                .fetch();
    }
}
