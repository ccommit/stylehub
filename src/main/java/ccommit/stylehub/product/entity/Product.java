package ccommit.stylehub.product.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - fix: isOnSale 추가 (정지·미승인 스토어 상품의 판매 여부 판단을 엔티티에 둠)
 * @modified 2026/09/17 by WonJin - fix: 커서 페이징 쿼리가 전제하는 인덱스를 @Table(indexes) 로 선언 (운영 반영 DDL 은 scripts/db/create-cursor-paging-indexes.sql)
 *
 * <p>
 * 스토어에 등록된 상품 정보를 관리한다.
 * MainCategory와 SubCategory로 2단계 카테고리를 구성한다.
 * </p>
 */

@Entity
@Table(name = "products", indexes = {
        // ProductQueryRepository 커서 페이징(필터 AND product_id < :cursor ORDER BY product_id DESC LIMIT n)이 전제하는 인덱스다.
        // 운영 DB 는 ddl-auto=validate 라 이 선언으로 만들어지지 않는다. 운영 반영 DDL: scripts/db/create-cursor-paging-indexes.sql

        // 카테고리 필터 + 커서 범위 + 정렬을 인덱스 순서로 처리한다.
        // mainCategory 만 필터하면 sub_category 가 사이에 있어 정렬까지 인덱스로 해결되지는 않는다.
        @Index(name = "idx_products_main_sub_product_id", columnList = "main_category, sub_category, product_id"),
        // 스토어별 목록. InnoDB 보조 인덱스는 PK 를 포함하지만, 정렬 전제를 코드에 드러내려고 product_id 까지 명시한다.
        @Index(name = "idx_products_user_product_id", columnList = "user_id, product_id")
})
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "main_category", nullable = false)
    private MainCategory mainCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_category", nullable = false)
    private SubCategory subCategory;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer price;

    @Column(name = "image_url", nullable = false, length = 300)
    private String imageUrl;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProductOption> options = new ArrayList<>();

    @Column(name = "like_count")
    @Builder.Default
    private Integer likeCount = 0;

    // 판매 가능 여부는 상품 자체가 아니라 스토어의 입점 상태가 결정한다. 정지·미승인 스토어의 상품은 노출·주문하지 않는다.
    public boolean isOnSale() {
        return user.getStoreStatus() == StoreStatus.APPROVED;
    }

    public static Product create(User user, String name, MainCategory mainCategory, SubCategory subCategory,
                                 String description, Integer price, String imageUrl) {
        return Product.builder()
                .user(user)
                .name(name)
                .mainCategory(mainCategory)
                .subCategory(subCategory)
                .description(description)
                .price(price)
                .imageUrl(imageUrl)
                .build();
    }
}
