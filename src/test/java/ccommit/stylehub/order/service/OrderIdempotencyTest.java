package ccommit.stylehub.order.service;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.idempotency.IdempotencyRecordRepository;
import ccommit.stylehub.common.idempotency.IdempotencyRequest;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 주문 생성 Idempotency-Key 가 재전송·동시 중복 요청에서 주문과 재고 차감을 한 번만 만드는지 컨트롤러부터 DB 까지 검증한다.
 * 키 기록과 주문이 같은 트랜잭션이어야 의미가 있어 실제 트랜잭션으로 확인한다.
 * </p>
 */
@SpringBootTest
class OrderIdempotencyTest {

    private static final String ORDER_URL = "/api/v1/orders/orders";
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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
    @DisplayName("같은 Idempotency-Key 로 두 번 주문하면 주문은 하나만 생기고, 두 응답 모두 같은 주문을 돌려준다")
    void replaysSameOrder_forRetriedKey() throws Exception {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);
        long ordersBefore = orderRepository.count();

        // when
        MockHttpServletResponse first = perform(orderRequest(fx, 2).header(IdempotencyRequest.HEADER, "key-retry"), fx.userId());
        MockHttpServletResponse retry = perform(orderRequest(fx, 2).header(IdempotencyRequest.HEADER, "key-retry"), fx.userId());

        // then
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(orderIdOf(retry)).isEqualTo(orderIdOf(first));
        assertThat(orderRepository.count()).isEqualTo(ordersBefore + 1);
        assertThat(stockOf(fx.optionId())).isEqualTo(INITIAL_STOCK - 2);
    }

    @Test
    @DisplayName("같은 키로 10건이 동시에 들어와도 주문과 재고 차감은 한 번이고, 나머지는 같은 주문을 받거나 처리 중(409)으로 거절된다")
    void createsOneOrder_forConcurrentSameKey() throws Exception {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);
        long ordersBefore = orderRepository.count();
        int requestCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requestCount);
        List<MockHttpServletResponse> responses = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // when
        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    responses.add(perform(orderRequest(fx, 1).header(IdempotencyRequest.HEADER, "key-concurrent"), fx.userId()));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // then
        assertThat(errors).isEmpty();
        Set<Long> createdOrderIds = ConcurrentHashMap.newKeySet();
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 201) {
                createdOrderIds.add(orderIdOf(response));
            } else {
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(codeOf(response)).isEqualTo(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS.getCode());
            }
        }
        assertThat(createdOrderIds).hasSize(1);
        assertThat(orderRepository.count()).isEqualTo(ordersBefore + 1);
        assertThat(stockOf(fx.optionId())).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("같은 키로 수량을 바꿔 보내면 422 로 거절하고 두 번째 주문은 만들지 않는다")
    void rejectsKeyReuse_withDifferentRequest() throws Exception {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);
        perform(orderRequest(fx, 1).header(IdempotencyRequest.HEADER, "key-reused"), fx.userId());

        // when
        MockHttpServletResponse reused = perform(orderRequest(fx, 3).header(IdempotencyRequest.HEADER, "key-reused"), fx.userId());

        // then
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(codeOf(reused)).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED.getCode());
        assertThat(stockOf(fx.optionId())).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("재고 부족으로 실패한 요청은 키를 남기지 않아, 재고가 채워진 뒤 같은 키로 다시 주문할 수 있다")
    void failedRequestDoesNotConsumeKey() throws Exception {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(1);
        MockHttpServletResponse failed = perform(orderRequest(fx, 2).header(IdempotencyRequest.HEADER, "key-after-restock"), fx.userId());
        assertThat(codeOf(failed)).isEqualTo(ErrorCode.INSUFFICIENT_STOCK.getCode());
        assertThat(idempotencyRecordRepository.count()).isZero();

        ProductOption option = productOptionRepository.findById(fx.optionId()).orElseThrow();
        option.increaseStock(5);
        productOptionRepository.save(option);

        // when
        MockHttpServletResponse retried = perform(orderRequest(fx, 2).header(IdempotencyRequest.HEADER, "key-after-restock"), fx.userId());

        // then
        assertThat(retried.getStatus()).isEqualTo(201);
        assertThat(stockOf(fx.optionId())).isEqualTo(6 - 2);
    }

    @Test
    @DisplayName("키 없이 보내면 이전처럼 요청마다 주문이 생긴다")
    void createsOrderPerRequest_withoutKey() throws Exception {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);
        long ordersBefore = orderRepository.count();

        // when
        perform(orderRequest(fx, 1), fx.userId());
        perform(orderRequest(fx, 1), fx.userId());

        // then
        assertThat(orderRepository.count()).isEqualTo(ordersBefore + 2);
        assertThat(stockOf(fx.optionId())).isEqualTo(INITIAL_STOCK - 2);
        assertThat(idempotencyRecordRepository.count()).isZero();
    }

    @Test
    @DisplayName("형식이 잘못된 키는 400 으로 거절하고 주문을 만들지 않는다")
    void rejectsMalformedKey() throws Exception {
        // given
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(INITIAL_STOCK);

        // when
        MockHttpServletResponse response = perform(orderRequest(fx, 1).header(IdempotencyRequest.HEADER, "bad key!"), fx.userId());

        // then
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(codeOf(response)).isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY.getCode());
        assertThat(stockOf(fx.optionId())).isEqualTo(INITIAL_STOCK);
    }

    private MockHttpServletRequestBuilder orderRequest(OrderFixtureFactory.Fixture fx, int quantity) {
        return post(ORDER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"addressId": %d, "details": [{"productOptionId": %d, "quantity": %d}]}
                        """.formatted(fx.addressId(), fx.optionId(), quantity));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, Long userId) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, UserRole.USER);
        return mockMvc.perform(request.session(session)).andReturn().getResponse();
    }

    private Long orderIdOf(MockHttpServletResponse response) throws Exception {
        return ((Number) JsonPath.read(response.getContentAsString(), "$.orderId")).longValue();
    }

    private String codeOf(MockHttpServletResponse response) throws Exception {
        return JsonPath.read(response.getContentAsString(), "$.code");
    }

    private int stockOf(Long optionId) {
        return productOptionRepository.findById(optionId).orElseThrow().getStockQuantity();
    }
}
