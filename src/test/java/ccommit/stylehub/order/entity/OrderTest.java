package ccommit.stylehub.order.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * Order 엔티티의 취소 전이 규칙을 검증하는 단위 테스트이다.
 * 결제 전 취소와 결제 후 취소(환불)가 허용하는 상태를 고정해, 결제 검증기와 규칙이 어긋나는 회귀를 막는다.
 * </p>
 */
class OrderTest {

    private Order orderWithStatus(OrderStatus status) {
        return Order.builder().orderStatus(status).build();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING", "DELIVERED"})
    @DisplayName("결제 완료·배송 준비·배송 완료 주문은 결제 후 취소할 수 있다")
    void cancelsPaidOrder(OrderStatus status) {
        Order order = orderWithStatus(status);

        assertThat(order.isCancelableAfterPayment()).isTrue();
        order.cancelPaid();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "SHIPPING", "CANCELLED"})
    @DisplayName("결제 대기·배송 중·취소된 주문은 결제 후 취소할 수 없다")
    void rejectsPaidCancel(OrderStatus status) {
        Order order = orderWithStatus(status);

        assertThat(order.isCancelableAfterPayment()).isFalse();
        assertThatThrownBy(order::cancelPaid)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS);
        assertThat(order.getOrderStatus()).isEqualTo(status);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = OrderStatus.class, names = {"PENDING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("결제 전 취소는 결제 대기(PENDING) 주문만 가능하다")
    void cancelsUnpaidOnlyWhenPending(OrderStatus status) {
        Order pending = orderWithStatus(OrderStatus.PENDING);
        pending.cancelUnpaid();
        assertThat(pending.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);

        Order other = orderWithStatus(status);
        assertThatThrownBy(other::cancelUnpaid)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS);
    }
}
