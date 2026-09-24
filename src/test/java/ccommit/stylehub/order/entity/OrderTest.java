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
 * @modified 2026/09/17 by WonJin - test: 포인트 사용 규칙(음수 거절, 상품 금액 1만원 미만 거절, 최종 결제 금액 0원 이하 거절, 쿠폰 할인 반영) 검증 추가
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
    @DisplayName("상품 금액 합계가 1만원 이상이면 포인트를 사용할 수 있고 최종 결제 금액에서 빠진다")
    void appliesUsedPoint_whenOrderAmountMeetsMinimum() {
        Order order = orderWithStatus(OrderStatus.PENDING);

        order.applyUsedPoint(3_000, 10_000);

        assertThat(order.getUsedPoint()).isEqualTo(3_000);
        assertThat(order.calculateFinalAmount(10_000)).isEqualTo(7_000);
    }

    @Test
    @DisplayName("상품 금액 합계가 1만원 미만이면 포인트 사용을 거절한다(POINT_MIN_ORDER_AMOUNT_NOT_MET)")
    void rejectsUsedPoint_whenOrderAmountBelowMinimum() {
        Order order = orderWithStatus(OrderStatus.PENDING);

        assertThatThrownBy(() -> order.applyUsedPoint(100, Order.MIN_ORDER_AMOUNT_FOR_POINT - 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_MIN_ORDER_AMOUNT_NOT_MET);
        assertThat(order.getUsedPoint()).isZero();
    }

    // 최소 금액은 쿠폰 적용 전 상품 금액 합계로 판단한다. 할인 후 금액이 1만원 아래로 내려가도 포인트를 쓸 수 있다.
    @Test
    @DisplayName("최소 주문 금액은 할인 전 상품 금액 합계로 판단하고, 사용 한도는 쿠폰 할인 후 금액으로 판단한다")
    void judgesMinimumBeforeDiscountAndLimitAfterDiscount() {
        Order order = orderWithStatus(OrderStatus.PENDING);
        order.applyDiscount(3_000);

        order.applyUsedPoint(6_999, 10_000);
        assertThat(order.calculateFinalAmount(10_000)).isEqualTo(1);

        Order another = orderWithStatus(OrderStatus.PENDING);
        another.applyDiscount(3_000);
        assertThatThrownBy(() -> another.applyUsedPoint(7_000, 10_000))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_EXCEEDS_PAYMENT_AMOUNT);
    }

    // 0원 결제는 PG 승인 없이 결제 완료로 넘기는 흐름이 없어 거절한다.
    @Test
    @DisplayName("사용 포인트가 결제 금액과 같거나 크면(최종 결제 금액 0원 이하) 거절한다(POINT_EXCEEDS_PAYMENT_AMOUNT)")
    void rejectsUsedPoint_whenFinalAmountNotPositive() {
        Order exact = orderWithStatus(OrderStatus.PENDING);
        assertThatThrownBy(() -> exact.applyUsedPoint(10_000, 10_000))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_EXCEEDS_PAYMENT_AMOUNT);

        Order over = orderWithStatus(OrderStatus.PENDING);
        assertThatThrownBy(() -> over.applyUsedPoint(15_000, 10_000))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_EXCEEDS_PAYMENT_AMOUNT);
        assertThat(over.getUsedPoint()).isZero();
    }

    @Test
    @DisplayName("음수 사용 포인트는 거절한다(INVALID_INPUT)")
    void rejectsNegativeUsedPoint() {
        Order order = orderWithStatus(OrderStatus.PENDING);

        assertThatThrownBy(() -> order.applyUsedPoint(-1, 50_000))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("포인트를 쓰지 않으면(0) 주문 금액이 1만원 미만이어도 통과한다")
    void allowsZeroUsedPoint_regardlessOfAmount() {
        Order order = orderWithStatus(OrderStatus.PENDING);

        order.applyUsedPoint(0, 5_000);

        assertThat(order.getUsedPoint()).isZero();
        assertThat(order.calculateFinalAmount(5_000)).isEqualTo(5_000);
    }

    // 토스 orderId 규칙: 영문 대소문자·숫자·'-'·'_' 만, 6~64자. 컬럼 길이도 64다.
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
