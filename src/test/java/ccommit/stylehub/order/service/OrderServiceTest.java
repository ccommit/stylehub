package ccommit.stylehub.order.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 *
 * <p>
 * OrderService.placeOrder 의 단위 테스트이다.
 * 현재 코드는 쿠폰/포인트 로직이 TODO 상태이므로 해당 케이스는 기능 구현 후 별도 추가가 필요하다.
 * 본 테스트는 기본 주문 플로우(배송지 조회 → 주문 저장 → 재고 차감 → 이벤트 발행)를 검증한다.
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
                    List.of(new OrderDetailRequest(optionId, quantity))
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
                    List.of(new OrderDetailRequest(100L, 1))
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
                    List.of(new OrderDetailRequest(optionId, 999))
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
                    )
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
}
