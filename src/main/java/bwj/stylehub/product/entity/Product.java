package bwj.stylehub.product.entity;

import bwj.stylehub.common.entity.BaseEntity;
import bwj.stylehub.product.enums.MainCategory;
import bwj.stylehub.product.enums.SubCategory;
import bwj.stylehub.store.entity.Store;
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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

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
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

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

    @Column(name = "like_count")
    @Builder.Default
    private Integer likeCount = 0;

    public static Product create(Store store, String name, MainCategory mainCategory, SubCategory subCategory,
                                 String description, Integer price, String imageUrl) {
        return Product.builder()
                .store(store)
                .name(name)
                .mainCategory(mainCategory)
                .subCategory(subCategory)
                .description(description)
                .price(price)
                .imageUrl(imageUrl)
                .build();
    }
}
