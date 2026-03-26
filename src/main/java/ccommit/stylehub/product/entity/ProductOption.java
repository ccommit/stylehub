package ccommit.stylehub.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/25 by WonJin - feat: 재고 수량 변경 메서드 추가
 *
 * <p>
 * 상품의 색상/사이즈별 옵션과 재고를 관리한다.
 * OrderItem이 이 옵션을 직접 참조하여 옵션 단위 주문을 지원한다.
 * </p>
 */

@Entity
@Table(name = "products_options")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_option_id")
    private Long productOptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(length = 20)
    private String color;

    @Column(length = 10)
    private String size;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Column(name = "max_point_amount")
    private Integer maxPointAmount;

    public void updateStockQuantity(Integer stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public static ProductOption create(Product product, String color, String size,
                                       Integer stockQuantity, Integer maxPointAmount) {
        return ProductOption.builder()
                .product(product)
                .color(color)
                .size(size)
                .stockQuantity(stockQuantity)
                .maxPointAmount(maxPointAmount)
                .build();
    }
}
