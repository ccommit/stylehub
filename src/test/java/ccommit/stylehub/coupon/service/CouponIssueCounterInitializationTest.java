package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.CouponIssueCounter;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 선착순 발급 카운터를 초기화할 때, 요청 앞부분에서 락 없이 읽은 오래된 이벤트 값이 아니라 행 락 시점의 DB 값을 쓰는지 검증한다.
 * OSIV 가 켜진 웹 요청에서는 앞서 읽은 엔티티가 영속성 컨텍스트에 남아 락 조회 결과로 갱신되지 않으므로, MockMvc 로 실제 요청 경로를 탄다.
 * </p>
 */
@SpringBootTest
// 검증하려는 경로(요청 범위 영속성 컨텍스트에 남은 엔티티)는 OSIV 가 켜져 있어야 생긴다. 운영 설정·환경 변수와 무관하게 켠다.
@TestPropertySource(properties = "spring.jpa.open-in-view=true")
class CouponIssueCounterInitializationTest {

    @MockitoSpyBean
    private CouponIssueCounter couponIssueCounter;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CouponApplicationService couponApplicationService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponEventRepository couponEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanUp() {
        for (Long eventId : eventIds) {
            redisTemplate.delete(List.of(CouponIssueCounter.counterKey(eventId), CouponIssueCounter.issuedUsersKey(eventId)));
            jdbcTemplate.update("DELETE FROM user_coupons WHERE coupon_event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM coupon_events WHERE coupon_event_id = ?", eventId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
        }
    }

    @Test
    @DisplayName("발급 요청이 이벤트를 읽은 뒤 관리자가 수량을 늘려도, 카운터는 행 락 시점의 DB 값으로 만들어져 늘린 수량이 발급된다")
    void initializesCounterFromLockedRow_notFromEntityReadEarlierInRequest() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        // 시작일은 과거로 만들 수 없으므로(허용 오차 1분) 10초 전으로 두고, 수정 때도 같은 시작일을 보내 진행 중 이벤트의 수량만 바꾼다.
        LocalDateTime startedAt = now.minusSeconds(10);
        Long eventId = createEvent(5, startedAt, now.plusDays(1));
        for (int i = 0; i < 5; i++) {
            couponApplicationService.issueCoupon(createUser(), eventId);
        }
        // Redis 유실·재동기화로 카운터가 비어 있는 상태 — 다음 요청이 카운터를 DB 기준으로 다시 만든다.
        couponIssueCounter.clear(eventId);

        Long latecomer = createUser();
        AtomicBoolean persistenceContextBoundToRequest = new AtomicBoolean();
        AtomicBoolean updatedDuringRequest = new AtomicBoolean();
        // 요청이 이벤트를 읽은 뒤(발급 수 5/5) 자리를 확보하기 직전에, 관리자가 수량을 8 로 늘리고 커밋한다.
        willAnswer(invocation -> {
            if (updatedDuringRequest.compareAndSet(false, true)) {
                persistenceContextBoundToRequest.set(TransactionSynchronizationManager.hasResource(entityManagerFactory));
                CompletableFuture.runAsync(() -> couponService.updateCouponEvent(eventId,
                        new CouponEventUpdateRequest(8, 0, startedAt, now.plusDays(1)))
                ).get(10, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).given(couponIssueCounter).reserve(eq(eventId), eq(latecomer), any());

        mockMvc.perform(post("/api/v1/coupon-events/{couponEventId}/issue", eventId).session(userSession(latecomer)))
                .andExpect(status().isOk());

        assertThat(persistenceContextBoundToRequest)
                .as("요청 범위 영속성 컨텍스트(OSIV)가 없으면 이 테스트는 검증하려는 경로를 타지 않는다")
                .isTrue();
        assertThat(issuedRows(eventId)).isEqualTo(6);
        assertThat(redisTemplate.opsForValue().get(CouponIssueCounter.counterKey(eventId))).isEqualTo("2");
    }

    private Long createEvent(int issueCount, LocalDateTime startedAt, LocalDateTime expiredAt) {
        Long eventId = couponService.createPlatformCouponEvent(new CouponEventCreateRequest(
                "초기화테스트", DiscountType.FIXED, 1000, 0, issueCount, startedAt, expiredAt
        )).couponEventId();
        eventIds.add(eventId);
        return eventId;
    }

    private Long createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 19);
        User user = userRepository.save(User.create(
                "i" + suffix, "i" + suffix + "@test.com", "password", LocalDate.of(1995, 1, 1), UserRole.USER
        ));
        userIds.add(user.getUserId());
        return user.getUserId();
    }

    private MockHttpSession userSession(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, UserRole.USER);
        return session;
    }

    private int issuedRows(Long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE coupon_event_id = ?", Integer.class, eventId);
    }
}
