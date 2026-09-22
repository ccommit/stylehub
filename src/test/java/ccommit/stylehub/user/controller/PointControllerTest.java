package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.enums.PointType;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.service.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 보유 포인트 조회 API(GET /api/v1/users/me/points)의 인증·인가·본인 이력 한정·커서 페이징을 HTTP 계층에서 검증한다.
 * AuthInterceptor·RoleCheckInterceptor·/api/v1 프리픽스가 실제로 조합돼야 401/403 이 기대한 코드로 나가므로 전체 컨텍스트 위에 MockMvc 를 올린다.
 * </p>
 */
@SpringBootTest
class PointControllerTest {

    private static final String URL = "/api/v1/users/me/points";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private MockMvc mockMvc;

    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        fixtureFactory.deleteByIds(orderIds, productIds, userIds);
        orderIds.clear();
        productIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("비로그인 요청은 401(A001)로 거절된다")
    void returns401_whenNotLoggedIn() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = UserRole.class, names = {"STORE", "ADMIN"})
    @DisplayName("구매자(USER)가 아닌 역할 세션은 403(A002)으로 거절된다")
    void returns403_whenNotUserRole(UserRole role) throws Exception {
        mockMvc.perform(get(URL).session(session(1L, role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A002"));
    }

    @Test
    @DisplayName("본인의 잔액과 이력만 최신순으로 돌려주고, 주문 없는 적립 이력은 주문 ID 가 null 로 포함된다")
    void returnsOnlyOwnBalanceAndHistories() throws Exception {
        // given — 나: 웰컴 1000P 적립 후 300P 사용 주문, 다른 사용자: 웰컴 1000P
        OrderFixtureFactory.Fixture me = newFixture();
        userService.rewardLoginPoint(me.userId(), LocalDate.now());
        Long orderId = orderService.placeOrder(me.userId(), new OrderCreateRequest(
                me.addressId(), List.of(new OrderDetailRequest(me.optionId(), 1)), null, 300)).orderId();
        orderIds.add(orderId);

        OrderFixtureFactory.Fixture other = newFixture();
        userService.rewardLoginPoint(other.userId(), LocalDate.now());

        // when & then
        mockMvc.perform(get(URL).session(session(me.userId(), UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointBalance").value(700))
                .andExpect(jsonPath("$.histories.items", hasSize(2)))
                .andExpect(jsonPath("$.histories.items[0].pointType").value(PointType.USE.name()))
                .andExpect(jsonPath("$.histories.items[0].amount").value(-300))
                .andExpect(jsonPath("$.histories.items[0].balanceSnapshot").value(700))
                .andExpect(jsonPath("$.histories.items[0].orderId").value(orderId))
                .andExpect(jsonPath("$.histories.items[0].createdAt").exists())
                .andExpect(jsonPath("$.histories.items[1].pointType").value(PointType.WELCOME.name()))
                .andExpect(jsonPath("$.histories.items[1].amount").value(1000))
                .andExpect(jsonPath("$.histories.items[1].balanceSnapshot").value(1000))
                .andExpect(jsonPath("$.histories.items[1].orderId").value(nullValue()))
                .andExpect(jsonPath("$.histories.hasNext").value(false));
    }

    @Test
    @DisplayName("size 만큼 최신 이력을 주고 다음 커서로 이어서 조회하면 남은 이력을 준다")
    void pagesHistoriesWithCursor() throws Exception {
        // given — 웰컴(어제) → 일일(오늘) 적립 2건
        OrderFixtureFactory.Fixture me = newFixture();
        userService.rewardLoginPoint(me.userId(), LocalDate.now().minusDays(1));
        userService.rewardLoginPoint(me.userId(), LocalDate.now());
        MockHttpSession session = session(me.userId(), UserRole.USER);

        // when & then — 첫 페이지
        String firstPage = mockMvc.perform(get(URL).param("size", "1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointBalance").value(1010))
                .andExpect(jsonPath("$.histories.items", hasSize(1)))
                .andExpect(jsonPath("$.histories.items[0].pointType").value(PointType.DAILY_LOGIN.name()))
                .andExpect(jsonPath("$.histories.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        Number nextCursor = JsonPath.read(firstPage, "$.histories.nextCursor");

        // when & then — 다음 페이지
        mockMvc.perform(get(URL).param("size", "1").param("cursor", String.valueOf(nextCursor.longValue())).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.histories.items", hasSize(1)))
                .andExpect(jsonPath("$.histories.items[0].pointType").value(PointType.WELCOME.name()))
                .andExpect(jsonPath("$.histories.hasNext").value(false))
                .andExpect(jsonPath("$.histories.nextCursor").value(nullValue()));
    }

    private OrderFixtureFactory.Fixture newFixture() {
        OrderFixtureFactory.Fixture fx = fixtureFactory.create(10);
        userIds.add(fx.userId());
        userIds.add(fx.storeId());
        productIds.add(fx.productId());
        return fx;
    }

    private MockHttpSession session(Long userId, UserRole role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
        return session;
    }
}
