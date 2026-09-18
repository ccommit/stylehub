package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.idempotency.IdempotencyRecordRepository;
import ccommit.stylehub.common.idempotency.IdempotencyRequest;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 결제 취소 Idempotency-Key 가 재전송된 부분 취소를 한 번만 반영하고, PG 응답 유실 뒤 재시도에 같은 PG 멱등 키를 넘기는지 검증한다.
 * PG 통신만 PaymentClientFactory 목으로 대체한다.
 * </p>
 */
@SpringBootTest
class PaymentCancelIdempotencyTest {

    private static final String CANCEL_URL = "/api/v1/payments/{paymentId}/cancel";
    private static final String REASON = "부분 반품";
    private static final int PARTIAL_AMOUNT = 1000;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

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
        given(paymentClientFactory.getClient(any())).willReturn(paymentClient);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        idempotencyRecordRepository.deleteAll();
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 키로 부분 취소를 두 번 보내면 한 번만 취소되고 PG 취소도 한 번만 호출된다")
    void cancelsOnce_forRetriedKey() throws Exception {
        // given
        PaidPayment paid = placePaidPayment();

        // when
        MockHttpServletResponse first = cancel(paid, PARTIAL_AMOUNT, "cancel-retry");
        MockHttpServletResponse retry = cancel(paid, PARTIAL_AMOUNT, "cancel-retry");

        // then
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(retry.getStatus()).isEqualTo(200);
        assertThat(paymentIdOf(retry)).isEqualTo(paid.paymentId());
        assertThat(balanceOf(paid)).isEqualTo(paid.totalAmount() - PARTIAL_AMOUNT);
        then(paymentClient).should(times(1)).cancelPayment(eq(paid.paymentKey()), eq(REASON), eq(PARTIAL_AMOUNT), any());
    }

    @Test
    @DisplayName("PG 응답을 받지 못해 롤백된 취소를 같은 키로 재시도하면 PG 에 같은 멱등 키가 다시 전달되고 DB 에는 한 번만 반영된다")
    void resendsSamePgKey_afterPgFailure() throws Exception {
        // given
        PaidPayment paid = placePaidPayment();
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED))
                .willDoNothing()
                .given(paymentClient).cancelPayment(any(), any(), any(), any());

        // when
        MockHttpServletResponse failed = cancel(paid, PARTIAL_AMOUNT, "cancel-after-timeout");
        int balanceAfterFailure = balanceOf(paid);
        MockHttpServletResponse retried = cancel(paid, PARTIAL_AMOUNT, "cancel-after-timeout");

        // then
        assertThat(failed.getStatus()).isEqualTo(502);
        assertThat(balanceAfterFailure).isEqualTo(paid.totalAmount());
        assertThat(retried.getStatus()).isEqualTo(200);
        assertThat(balanceOf(paid)).isEqualTo(paid.totalAmount() - PARTIAL_AMOUNT);

        List<String> pgKeys = capturePgKeys(2);
        assertThat(pgKeys.get(0)).isNotNull().isEqualTo(pgKeys.get(1));
    }

    @Test
    @DisplayName("다른 키로 같은 금액을 두 번 부분 취소하면 두 번 모두 취소되고 PG 에는 서로 다른 멱등 키가 전달된다")
    void cancelsTwice_forDifferentKeys() throws Exception {
        // given
        PaidPayment paid = placePaidPayment();

        // when
        cancel(paid, PARTIAL_AMOUNT, "cancel-first");
        cancel(paid, PARTIAL_AMOUNT, "cancel-second");

        // then
        assertThat(balanceOf(paid)).isEqualTo(paid.totalAmount() - PARTIAL_AMOUNT * 2);
        List<String> pgKeys = capturePgKeys(2);
        assertThat(pgKeys.get(0)).isNotEqualTo(pgKeys.get(1));
    }

    @Test
    @DisplayName("같은 키로 취소 금액을 바꿔 보내면 422 로 거절하고 추가 취소하지 않는다")
    void rejectsKeyReuse_withDifferentAmount() throws Exception {
        // given
        PaidPayment paid = placePaidPayment();
        cancel(paid, PARTIAL_AMOUNT, "cancel-reused");

        // when
        MockHttpServletResponse reused = cancel(paid, PARTIAL_AMOUNT * 2, "cancel-reused");

        // then
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(JsonPath.<String>read(reused.getContentAsString(), "$.code"))
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED.getCode());
        assertThat(balanceOf(paid)).isEqualTo(paid.totalAmount() - PARTIAL_AMOUNT);
        then(paymentClient).should(times(1)).cancelPayment(any(), any(), any(), any());
    }

    private record PaidPayment(Long paymentId, Long buyerId, String paymentKey, int totalAmount) {
    }

    private PaidPayment placePaidPayment() {
        OrderFixtureFactory.Fixture buyer = fixtureFactory.create(100);
        OrderResponse placed = orderService.placeOrder(buyer.userId(), new OrderCreateRequest(
                buyer.addressId(), List.of(new OrderDetailRequest(buyer.optionId(), 1)), null
        ));
        String paymentKey = "pk-" + placed.pgOrderId();
        Long paymentId = paymentService.confirmPayment(paymentKey, placed.pgOrderId(), placed.finalAmount()).paymentId();
        return new PaidPayment(paymentId, buyer.userId(), paymentKey, placed.finalAmount());
    }

    private MockHttpServletResponse cancel(PaidPayment paid, int amount, String idempotencyKey) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, paid.buyerId());
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, UserRole.USER);
        return mockMvc.perform(post(CANCEL_URL, paid.paymentId())
                        .session(session)
                        .header(IdempotencyRequest.HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cancelReason": "%s", "cancelAmount": %d}
                                """.formatted(REASON, amount)))
                .andReturn().getResponse();
    }

    private List<String> capturePgKeys(int expectedCalls) {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        then(paymentClient).should(times(expectedCalls)).cancelPayment(any(), any(), any(), keys.capture());
        return keys.getAllValues();
    }

    private Long paymentIdOf(MockHttpServletResponse response) throws Exception {
        return ((Number) JsonPath.read(response.getContentAsString(), "$.paymentId")).longValue();
    }

    private int balanceOf(PaidPayment paid) {
        Payment payment = paymentRepository.findById(paid.paymentId()).orElseThrow();
        return payment.getBalanceAmount();
    }
}
