package ccommit.stylehub.payment.controller;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.payment.service.PaymentService;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 * @modified 2026/09/17 by WonJin - test: 실패 콜백의 승인 결제 보호·응답 반사 제거, 취소 금액 검증, 테스트 데이터 정리 추가
 *
 * <p>
 * 결제 취소 API의 인증·인가 통합 테스트이다.
 * 결제 경로 전체(/api/v1/payments/**)가 인증 인터셉터에서 제외돼 있어 세션 없이도,
 * 남의 결제여도 취소가 실행되던 문제를 재현하고 막혔는지 검증한다.
 * 토스 콜백(success/fail)은 인증 제외 범위를 좁힌 뒤에도 세션 없이 호출돼야 하므로 함께 확인한다.
 *
 *
 * <b>@SpringBootTest + MockMvc 사용 이유</b>
 * 인증 제외 경로(WebConfig)와 역할 검사(@RequiredRole)는 DispatcherServlet 을 거쳐야만 동작한다.
 * 서비스 단위 테스트로는 인터셉터 설정 실수를 잡을 수 없다.
 * 스프링 부트 4 에서 MockMvc 자동 구성은 별도 모듈로 분리됐고 이 프로젝트엔 없어,
 * 애플리케이션 컨텍스트로 MockMvc 를 직접 구성한다. 인터셉터와 예외 핸들러는 그대로 적용된다.
 * PG(토스) 호출은 외부 경계이므로 PaymentClientFactory 만 @MockitoBean 으로 차단한다.
 * </p>
 */
@SpringBootTest
class PaymentCancelAuthorizationTest {

    private static final String CANCEL_URL = "/api/v1/payments/{paymentId}/cancel";
    private static final String CANCEL_REASON = "단순 변심";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private PaymentClientFactory paymentClientFactory;

    private PaymentClient paymentClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        paymentClient = mock(PaymentClient.class);
        given(paymentClientFactory.getClient("TOSS")).willReturn(paymentClient);
    }

    // 결제·주문은 커밋되므로 같은 컨텍스트를 쓰는 다른 통합 테스트에 쌓이지 않게 외래키 순서대로 지운다.
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("세션 없이 결제 취소를 요청하면 401 UNAUTHORIZED 로 거절되고 PG 취소는 호출되지 않는다")
    void 세션없이_취소하면_401() throws Exception {
        // given
        PaidPayment paid = setupPaidPayment();

        // when & then
        mockMvc.perform(cancelRequest(paid.paymentId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        then(paymentClient).should(never()).cancelPayment(any(), any(), any());
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("다른 사용자의 결제를 취소하면 403 UNAUTHORIZED_PAYMENT_ACCESS 로 거절되고 PG 취소는 호출되지 않는다")
    void 다른사용자가_취소하면_403() throws Exception {
        // given
        PaidPayment paid = setupPaidPayment();
        Long otherUserId = fixtureFactory.create(1).userId();

        // when & then
        mockMvc.perform(cancelRequest(paid.paymentId()).session(loginSession(otherUserId, UserRole.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED_PAYMENT_ACCESS.getCode()));

        then(paymentClient).should(never()).cancelPayment(any(), any(), any());
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("STORE 역할은 결제 취소 API 를 호출할 수 없어 403 FORBIDDEN 으로 거절된다")
    void 스토어역할이_취소하면_403() throws Exception {
        // given
        PaidPayment paid = setupPaidPayment();

        // when & then
        mockMvc.perform(cancelRequest(paid.paymentId()).session(loginSession(paid.ownerId(), UserRole.STORE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

        then(paymentClient).should(never()).cancelPayment(any(), any(), any());
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("주문자 본인이 결제를 취소하면 PG 취소가 호출되고 결제가 CANCELED 가 된다")
    void 본인이_취소하면_성공() throws Exception {
        // given
        PaidPayment paid = setupPaidPayment();

        // when & then
        mockMvc.perform(cancelRequest(paid.paymentId()).session(loginSession(paid.ownerId(), UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paid.paymentId()))
                .andExpect(jsonPath("$.status").value(PaymentStatus.CANCELED.name()));

        then(paymentClient).should().cancelPayment(paid.paymentKey(), CANCEL_REASON, null);
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("관리자는 다른 사용자의 결제도 취소할 수 있다")
    void 관리자는_타인결제도_취소한다() throws Exception {
        // given
        PaidPayment paid = setupPaidPayment();
        Long adminUserId = fixtureFactory.create(1).userId();

        // when & then
        mockMvc.perform(cancelRequest(paid.paymentId()).session(loginSession(adminUserId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaymentStatus.CANCELED.name()));

        then(paymentClient).should().cancelPayment(paid.paymentKey(), CANCEL_REASON, null);
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.CANCELED);
    }

    // 인증 제외 범위를 좁히면서 콜백까지 막히면 토스 리다이렉트가 전부 401 로 끝난다.
    // 존재하지 않는 주문으로 호출해 인증 단계를 통과해 서비스까지 도달했는지(404)만 본다.
    @Test
    @DisplayName("토스 승인 콜백(success)은 세션 없이 호출해도 인증 단계에서 막히지 않는다")
    void 토스_승인콜백은_세션없이_호출된다() throws Exception {
        mockMvc.perform(get("/api/v1/payments/success")
                        .param("paymentKey", "pk-unknown")
                        .param("orderId", "ORD-UNKNOWN")
                        .param("amount", "10000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("토스 실패 콜백(fail)은 세션 없이 호출해도 인증 단계에서 막히지 않는다")
    void 토스_실패콜백은_세션없이_호출된다() throws Exception {
        mockMvc.perform(get("/api/v1/payments/fail")
                        .param("code", "PAY_PROCESS_CANCELED")
                        .param("message", "사용자가 결제를 취소했습니다")
                        .param("orderId", "ORD-UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("취소 금액이 0 이하면 400 INVALID_INPUT 으로 거절되고 PG 취소는 호출되지 않는다")
    void 취소금액이_0이하면_400() throws Exception {
        // given
        PaidPayment paid = setupPaidPayment();

        // when & then
        mockMvc.perform(post(CANCEL_URL, paid.paymentId())
                        .session(loginSession(paid.ownerId(), UserRole.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\": \"" + CANCEL_REASON + "\", \"cancelAmount\": -1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));

        then(paymentClient).should(never()).cancelPayment(any(), any(), any());
        assertThat(paymentStatusOf(paid.paymentId())).isEqualTo(PaymentStatus.DONE);
    }

    // 실패 콜백은 인증 없이 열려 있어 pgOrderId 를 아는 사람은 누구나 호출할 수 있다.
    // 승인이 끝난 결제에 반영되면 PG 환불 없이 주문이 취소되고 재고·쿠폰이 복구된다.
    @Test
    @DisplayName("승인된 결제에 실패 콜백을 호출해도 결제·주문·재고가 바뀌지 않고, 요청 message 는 응답에 반사되지 않는다")
    void 승인된_결제의_실패콜백은_아무것도_바꾸지_않는다() throws Exception {
        // given
        OrderFixtureFactory.Fixture owner = fixtureFactory.create(100);
        OrderResponse placed = orderService.placeOrder(owner.userId(), new OrderCreateRequest(
                owner.addressId(), List.of(new OrderDetailRequest(owner.optionId(), 1)), null
        ));
        paymentService.confirmPayment("pk-" + placed.pgOrderId(), placed.pgOrderId(), placed.finalAmount());
        int stockAfterOrder = productOptionRepository.findById(owner.optionId()).orElseThrow().getStockQuantity();

        // when & then
        mockMvc.perform(get("/api/v1/payments/fail")
                        .param("code", "PAY_PROCESS_CANCELED")
                        .param("message", "<script>alert(1)</script>")
                        .param("orderId", placed.pgOrderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(placed.pgOrderId()))
                .andExpect(content().string(not(containsString("script"))));

        Payment payment = paymentRepository.findByOrderPgOrderId(placed.pgOrderId()).orElseThrow();
        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(productOptionRepository.findById(owner.optionId()).orElseThrow().getStockQuantity())
                .isEqualTo(stockAfterOrder);
    }

    @Test
    @DisplayName("승인 대기 결제에 실패 콜백을 호출하면 결제는 ABORTED, 주문은 CANCELLED 가 되고 재고가 복구된다")
    void 승인대기_결제의_실패콜백은_주문을_취소한다() throws Exception {
        // given
        OrderFixtureFactory.Fixture owner = fixtureFactory.create(100);
        OrderResponse placed = orderService.placeOrder(owner.userId(), new OrderCreateRequest(
                owner.addressId(), List.of(new OrderDetailRequest(owner.optionId(), 1)), null
        ));

        // when & then
        mockMvc.perform(get("/api/v1/payments/fail")
                        .param("code", "PAY_PROCESS_CANCELED")
                        .param("orderId", placed.pgOrderId()))
                .andExpect(status().isOk());

        Payment payment = paymentRepository.findByOrderPgOrderId(placed.pgOrderId()).orElseThrow();
        Order order = orderRepository.findById(placed.orderId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(productOptionRepository.findById(owner.optionId()).orElseThrow().getStockQuantity()).isEqualTo(100);
    }

    private record PaidPayment(Long paymentId, Long ownerId, String paymentKey) {
    }

    // 주문 생성 → 결제 승인까지 마쳐 취소 가능한 DONE 상태의 결제를 만든다.
    private PaidPayment setupPaidPayment() {
        OrderFixtureFactory.Fixture owner = fixtureFactory.create(100);
        OrderResponse placed = orderService.placeOrder(owner.userId(), new OrderCreateRequest(
                owner.addressId(), List.of(new OrderDetailRequest(owner.optionId(), 1)), null
        ));

        String paymentKey = "pk-" + placed.pgOrderId();
        Long paymentId = paymentService.confirmPayment(paymentKey, placed.pgOrderId(), placed.finalAmount())
                .paymentId();
        return new PaidPayment(paymentId, owner.userId(), paymentKey);
    }

    private MockHttpServletRequestBuilder cancelRequest(Long paymentId) {
        return post(CANCEL_URL, paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelReason\": \"" + CANCEL_REASON + "\"}");
    }

    private MockHttpSession loginSession(Long userId, UserRole role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
        return session;
    }

    private PaymentStatus paymentStatusOf(Long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow().getStatus();
    }
}
