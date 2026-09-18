package ccommit.stylehub.order.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 * @modified 2026/09/17 by WonJin - test: 주문번호 형식·길이·중복 없음 검증 추가
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

    @Test
    @DisplayName("주문번호는 ORD-날짜-32자리 16진수 형식이고 토스·컬럼 길이 제한(64자)을 넘지 않는다")
    void generatesPgOrderIdWithinTossRules() {
        String pgOrderId = Order.create(User.builder().build(), Address.builder().build()).getPgOrderId();

        assertThat(pgOrderId).matches("^ORD-\\d{8}-[0-9a-f]{32}$");
        assertThat(pgOrderId.length()).isBetween(6, 64);
    }

    @Test
    @DisplayName("주문번호를 10만 번 만들어도 중복되지 않는다")
    void generatesDistinctPgOrderIds() {
        int count = 100_000;
        Set<String> ids = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            ids.add(Order.create(User.builder().build(), Address.builder().build()).getPgOrderId());
        }

        assertThat(ids).hasSize(count);
    }
}
