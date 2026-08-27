package ccommit.stylehub.order.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.dto.response.OrderListResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.dto.response.OrderTotalAmountDto;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderDetail;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.event.OrderPlacedEvent;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.validator.DeliveryValidator;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.port.ProductPort;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * OrderService의 주문 접수, 취소, 배송 상태 변경, 조회 로직을 검증하는 단위테스트이다.
 * Repository/Port/Validator/이벤트 발행기는 전부 Mock으로 대체해 DB 없이 협력 순서와 예외를 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
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

    private ProductOption option(long optionId, long storeId, int price, int stock) {
        User store = User.builder().userId(storeId).storeName("스토어").build();
        Product product = Product.builder().productId(1L).user(store).name("상품").price(price).build();
        ProductOption option = ProductOption.create(product, "black", "M", stock, 100);
        ReflectionTestUtils.setField(option, "productOptionId", optionId);
        return option;
    }

    private Order orderWithId(long orderId, User user, OrderStatus status) {
        Order order = Order.builder().user(user).orderStatus(status).build();
        ReflectionTestUtils.setField(order, "orderId", orderId);
        return order;
    }

    @Nested
    @DisplayName("placeOrder")
    class PlaceOrder {

        @Test
        @DisplayName("배송지 확인 후 재고를 차감하고 주문을 접수한다")
        void 정상적으로_주문을_접수한다() {
            // given
            Long userId = 1L;
            User user = User.builder().userId(userId).build();
            Address address = Address.builder().addressId(10L).user(user).build();
            when(userPort.findAddressByOwner(userId, 10L)).thenReturn(address);
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "orderId", 100L);
                return saved;
            });
            ProductOption option = option(5L, 20L, 10000, 10);
            when(productPort.decreaseStockWithLock(5L, 2)).thenReturn(option);
            when(orderDetailRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            OrderCreateRequest request = new OrderCreateRequest(10L, List.of(new OrderDetailRequest(5L, 2)));

            // when
            OrderResponse response = orderService.placeOrder(userId, request);

            // then
            assertThat(response.orderId()).isEqualTo(100L);
            assertThat(response.totalAmount()).isEqualTo(20000);
            assertThat(response.finalAmount()).isEqualTo(20000);
            assertThat(response.details()).hasSize(1);

            ArgumentCaptor<OrderPlacedEvent> eventCaptor = ArgumentCaptor.forClass(OrderPlacedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isEqualTo(new OrderPlacedEvent(100L, 20000, 20000));
        }

        @Test
        @DisplayName("같은 옵션을 여러 번 담으면 수량을 합산해 한 번만 재고를 차감한다")
        void 같은_옵션은_수량을_합산한다() {
            // given
            Long userId = 1L;
            User user = User.builder().userId(userId).build();
            Address address = Address.builder().addressId(10L).user(user).build();
            when(userPort.findAddressByOwner(userId, 10L)).thenReturn(address);
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
            ProductOption option = option(5L, 20L, 1000, 10);
            when(productPort.decreaseStockWithLock(5L, 5)).thenReturn(option);
            when(orderDetailRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            OrderCreateRequest request = new OrderCreateRequest(10L, List.of(
                    new OrderDetailRequest(5L, 2),
                    new OrderDetailRequest(5L, 3)
            ));

            // when
            orderService.placeOrder(userId, request);

            // then
            verify(productPort, times(1)).decreaseStockWithLock(5L, 5);
            verify(productPort, never()).decreaseStockWithLock(5L, 2);
            verify(productPort, never()).decreaseStockWithLock(5L, 3);
        }

        @Test
        @DisplayName("서로 다른 옵션을 주문하면 데드락 방지를 위해 optionId 오름차순으로 락을 획득한다")
        void 여러_옵션은_optionId_오름차순으로_락을_획득한다() {
            // given
            Long userId = 1L;
            User user = User.builder().userId(userId).build();
            Address address = Address.builder().addressId(10L).user(user).build();
            when(userPort.findAddressByOwner(userId, 10L)).thenReturn(address);
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(productPort.decreaseStockWithLock(any(), anyInt()))
                    .thenReturn(option(1L, 20L, 1000, 10));
            when(orderDetailRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            // 요청은 내림차순(옵션 20 → 옵션 10)으로 들어오지만 서비스는 오름차순으로 락을 획득해야 한다
            OrderCreateRequest request = new OrderCreateRequest(10L, List.of(
                    new OrderDetailRequest(20L, 1),
                    new OrderDetailRequest(10L, 1)
            ));

            // when
            orderService.placeOrder(userId, request);

            // then
            InOrder inOrder = inOrder(productPort);
            inOrder.verify(productPort).decreaseStockWithLock(eq(10L), anyInt());
            inOrder.verify(productPort).decreaseStockWithLock(eq(20L), anyInt());
        }
    }

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        @DisplayName("PENDING/PAID 주문을 취소하면 재고가 복구된다")
        void 정상적으로_취소하고_재고를_복구한다() {
            // given
            Order order = orderWithId(1L, User.builder().userId(1L).build(), OrderStatus.PENDING);
            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            ProductOption optionA = option(10L, 20L, 1000, 5);
            ProductOption optionB = option(20L, 20L, 1000, 5);
            OrderDetail detailA = OrderDetail.create(optionA, order, 2, 1000, null);
            OrderDetail detailB = OrderDetail.create(optionB, order, 1, 1000, null);
            // 조회 결과가 내림차순(20 → 10)으로 와도 서비스가 오름차순으로 정렬해 복구해야 한다
            // (서비스가 리스트를 in-place로 sort하므로 불변 리스트가 아닌 가변 리스트를 반환해야 한다)
            when(orderDetailRepository.findByOrderIdWithDetails(1L))
                    .thenReturn(new ArrayList<>(List.of(detailB, detailA)));

            // when
            orderService.cancelOrder(1L);

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
            InOrder inOrder = inOrder(productPort);
            inOrder.verify(productPort).increaseStock(10L, 2);
            inOrder.verify(productPort).increaseStock(20L, 1);
        }

        @Test
        @DisplayName("존재하지 않는 주문이면 ORDER_NOT_FOUND 예외가 발생한다")
        void 주문이_없으면_예외() {
            // given
            when(orderRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderService.cancelOrder(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
            verify(productPort, never()).increaseStock(any(), anyInt());
        }

        @Test
        @DisplayName("취소할 수 없는 상태면 예외가 발생하고 재고를 복구하지 않는다")
        void 취소불가_상태면_재고를_복구하지_않는다() {
            // given
            Order order = orderWithId(1L, User.builder().userId(1L).build(), OrderStatus.DELIVERED);
            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.cancelOrder(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_ORDER_STATUS);
            verify(orderDetailRepository, never()).findByOrderIdWithDetails(any());
            verify(productPort, never()).increaseStock(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("updateDeliveryStatus")
    class UpdateDeliveryStatus {

        @Test
        @DisplayName("검증을 통과하면 주문 상태를 변경한다")
        void 검증_통과시_상태를_변경한다() {
            // given
            Order order = orderWithId(1L, User.builder().userId(1L).build(), OrderStatus.PREPARING);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 1L, OrderStatus.SHIPPING);

            // when
            orderService.updateDeliveryStatus(request);

            // then
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.SHIPPING);
            verify(deliveryValidator).validate(request, order);
        }

        @Test
        @DisplayName("존재하지 않는 주문이면 ORDER_NOT_FOUND 예외가 발생한다")
        void 주문이_없으면_예외() {
            // given
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());
            UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 999L, OrderStatus.SHIPPING);

            // when & then
            assertThatThrownBy(() -> orderService.updateDeliveryStatus(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("검증에 실패하면 상태를 변경하지 않는다")
        void 검증_실패시_상태를_변경하지_않는다() {
            // given
            Order order = orderWithId(1L, User.builder().userId(1L).build(), OrderStatus.PREPARING);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(1L, 10L, 1L, OrderStatus.DELIVERED);
            doThrow(new BusinessException(ErrorCode.INVALID_DELIVERY_STATUS))
                    .when(deliveryValidator).validate(request, order);

            // when & then
            assertThatThrownBy(() -> orderService.updateDeliveryStatus(request))
                    .isInstanceOf(BusinessException.class);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PREPARING);
        }
    }

    @Nested
    @DisplayName("getMyOrders")
    class GetMyOrders {

        @Test
        @DisplayName("pageSize를 지정하지 않으면 기본 크기(20)로 조회하고 주문별 총액을 매핑한다")
        void 기본_페이지크기로_조회하고_총액을_매핑한다() {
            // given
            Order order = orderWithId(1L, User.builder().userId(1L).build(), OrderStatus.PAID);
            when(orderQueryRepository.findMyOrdersWithCursor(1L, null, 21)).thenReturn(List.of(order));
            when(orderDetailRepository.calculateTotalAmounts(List.of(1L)))
                    .thenReturn(List.of(new OrderTotalAmountDto(1L, 30000L)));

            // when
            CursorResponse<OrderListResponse> result = orderService.getMyOrders(1L, null, null);

            // then
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).totalAmount()).isEqualTo(30000);
            assertThat(result.hasNext()).isFalse();
        }

        @Test
        @DisplayName("총액 매핑에 없는 주문은 0원으로 처리한다")
        void 총액매핑에_없으면_0원으로_처리한다() {
            // given
            Order order = orderWithId(1L, User.builder().userId(1L).build(), OrderStatus.PENDING);
            when(orderQueryRepository.findMyOrdersWithCursor(1L, null, 21)).thenReturn(List.of(order));
            when(orderDetailRepository.calculateTotalAmounts(List.of(1L))).thenReturn(List.of());

            // when
            CursorResponse<OrderListResponse> result = orderService.getMyOrders(1L, null, null);

            // then
            assertThat(result.items().get(0).totalAmount()).isZero();
        }

        @Test
        @DisplayName("조회 결과가 없으면 총액 조회 쿼리를 실행하지 않는다")
        void 조회결과가_없으면_총액쿼리를_실행하지_않는다() {
            // given
            when(orderQueryRepository.findMyOrdersWithCursor(1L, null, 21)).thenReturn(List.of());

            // when
            CursorResponse<OrderListResponse> result = orderService.getMyOrders(1L, null, null);

            // then
            assertThat(result.items()).isEmpty();
            verify(orderDetailRepository, never()).calculateTotalAmounts(anyList());
        }

        @Test
        @DisplayName("pageSize가 최대값을 초과하면 100으로 제한된다")
        void pageSize가_최댓값을_초과하면_제한된다() {
            // given
            when(orderQueryRepository.findMyOrdersWithCursor(1L, null, 101)).thenReturn(List.of());

            // when
            orderService.getMyOrders(1L, null, 500);

            // then
            verify(orderQueryRepository).findMyOrdersWithCursor(1L, null, 101);
        }
    }

    @Nested
    @DisplayName("getOrder")
    class GetOrder {

        @Test
        @DisplayName("본인 주문이면 상세 정보를 반환한다")
        void 본인_주문이면_상세를_반환한다() {
            // given
            User owner = User.builder().userId(1L).build();
            Order order = orderWithId(1L, owner, OrderStatus.PAID);
            ProductOption opt = option(5L, 20L, 5000, 10);
            OrderDetail detail = OrderDetail.create(opt, order, 2, 5000, null);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderDetailRepository.findByOrderIdWithDetails(1L)).thenReturn(List.of(detail));

            // when
            OrderResponse response = orderService.getOrder(1L, 1L);

            // then
            assertThat(response.orderId()).isEqualTo(1L);
            assertThat(response.totalAmount()).isEqualTo(10000);
            assertThat(response.details()).hasSize(1);
        }

        @Test
        @DisplayName("존재하지 않는 주문이면 ORDER_NOT_FOUND 예외가 발생한다")
        void 주문이_없으면_예외() {
            // given
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> orderService.getOrder(1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 주문이 아니면 UNAUTHORIZED_ORDER_ACCESS 예외가 발생한다")
        void 본인_주문이_아니면_예외() {
            // given
            User owner = User.builder().userId(1L).build();
            Order order = orderWithId(1L, owner, OrderStatus.PAID);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            // when & then
            assertThatThrownBy(() -> orderService.getOrder(999L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_ORDER_ACCESS);
            verify(orderDetailRepository, never()).findByOrderIdWithDetails(any());
        }
    }
}
