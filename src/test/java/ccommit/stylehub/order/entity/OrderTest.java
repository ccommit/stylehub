package ccommit.stylehub.order.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * Order의 생성, 상태 전이(취소/결제완료), 최종 금액 계산 로직을 검증하는 단위테스트이다.
 * </p>
 */
class OrderTest {

    private Order newOrder() {
        User user = User.builder().userId(1L).build();
        Address address = Address.builder().addressId(1L).user(user).build();
        return Order.create(user, address);
    }

    @Test
    @DisplayName("생성 직후 PENDING 상태이고 고유한 pgOrderId가 발급된다")
    void 생성_직후_PENDING_상태다() {
        // when
        Order order = newOrder();

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPgOrderId()).startsWith("ORD-");
        assertThat(order.getDiscountAmount()).isZero();
        assertThat(order.getUsedPoint()).isZero();
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @ParameterizedTest
        @DisplayName("PENDING/PAID 상태면 CANCELLED로 전환된다")
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID"})
        void PENDING_또는_PAID면_취소된다(OrderStatus cancellable) {
            // given
            Order order = newOrder();
            order.updateOrderStatus(cancellable);

            // when
            order.cancel();

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @ParameterizedTest
        @DisplayName("그 외 상태면 INVALID_ORDER_STATUS 예외가 발생한다")
        @EnumSource(value = OrderStatus.class, names = {"PREPARING", "SHIPPING", "DELIVERED", "CANCELLED"})
        void 그외_상태면_예외가_발생한다(OrderStatus notCancellable) {
            // given
            Order order = newOrder();
            order.updateOrderStatus(notCancellable);

            // when & then
            assertThatThrownBy(order::cancel)
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_ORDER_STATUS);
            assertThat(order.getOrderStatus()).isEqualTo(notCancellable);
        }
    }

    @Nested
    @DisplayName("markPaid")
    class MarkPaid {

        @Test
        @DisplayName("PENDING 상태면 PAID로 전환된다")
        void PENDING_상태면_PAID로_전환된다() {
            // given
            Order order = newOrder();

            // when
            order.markPaid();

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        @DisplayName("PENDING이 아니면 INVALID_ORDER_STATUS 예외가 발생한다")
        void PENDING이_아니면_예외가_발생한다() {
            // given
            Order order = newOrder();
            order.markPaid();

            // when & then
            assertThatThrownBy(order::markPaid)
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_ORDER_STATUS);
        }
    }

    @Test
    @DisplayName("startDelivery 호출 시 PREPARING 상태로 전환된다")
    void startDelivery_호출시_PREPARING으로_전환된다() {
        // given
        Order order = newOrder();
        order.markPaid();

        // when
        order.startDelivery();

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PREPARING);
    }

    @Test
    @DisplayName("updateOrderStatus는 검증 없이 상태를 그대로 변경한다")
    void updateOrderStatus는_검증없이_변경한다() {
        // given
        Order order = newOrder();

        // when
        order.updateOrderStatus(OrderStatus.DELIVERED);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Nested
    @DisplayName("calculateFinalAmount")
    class CalculateFinalAmount {

        @Test
        @DisplayName("할인 금액과 사용 포인트를 뺀 최종 금액을 계산한다")
        void 할인과_포인트를_차감한_금액을_계산한다() {
            // given
            Order order = Order.builder()
                    .discountAmount(1000)
                    .usedPoint(500)
                    .build();

            // when & then
            assertThat(order.calculateFinalAmount(10000)).isEqualTo(8500);
        }

        @Test
        @DisplayName("할인/포인트가 없으면 총액이 그대로 최종 금액이 된다")
        void 할인이_없으면_총액그대로다() {
            // given
            Order order = newOrder();

            // when & then
            assertThat(order.calculateFinalAmount(10000)).isEqualTo(10000);
        }
    }
}
