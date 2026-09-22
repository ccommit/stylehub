package ccommit.stylehub.order.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.CouponUsageResult;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.port.CouponPort;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderDetail;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.event.OrderPlacedEvent;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.validator.DeliveryValidator;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.port.ProductPort;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 * @modified 2026/09/17 by WonJin - test: 결제 대기 주문만 취소하는 cancelUnpaidOrder 검증 추가
 * @modified 2026/09/17 by WonJin - test: CouponPort 목 추가, 쿠폰 사용 시 스토어별 주문 금액 전달·할인 반영 검증
 * @modified 2026/09/17 by WonJin - test: 포인트 사용 주문(주문 INSERT 전 차감, 규칙 판정 후 이력, 잔액 부족 시 재고·주문 미반영)과 취소 시 포인트 선복구 검증 추가
 *
 * <p>
 * OrderService 의 주문 접수(포인트 차감·재고 차감·쿠폰 적용·이벤트 발행)와 결제 대기 주문 취소를 검증하는 단위 테스트이다.
 * 포인트 규칙 자체는 OrderTest, 실제 잔액·동시성은 OrderPointIntegrationTest 에서 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDetailRepository orderDetailRepository;

    @Mock
    private OrderQueryRepository orderQueryRepository;

    @Mock
    private DeliveryValidator deliveryValidator;

    @Mock
    private UserPort userPort;

    @Mock
    private ProductPort productPort;

    @Mock
    private CouponPort couponPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    @Nested
    @DisplayName("placeOrder (주문 생성)")
    class PlaceOrder {

        @Test
        @DisplayName("정상 주문 시 주문 저장, 재고 차감, OrderPlacedEvent 발행이 모두 수행된다")
        void placesOrderSuccessfully() {
            // given
            Long userId = 1L;
            Long addressId = 10L;
            Long optionId = 100L;
            int quantity = 2;
            int unitPrice = 5_000;

            OrderCreateRequest request = new OrderCreateRequest(
                    addressId,
                    List.of(new OrderDetailRequest(optionId, quantity)),
                    null
            );

            stubAddressOwnedByUser(userId, addressId);
            Order savedOrder = stubOrderSave(999L);
            ProductOption option = stubDecreaseStock(optionId, quantity, unitPrice);
            OrderDetail detail = stubOrderDetail(1L, option, savedOrder, quantity, unitPrice);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detail));

            // when
            OrderResponse response = orderService.placeOrder(userId, request);

            // then
            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(999L);
            assertThat(response.totalAmount()).isEqualTo(quantity * unitPrice);

            then(productPort).should().decreaseStockWithLock(optionId, quantity);
            then(orderRepository).should().save(any(Order.class));
            then(orderDetailRepository).should().saveAll(anyList());
            then(eventPublisher).should().publishEvent(any(OrderPlacedEvent.class));
        }

        @Test
        @DisplayName("배송지 소유권이 맞지 않으면 재고 차감·주문 저장이 일어나지 않는다")
        void abortsBeforeStockDeduction_whenAddressOwnerMismatch() {
            // given
            Long userId = 1L;
            Long addressId = 10L;
            OrderCreateRequest request = new OrderCreateRequest(
                    addressId,
                    List.of(new OrderDetailRequest(100L, 1)),
                    null
            );
            willThrow(new BusinessException(ErrorCode.UNAUTHORIZED_ORDER_ACCESS))
                    .given(userPort).findAddressByOwner(userId, addressId);

            // when / then
            assertThatThrownBy(() -> orderService.placeOrder(userId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED_ORDER_ACCESS);

            then(orderRepository).should(never()).save(any(Order.class));
            then(productPort).should(never()).decreaseStockWithLock(any(), anyInt());
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("재고 차감 단계에서 INSUFFICIENT_STOCK 이 발생하면 이벤트는 발행되지 않는다")
        void doesNotPublishEvent_whenStockInsufficient() {
            // given
            Long userId = 1L;
            Long addressId = 10L;
            Long optionId = 100L;
            OrderCreateRequest request = new OrderCreateRequest(
                    addressId,
                    List.of(new OrderDetailRequest(optionId, 999)),
                    null
            );

            stubAddressOwnedByUser(userId, addressId);
            stubOrderSave(1L);
            willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK))
                    .given(productPort).decreaseStockWithLock(optionId, 999);

            // when / then
            assertThatThrownBy(() -> orderService.placeOrder(userId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK);

            then(eventPublisher).should(never()).publishEvent(any());
            then(orderDetailRepository).should(never()).saveAll(anyList());
        }

        @Test
        @DisplayName("한 주문에 동일 옵션이 여러 번 포함되면 수량이 합산되어 재고 차감이 1번만 발생한다")
        void mergesSameOptionQuantities() {
            // given
            Long userId = 1L;
            Long addressId = 10L;
            Long optionId = 100L;
            OrderCreateRequest request = new OrderCreateRequest(
                    addressId,
                    List.of(
                            new OrderDetailRequest(optionId, 2),
                            new OrderDetailRequest(optionId, 3)        // 같은 옵션 → 합산되어 5개 차감
                    ),
                    null
            );

            stubAddressOwnedByUser(userId, addressId);
            Order savedOrder = stubOrderSave(1L);
            ProductOption option = stubDecreaseStock(optionId, 5, 5_000);
            OrderDetail detail = stubOrderDetail(1L, option, savedOrder, 5, 5_000);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detail));

            // when
            orderService.placeOrder(userId, request);

            // then — 합산된 수량 5 로 1번만 호출
            then(productPort).should().decreaseStockWithLock(optionId, 5);
            then(productPort).should(never()).decreaseStockWithLock(optionId, 2);
            then(productPort).should(never()).decreaseStockWithLock(optionId, 3);
        }
    }

    @Nested
    @DisplayName("placeOrder (쿠폰 사용)")
    class PlaceOrderWithCoupon {

        @Test
        @DisplayName("쿠폰 사용 주문은 스토어별 주문 금액을 쿠폰 포트에 넘기고, 돌려받은 할인액을 주문에 반영하고 첫 항목에 쿠폰을 연결한다")
        void passesAmountByStoreAndAppliesDiscount() {
            // given
            Long userId = 1L;
            Long addressId = 10L;
            Long userCouponId = 77L;
            OrderCreateRequest request = new OrderCreateRequest(addressId, List.of(
                    new OrderDetailRequest(100L, 1),
                    new OrderDetailRequest(200L, 2)
            ), userCouponId);

            stubAddressOwnedByUser(userId, addressId);
            Order savedOrder = stubOrderSave(999L);
            ProductOption storeAOption = stubDecreaseStock(100L, 1, 10_000);
            ProductOption storeBOption = stubDecreaseStock(200L, 2, 5_000);
            given(storeAOption.getStoreId()).willReturn(10L);
            given(storeBOption.getStoreId()).willReturn(20L);
            OrderDetail detailA = stubOrderDetail(1L, storeAOption, savedOrder, 1, 10_000);
            OrderDetail detailB = stubOrderDetail(2L, storeBOption, savedOrder, 2, 5_000);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detailA, detailB));

            UserCoupon userCoupon = mock(UserCoupon.class);
            given(couponPort.useUserCoupon(eq(userId), eq(userCouponId), any()))
                    .willReturn(new CouponUsageResult(userCoupon, 1_000));

            // when
            orderService.placeOrder(userId, request);

            // then
            then(couponPort).should().useUserCoupon(userId, userCouponId, Map.of(10L, 10_000, 20L, 10_000));
            then(savedOrder).should().applyDiscount(1_000);
            then(detailA).should().attachCoupon(userCoupon);
        }

        @Test
        @DisplayName("쿠폰을 쓰지 않는 주문은 쿠폰 포트를 호출하지 않는다")
        void doesNotUseCoupon_whenCouponIdMissing() {
            // given
            OrderCreateRequest request = new OrderCreateRequest(10L, List.of(new OrderDetailRequest(100L, 1)), null);
            stubAddressOwnedByUser(1L, 10L);
            Order savedOrder = stubOrderSave(999L);
            ProductOption option = stubDecreaseStock(100L, 1, 10_000);
            OrderDetail detail = stubOrderDetail(1L, option, savedOrder, 1, 10_000);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detail));

            // when
            orderService.placeOrder(1L, request);

            // then
            then(couponPort).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("placeOrder (포인트 사용)")
    class PlaceOrderWithPoint {

        // 주문 INSERT 가 외래키 검사로 사용자 행에 공유 락을 걸기 전에 차감 UPDATE 로 배타 락을 먼저 잡아야 같은 사용자 동시 주문이 교착되지 않는다.
        @Test
        @DisplayName("포인트 사용 주문은 주문 저장 전에 차감하고, 쿠폰 반영 후 주문이 규칙을 판정한 뒤 사용 이력을 남긴다")
        void deductsBeforeSavingOrderAndRecordsAfterRuleCheck() {
            // given
            Long userId = 1L;
            OrderCreateRequest request = new OrderCreateRequest(
                    10L, List.of(new OrderDetailRequest(100L, 1)), null, 3_000);
            stubAddressOwnedByUser(userId, 10L);
            Order savedOrder = stubOrderSave(999L);
            ProductOption option = stubDecreaseStock(100L, 1, 10_000);
            OrderDetail detail = stubOrderDetail(1L, option, savedOrder, 1, 10_000);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detail));

            // when
            orderService.placeOrder(userId, request);

            // then
            InOrder inOrder = inOrder(userPort, orderRepository, productPort, savedOrder, eventPublisher);
            inOrder.verify(userPort).deductPoint(userId, 3_000);
            inOrder.verify(orderRepository).save(any(Order.class));
            inOrder.verify(productPort).decreaseStockWithLock(100L, 1);
            inOrder.verify(savedOrder).applyUsedPoint(3_000, 10_000);
            inOrder.verify(userPort).recordPointUse(userId, 999L, 3_000);
            inOrder.verify(eventPublisher).publishEvent(any(OrderPlacedEvent.class));
        }

        @Test
        @DisplayName("잔액이 부족하면 주문 저장·재고 차감·이벤트 발행 없이 INSUFFICIENT_POINT 로 거절된다")
        void rejectsBeforeTouchingStock_whenPointInsufficient() {
            // given
            Long userId = 1L;
            OrderCreateRequest request = new OrderCreateRequest(
                    10L, List.of(new OrderDetailRequest(100L, 1)), null, 3_000);
            stubAddressOwnedByUser(userId, 10L);
            willThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINT))
                    .given(userPort).deductPoint(userId, 3_000);

            // when / then
            assertThatThrownBy(() -> orderService.placeOrder(userId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_POINT);

            then(orderRepository).should(never()).save(any(Order.class));
            then(productPort).should(never()).decreaseStockWithLock(any(), anyInt());
            then(userPort).should(never()).recordPointUse(any(), any(), anyInt());
            then(eventPublisher).should(never()).publishEvent(any());
        }

        // 예외가 트랜잭션 밖으로 나가면 앞서 실행한 차감 UPDATE 도 함께 롤백된다(실제 롤백은 OrderPointIntegrationTest 에서 확인).
        @Test
        @DisplayName("주문이 포인트 규칙 위반으로 거절하면 사용 이력을 남기지 않고 이벤트도 발행하지 않는다")
        void doesNotRecordUse_whenOrderRejectsPoint() {
            // given
            Long userId = 1L;
            OrderCreateRequest request = new OrderCreateRequest(
                    10L, List.of(new OrderDetailRequest(100L, 1)), null, 10_000);
            stubAddressOwnedByUser(userId, 10L);
            Order savedOrder = stubOrderSave(999L);
            ProductOption option = stubDecreaseStock(100L, 1, 10_000);
            OrderDetail detail = stubOrderDetail(1L, option, savedOrder, 1, 10_000);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detail));
            willThrow(new BusinessException(ErrorCode.POINT_EXCEEDS_PAYMENT_AMOUNT))
                    .given(savedOrder).applyUsedPoint(10_000, 10_000);

            // when / then
            assertThatThrownBy(() -> orderService.placeOrder(userId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POINT_EXCEEDS_PAYMENT_AMOUNT);

            then(userPort).should(never()).recordPointUse(any(), any(), anyInt());
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("사용 포인트가 없으면(null) 포인트 차감·이력을 호출하지 않고 0 으로 판정한다")
        void doesNotTouchPoint_whenUsedPointMissing() {
            // given
            OrderCreateRequest request = new OrderCreateRequest(10L, List.of(new OrderDetailRequest(100L, 1)), null);
            stubAddressOwnedByUser(1L, 10L);
            Order savedOrder = stubOrderSave(999L);
            ProductOption option = stubDecreaseStock(100L, 1, 5_000);
            OrderDetail detail = stubOrderDetail(1L, option, savedOrder, 1, 5_000);
            given(orderDetailRepository.saveAll(anyList())).willReturn(List.of(detail));

            // when
            orderService.placeOrder(1L, request);

            // then
            then(savedOrder).should().applyUsedPoint(0, 5_000);
            then(userPort).should(never()).deductPoint(any(), anyInt());
            then(userPort).should(never()).recordPointUse(any(), any(), anyInt());
        }
    }

    // ===== Helpers =====

    private void stubAddressOwnedByUser(Long userId, Long addressId) {
        User user = mock(User.class);
        Address address = mock(Address.class);
        given(address.getUser()).willReturn(user);
        given(userPort.findAddressByOwner(userId, addressId)).willReturn(address);
    }

    private Order stubOrderSave(Long orderId) {
        Order order = mock(Order.class);
        given(order.getOrderId()).willReturn(orderId);
        given(order.getPgOrderId()).willReturn("ORD-TEST-" + orderId);
        given(order.getOrderStatus()).willReturn(OrderStatus.PENDING);
        given(order.getDiscountAmount()).willReturn(0);
        given(order.getUsedPoint()).willReturn(0);
        given(order.getEarnedPoint()).willReturn(0);
        given(order.getCreatedAt()).willReturn(LocalDateTime.now());
        given(order.calculateFinalAmount(anyInt())).willReturn(0);
        given(orderRepository.save(any(Order.class))).willReturn(order);
        return order;
    }

    private ProductOption stubDecreaseStock(Long optionId, int quantity, int price) {
        ProductOption option = mock(ProductOption.class);
        given(option.getProductOptionId()).willReturn(optionId);
        given(option.getProductPrice()).willReturn(price);
        given(option.getStoreId()).willReturn(10L);
        given(option.getStoreName()).willReturn("테스트스토어");
        given(option.getProductName()).willReturn("테스트상품");
        given(option.getColor()).willReturn("RED");
        given(option.getSize()).willReturn("M");
        given(productPort.decreaseStockWithLock(optionId, quantity)).willReturn(option);
        return option;
    }

    private OrderDetail stubOrderDetail(Long detailId, ProductOption option, Order order,
                                        int quantity, int unitPrice) {
        OrderDetail detail = mock(OrderDetail.class);
        given(detail.getOrderDetailId()).willReturn(detailId);
        given(detail.getProductOption()).willReturn(option);
        given(detail.getOrder()).willReturn(order);
        given(detail.getQuantity()).willReturn(quantity);
        given(detail.getUnitPrice()).willReturn(unitPrice);
        given(detail.getTotalPrice()).willReturn(quantity * unitPrice);
        return detail;
    }

    @Nested
    @DisplayName("cancelUnpaidOrder (결제 대기 주문만 취소)")
    class CancelUnpaidOrder {

        @Test
        @DisplayName("결제 대기(PENDING) 주문이면 취소하고 재고를 복구한 뒤 true 를 돌려준다")
        void cancelsPendingOrder() {
            // given
            Order order = Order.builder().orderStatus(OrderStatus.PENDING).build();
            ReflectionTestUtils.setField(order, "orderId", 1L);
            ProductOption option = mock(ProductOption.class);
            given(option.getProductOptionId()).willReturn(100L);
            OrderDetail detail = mock(OrderDetail.class);
            given(detail.getProductOption()).willReturn(option);
            given(detail.getQuantity()).willReturn(2);
            given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
            given(orderDetailRepository.findByOrderIdWithDetails(1L)).willReturn(new ArrayList<>(List.of(detail)));

            // when
            boolean cancelled = orderService.cancelUnpaidOrder(1L);

            // then
            assertThat(cancelled).isTrue();
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
            then(productPort).should().increaseStock(100L, 2);
        }

        // 주문 생성과 같은 락 순서(사용자 → 재고 옵션)를 지키도록 포인트를 재고보다 먼저 복구한다.
        @Test
        @DisplayName("포인트를 사용한 결제 대기 주문을 취소하면 사용 포인트를 재고보다 먼저 한 번 복구한다")
        void restoresUsedPointBeforeStock() {
            // given
            User buyer = User.builder().build();
            ReflectionTestUtils.setField(buyer, "userId", 7L);
            Order order = Order.builder().orderStatus(OrderStatus.PENDING).user(buyer).usedPoint(3_000).build();
            ReflectionTestUtils.setField(order, "orderId", 1L);
            ProductOption option = mock(ProductOption.class);
            given(option.getProductOptionId()).willReturn(100L);
            OrderDetail detail = mock(OrderDetail.class);
            given(detail.getProductOption()).willReturn(option);
            given(detail.getQuantity()).willReturn(1);
            given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
            given(orderDetailRepository.findByOrderIdWithDetails(1L)).willReturn(new ArrayList<>(List.of(detail)));

            // when
            orderService.cancelUnpaidOrder(1L);

            // then
            InOrder inOrder = inOrder(userPort, productPort);
            inOrder.verify(userPort).restoreUsedPoint(7L, 1L, 3_000);
            inOrder.verify(productPort).increaseStock(100L, 1);
        }

        @Test
        @DisplayName("포인트를 쓰지 않은 주문을 취소하면 포인트 복구를 호출하지 않는다")
        void skipsPointRestore_whenNoPointUsed() {
            // given
            Order order = Order.builder().orderStatus(OrderStatus.PENDING).build();
            ReflectionTestUtils.setField(order, "orderId", 1L);
            given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
            given(orderDetailRepository.findByOrderIdWithDetails(1L)).willReturn(new ArrayList<>());

            // when
            orderService.cancelUnpaidOrder(1L);

            // then
            then(userPort).should(never()).restoreUsedPoint(any(), any(), anyInt());
        }

        // 만료 처리와 승인 반영이 겹쳐 승인이 먼저 커밋됐다면, 주문 행 락을 잡은 뒤 PAID 를 보고 아무것도 하지 않아야 한다.
        @Test
        @DisplayName("이미 결제된(PAID) 주문이면 취소하지 않고 재고도 복구하지 않는다")
        void skipsPaidOrder() {
            // given
            Order order = Order.builder().orderStatus(OrderStatus.PAID).build();
            given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

            // when
            boolean cancelled = orderService.cancelUnpaidOrder(1L);

            // then
            assertThat(cancelled).isFalse();
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            then(productPort).should(never()).increaseStock(anyLong(), anyInt());
            then(userPort).should(never()).restoreUsedPoint(any(), any(), anyInt());
        }
    }
}
