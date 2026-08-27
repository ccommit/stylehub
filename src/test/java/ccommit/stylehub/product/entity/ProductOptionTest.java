package ccommit.stylehub.product.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * ProductOption의 재고 증감 도메인 로직과 Product/User 위임 메서드를 검증하는 단위테스트이다.
 * 외부 의존성 없이 순수 객체만으로 검증하므로 Mock이 필요 없다.
 * </p>
 */
class ProductOptionTest {

    private ProductOption optionWithStock(int stockQuantity) {
        User store = User.builder()
                .userId(1L)
                .storeName("스타일허브 스토어")
                .build();
        Product product = Product.builder()
                .productId(1L)
                .user(store)
                .name("반팔 티셔츠")
                .price(19000)
                .build();
        return ProductOption.builder()
                .productOptionId(1L)
                .product(product)
                .color("black")
                .size("M")
                .stockQuantity(stockQuantity)
                .maxPointAmount(1000)
                .build();
    }

    @Nested
    @DisplayName("decreaseStock")
    class DecreaseStock {

        @Test
        @DisplayName("재고보다 적은 수량을 요청하면 정상적으로 차감된다")
        void 재고가_충분하면_차감에_성공한다() {
            // given
            ProductOption option = optionWithStock(10);

            // when
            option.decreaseStock(3);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("재고와 정확히 같은 수량을 요청하면 재고가 0이 된다 (경계값)")
        void 재고와_정확히_같은_수량이면_0이_된다() {
            // given
            ProductOption option = optionWithStock(5);

            // when
            option.decreaseStock(5);

            // then
            assertThat(option.getStockQuantity()).isZero();
        }

        @Test
        @DisplayName("재고보다 많은 수량을 요청하면 INSUFFICIENT_STOCK 예외가 발생하고 재고는 변하지 않는다")
        void 재고보다_많으면_예외가_발생한다() {
            // given
            ProductOption option = optionWithStock(5);

            // when & then
            assertThatThrownBy(() -> option.decreaseStock(6))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(option.getStockQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("재고가 0일 때 1개라도 요청하면 예외가 발생한다")
        void 재고가_0이면_1개_요청도_예외가_발생한다() {
            // given
            ProductOption option = optionWithStock(0);

            // when & then
            assertThatThrownBy(() -> option.decreaseStock(1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }
    }

    @Nested
    @DisplayName("increaseStock")
    class IncreaseStock {

        @Test
        @DisplayName("재고가 요청 수량만큼 증가한다")
        void 재고가_증가한다() {
            // given
            ProductOption option = optionWithStock(3);

            // when
            option.increaseStock(7);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("updateStockQuantity")
    class UpdateStockQuantity {

        @Test
        @DisplayName("재고를 지정한 값으로 절대 설정한다")
        void 재고를_지정값으로_설정한다() {
            // given
            ProductOption option = optionWithStock(100);

            // when
            option.updateStockQuantity(3);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Product/User 위임 메서드")
    class DelegateMethods {

        @Test
        @DisplayName("getProductName은 연관된 Product의 이름을 반환한다")
        void 상품명을_위임받는다() {
            assertThat(optionWithStock(1).getProductName()).isEqualTo("반팔 티셔츠");
        }

        @Test
        @DisplayName("getProductPrice는 연관된 Product의 가격을 반환한다")
        void 상품가격을_위임받는다() {
            assertThat(optionWithStock(1).getProductPrice()).isEqualTo(19000);
        }

        @Test
        @DisplayName("getStoreId는 연관된 Product의 소유자(User) ID를 반환한다")
        void 스토어_ID를_위임받는다() {
            assertThat(optionWithStock(1).getStoreId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("getStoreName은 연관된 Product 소유자(User)의 스토어명을 반환한다")
        void 스토어명을_위임받는다() {
            assertThat(optionWithStock(1).getStoreName()).isEqualTo("스타일허브 스토어");
        }
    }
}
