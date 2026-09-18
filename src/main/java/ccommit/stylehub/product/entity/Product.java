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
 *
 * <p>
 * 스토어에 등록된 상품 정보를 관리한다.
 * MainCategory와 SubCategory로 2단계 카테고리를 구성한다.
 * </p>
 */

@Entity
@Table(name = "products")
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
