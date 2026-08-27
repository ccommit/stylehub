package ccommit.stylehub.order.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderItem;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderItemRepository;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * DeliveryValidator의 배송 상태 변경 검증(스토어 소유권 → 주문-스토어 매칭 → 상태 전이 규칙)을 검증하는 단위테스트이다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DeliveryValidatorTest {

    @Mock
    private UserPort userPort;

    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private DeliveryValidator deliveryValidator;

    private OrderItem itemOfStore(Long storeId) {
        User store = User.builder().userId(storeId).storeName("스토어").build();
        Product product = Product.builder().productId(1L).user(store).name("상품").price(1000).build();
        ProductOption option = ProductOption.create(product, "black", "M", 10, 100);
        return OrderItem.create(option, null, 1, 1000, null);
    }

    @Test
    @DisplayName("소유권, 주문-스토어 매칭, 상태 전이가 모두 유효하면 예외가 발생하지 않는다")
    void 모두_유효하면_통과한다() {
        // given
        UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 100L, OrderStatus.SHIPPING);
        Order order = Order.builder().orderStatus(OrderStatus.PREPARING).build();
        when(orderItemRepository.findByOrderIdWithDetails(100L)).thenReturn(List.of(itemOfStore(10L)));

        // when & then
        assertThatCode(() -> deliveryValidator.validate(request, order)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("스토어 소유권 검증에 실패하면 예외가 전파되고 이후 검증은 수행하지 않는다")
    void 소유권_검증_실패시_예외가_전파된다() {
        // given
        UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 100L, OrderStatus.SHIPPING);
        Order order = Order.builder().orderStatus(OrderStatus.PREPARING).build();
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED_STORE_ACCESS))
                .when(userPort).validateApprovedStoreOwner(1L, 10L);

        // when & then
        assertThatThrownBy(() -> deliveryValidator.validate(request, order))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED_STORE_ACCESS);
    }

    @Test
    @DisplayName("주문에 해당 스토어의 상품이 없으면 UNAUTHORIZED_DELIVERY_ACCESS 예외가 발생한다")
    void 스토어_상품이_주문에_없으면_예외() {
        // given
        UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 100L, OrderStatus.SHIPPING);
        Order order = Order.builder().orderStatus(OrderStatus.PREPARING).build();
        when(orderItemRepository.findByOrderIdWithDetails(100L)).thenReturn(List.of(itemOfStore(99L)));

        // when & then
        assertThatThrownBy(() -> deliveryValidator.validate(request, order))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED_DELIVERY_ACCESS);
    }

    @Nested
    @DisplayName("상태 전이 규칙")
    class Transition {

        @ParameterizedTest
        @DisplayName("PREPARING→SHIPPING, SHIPPING→DELIVERED만 유효하다")
        @CsvSource({
                "PREPARING, SHIPPING",
                "SHIPPING, DELIVERED"
        })
        void 유효한_전이는_통과한다(OrderStatus current, OrderStatus next) {
            // given
            UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 100L, next);
            Order order = Order.builder().orderStatus(current).build();
            when(orderItemRepository.findByOrderIdWithDetails(100L)).thenReturn(List.of(itemOfStore(10L)));

            // when & then
            assertThatCode(() -> deliveryValidator.validate(request, order)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @DisplayName("정의되지 않은 전이는 INVALID_DELIVERY_STATUS 예외가 발생한다")
        @CsvSource({
                "PREPARING, DELIVERED",
                "SHIPPING, PREPARING",
                "DELIVERED, SHIPPING",
                "PENDING, SHIPPING"
        })
        void 잘못된_전이는_예외가_발생한다(OrderStatus current, OrderStatus next) {
            // given
            UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 100L, next);
            Order order = Order.builder().orderStatus(current).build();
            when(orderItemRepository.findByOrderIdWithDetails(100L)).thenReturn(List.of(itemOfStore(10L)));

            // when & then
            assertThatThrownBy(() -> deliveryValidator.validate(request, order))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_DELIVERY_STATUS);
        }
    }
}
