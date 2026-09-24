package ccommit.stylehub.order.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderDetail;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.user.port.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 배송 상태 변경 검증 규칙(상태 전이, 주문 상품 소유 스토어)을 검증하는 단위 테스트이다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryValidatorTest {

    private static final Long STORE_ID = 10L;
    private static final Long ORDER_ID = 1L;

    @Mock
    private UserPort userPort;

    @Mock
    private OrderDetailRepository orderDetailRepository;

    @InjectMocks
    private DeliveryValidator deliveryValidator;

    private OrderDetail detailOwnedBy(Long storeId) {
        ProductOption option = mock(ProductOption.class);
        given(option.getStoreId()).willReturn(storeId);
        OrderDetail detail = mock(OrderDetail.class);
        given(detail.getProductOption()).willReturn(option);
        return detail;
    }

    private UpdateDeliveryStatusRequest request(OrderStatus next) {
        return new UpdateDeliveryStatusRequest(STORE_ID, STORE_ID, ORDER_ID, next);
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({"PAID, PREPARING", "PREPARING, SHIPPING", "SHIPPING, DELIVERED"})
    @DisplayName("결제 완료부터 배송 완료까지 한 단계씩 진행하는 전이는 허용된다")
    void allowsForwardTransitions(OrderStatus current, OrderStatus next) {
        OrderDetail owned = detailOwnedBy(STORE_ID);
        given(orderDetailRepository.findByOrderIdWithDetails(ORDER_ID)).willReturn(List.of(owned));
        Order order = Order.builder().orderStatus(current).build();

        assertThatCode(() -> deliveryValidator.validate(request(next), order)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({"PENDING, PREPARING", "PAID, SHIPPING", "PREPARING, DELIVERED", "DELIVERED, SHIPPING", "CANCELLED, PREPARING"})
    @DisplayName("결제 전·단계 건너뛰기·역행·취소된 주문의 전이는 INVALID_DELIVERY_STATUS 로 거절된다")
    void rejectsInvalidTransitions(OrderStatus current, OrderStatus next) {
        OrderDetail owned = detailOwnedBy(STORE_ID);
        given(orderDetailRepository.findByOrderIdWithDetails(ORDER_ID)).willReturn(List.of(owned));
        Order order = Order.builder().orderStatus(current).build();

        assertThatThrownBy(() -> deliveryValidator.validate(request(next), order))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_DELIVERY_STATUS);
    }

    @Test
    @DisplayName("주문에 다른 스토어 상품이 하나라도 섞여 있으면 UNAUTHORIZED_DELIVERY_ACCESS 로 거절된다")
    void rejectsWhenOrderContainsOtherStoreItems() {
        OrderDetail owned = detailOwnedBy(STORE_ID);
        OrderDetail otherStore = detailOwnedBy(99L);
        given(orderDetailRepository.findByOrderIdWithDetails(ORDER_ID)).willReturn(List.of(owned, otherStore));
        Order order = Order.builder().orderStatus(OrderStatus.PAID).build();

        assertThatThrownBy(() -> deliveryValidator.validate(request(OrderStatus.PREPARING), order))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED_DELIVERY_ACCESS);
    }
}
