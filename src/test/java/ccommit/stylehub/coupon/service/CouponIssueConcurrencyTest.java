package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.CouponIssueCounter;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/15
 * @modified 2026/09/17 by WonJin - test: 생성 데이터·Redis 키 정리, UUID 사용자명, 발급 중 수량 수정·수정 직후 카운터 복구·재동기화 API 시나리오 추가
 * @modified 2026/09/17 by WonJin - test: 시작일 과거 불가 규칙에 맞춰 생성 시작일을 허용 오차 안으로, 수량 수정은 저장된 기간을 그대로 보내도록 변경
 *
 * <p>
 * 선착순 쿠폰 발급을 실제 Redis와 DB로 동시에 호출해, Redis 유실·오염이나 수량 변경에도 초과 없이 정확히 한도만큼 발급되는지 검증하는 통합 테스트이다.
 * 락 의미론은 운영(MySQL)이 아닌 H2 기준이라는 한계가 있다.
 * </p>
 */
@SpringBootTest
class CouponIssueConcurrencyTest {

    private static final int THREAD_POOL_SIZE = 32;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CouponApplicationService couponApplicationService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponIssueCounter couponIssueCounter;

    @Autowired
    private CouponEventRepository couponEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    // 커밋된 데이터라 직접 지우며, 외래 키 방향에 따라 user_coupons부터 지운다.
    @AfterEach
    void cleanUp() {
        List<Object[]> eventArgs = eventIds.stream().map(id -> new Object[]{id}).toList();
        List<Object[]> userArgs = userIds.stream().map(id -> new Object[]{id}).toList();
        jdbcTemplate.batchUpdate("DELETE FROM user_coupons WHERE coupon_event_id = ?", eventArgs);
        jdbcTemplate.batchUpdate("DELETE FROM coupon_events WHERE coupon_event_id = ?", eventArgs);
        jdbcTemplate.batchUpdate("DELETE FROM users WHERE user_id = ?", userArgs);
        for (Long eventId : eventIds) {
            redisTemplate.delete(List.of(CouponIssueCounter.counterKey(eventId), CouponIssueCounter.issuedUsersKey(eventId)));
        }
    }

    @Test
    @DisplayName("한도 50장에 200명이 동시에 요청하면 정확히 50장만 발급되고 DB 발급 수와 Redis 카운터가 일치한다")
    void issuesExactlyLimit_underConcurrentRequests() throws InterruptedException {
        Long eventId = createEvent(50);
        List<Long> users = createUsers(200);

        Outcome outcome = issueConcurrently(eventId, users);

        outcome.assertNoUnexpectedErrors();
        assertThat(outcome.successCount()).isEqualTo(50);
        assertThat(outcome.count(ErrorCode.COUPON_SOLD_OUT)).isEqualTo(150);
        assertThat(issuedRows(eventId)).isEqualTo(50);
        assertThat(issuedCount(eventId)).isEqualTo(50);
        assertThat(counter(eventId)).isEqualTo("0");
    }

    @Test
    @DisplayName("같은 사용자가 동시에 20번 요청해도 1장만 발급된다")
    void issuesOnce_forSameUserConcurrentRequests() throws InterruptedException {
        Long eventId = createEvent(10);
        Long userId = createUsers(1).get(0);
        List<Long> sameUser = Collections.nCopies(20, userId);

        Outcome outcome = issueConcurrently(eventId, sameUser);

        outcome.assertNoUnexpectedErrors();
        assertThat(outcome.successCount()).isEqualTo(1);
        assertThat(outcome.count(ErrorCode.COUPON_ALREADY_ISSUED)).isEqualTo(19);
        assertThat(issuedRows(eventId)).isEqualTo(1);
        assertThat(counter(eventId)).isEqualTo("9");
    }

    @Test
    @DisplayName("Redis 데이터가 유실돼도 카운터를 DB 기준 남은 수량으로 다시 만들어 초과 발급하지 않는다")
    void doesNotOverIssue_afterRedisDataLoss() throws InterruptedException {
        Long eventId = createEvent(30);
        List<Long> earlyUsers = createUsers(20);
        issueConcurrently(eventId, earlyUsers).assertNoUnexpectedErrors();

        redisTemplate.delete(List.of(CouponIssueCounter.counterKey(eventId), CouponIssueCounter.issuedUsersKey(eventId)));

        for (Long userId : earlyUsers) {
            assertThat(issueAndGetErrorCode(eventId, userId)).isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
            assertThat(isIssuedUser(eventId, userId)).as("DB 유니크 제약 경로에서 발급자 기록이 다시 채워진다").isTrue();
        }
        assertThat(counter(eventId)).as("이미 받은 사용자의 재요청은 확보한 자리를 돌려준다").isEqualTo("10");

        Outcome lateOutcome = issueConcurrently(eventId, createUsers(40));

        lateOutcome.assertNoUnexpectedErrors();
        assertThat(lateOutcome.successCount()).isEqualTo(10);
        assertThat(issuedRows(eventId)).isEqualTo(30);
        assertThat(issuedCount(eventId)).isEqualTo(30);
        assertThat(duplicatedUsers(eventId)).isZero();
    }

    @Test
    @DisplayName("Redis 카운터가 실제보다 크게 틀어져 있어도 DB 조건부 UPDATE 가 한도를 지키고, 카운터는 DB 기준으로 복구된다")
    void databaseEnforcesLimit_whenRedisCounterIsWrong() throws InterruptedException {
        Long eventId = createEvent(10);
        redisTemplate.opsForValue().set(CouponIssueCounter.counterKey(eventId), "100");

        Outcome outcome = issueConcurrently(eventId, createUsers(50));

        outcome.assertNoUnexpectedErrors();
        assertThat(outcome.successCount()).isEqualTo(10);
        // 틀어진 카운터로 DB 까지 온 요청은 매진으로 끝나며 카운터를 지운다. 그 직후 재초기화와 겹친 요청은 503 일 수 있다.
        assertThat(outcome.count(ErrorCode.COUPON_SOLD_OUT) + outcome.count(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE))
                .isEqualTo(40);
        assertThat(issuedRows(eventId)).isEqualTo(10);
        assertThat(issuedCount(eventId)).isEqualTo(10);

        assertThat(issueAndGetErrorCode(eventId, createUsers(1).get(0))).isEqualTo(ErrorCode.COUPON_SOLD_OUT);
        assertThat(counter(eventId)).isEqualTo("0");
    }

    @Test
    @DisplayName("관리자가 발급 수량을 늘리면 늘어난 수량만큼 추가로 발급된다")
    void reflectsIssueCountUpdate() throws InterruptedException {
        Long eventId = createEvent(10);
        issueConcurrently(eventId, createUsers(10)).assertNoUnexpectedErrors();

        couponService.updateCouponEvent(eventId, updateRequest(eventId, 15));

        Outcome outcome = issueConcurrently(eventId, createUsers(10));

        outcome.assertNoUnexpectedErrors();
        assertThat(outcome.successCount()).isEqualTo(5);
        assertThat(issuedRows(eventId)).isEqualTo(15);
        assertThat(issuedCount(eventId)).isEqualTo(15);
        assertThat(counter(eventId)).isEqualTo("0");
    }

    @Test
    @DisplayName("발급 요청이 쏟아지는 중에 관리자가 수량을 여러 번 늘려도 issued_count 가 덮어써지지 않고, 결국 마지막 수량만큼만 발급된다")
    void keepsDatabaseLimit_whenIssueCountUpdatedDuringFlood() throws InterruptedException {
        Long eventId = createEvent(100);
        List<Long> users = createUsers(400);
        AtomicInteger updateFailures = new AtomicInteger();
        // 발급 트랜잭션이 진행 중인 구간에 수정이 겹치도록 흐름 앞쪽에 끼워 넣는다. 락 없이 수정하면 이 구간에서 issued_count 가 되돌려진다.
        Map<Integer, Runnable> adminUpdates = Map.of(
                10, updateTask(eventId, 120, updateFailures),
                40, updateTask(eventId, 140, updateFailures),
                80, updateTask(eventId, 150, updateFailures)
        );

        Outcome outcome = issueConcurrentlyWithInterleavedTasks(eventId, users, adminUpdates);

        outcome.assertNoUnexpectedErrors();
        assertThat(updateFailures).hasValue(0);
        assertThat(issueCount(eventId)).isEqualTo(150);
        assertThat(issuedRows(eventId)).isEqualTo(issuedCount(eventId));
        assertThat(issuedCount(eventId)).isBetween(100, 150);
        assertThat(outcome.successCount()).isEqualTo(issuedCount(eventId));

        // 경합 중에 카운터가 잠시 어긋났더라도 스스로 복구되어, 남은 자리가 모두 발급된 뒤에만 매진이 된다.
        issueToNewUsersUntilSoldOut(eventId, 150 - issuedCount(eventId));
        assertThat(issuedRows(eventId)).isEqualTo(150);
        assertThat(issuedCount(eventId)).isEqualTo(150);
        assertThat(counter(eventId)).isEqualTo("0");
        assertThat(duplicatedUsers(eventId)).isZero();
    }

    @Test
    @DisplayName("수량을 늘린 직후 카운터는 새 수량 기준으로 복구되고, 수정 전 상태로 DB 한도에 도달한 오래된 요청이 뒤늦게 와도 0 으로 덮이지 않는다")
    void recoversCounterToNewQuantity_evenIfStaleLimitSignalArrivesAfterUpdate() throws InterruptedException {
        Long eventId = createEvent(10);
        issueConcurrently(eventId, createUsers(10)).assertNoUnexpectedErrors();
        assertThat(counter(eventId)).isEqualTo("0");

        couponService.updateCouponEvent(eventId, updateRequest(eventId, 15));
        assertThat(counter(eventId)).as("수정 커밋 뒤 카운터는 무효화된다").isNull();

        assertThat(issueAndGetErrorCode(eventId, createUsers(1).get(0))).isNull();
        assertThat(counter(eventId)).as("DB 기준 남은 수량(15 - 10)에서 1장 발급").isEqualTo("4");

        // 수정 전(10/10)에 DB 까지 와서 조건부 UPDATE 0건을 받은 오래된 요청의 처리가 지금 도착했다고 가정한다.
        couponIssueCounter.invalidateOnLimitReached(eventId, createUsers(1).get(0));
        assertThat(counter(eventId)).as("0 으로 덮지 않고 지우므로 다음 요청이 DB 기준으로 다시 만든다").isNull();

        Outcome outcome = issueConcurrently(eventId, createUsers(10));

        outcome.assertNoUnexpectedErrors();
        assertThat(outcome.successCount()).isEqualTo(4);
        assertThat(issuedRows(eventId)).isEqualTo(15);
        assertThat(issuedCount(eventId)).isEqualTo(15);
        assertThat(counter(eventId)).isEqualTo("0");
    }

    @Test
    @DisplayName("자리 누수를 재동기화 API 로 복구하면, 막혔던 사용자는 발급받고 초과 발급·중복 발급은 생기지 않는다")
    void resyncApiRecoversLeakedSlots_withoutOverOrDuplicateIssue() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        Long eventId = createEvent(30);
        List<Long> winners = createUsers(20);
        issueConcurrently(eventId, winners).assertNoUnexpectedErrors();

        // 자리를 확보한 뒤 DB 커밋 전에 서버가 종료된 상황을 재현한다: Redis 에만 예약이 남고 DB 에는 발급 기록이 없다.
        List<Long> leakedUsers = createUsers(3);
        LocalDateTime expiredAt = couponEventRepository.findById(eventId).orElseThrow().getExpiredAt();
        for (Long userId : leakedUsers) {
            couponIssueCounter.reserve(eventId, userId, expiredAt);
        }
        assertThat(counter(eventId)).isEqualTo("7");
        assertThat(issueAndGetErrorCode(eventId, leakedUsers.get(0)))
                .as("누수된 사용자는 쿠폰이 없는데도 이미 받은 것으로 막힌다")
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);

        mockMvc.perform(post("/api/v1/admin/coupon-events/{couponEventId}/issue-counter/resync", eventId)
                        .session(session(winners.get(0), UserRole.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/coupon-events/{couponEventId}/issue-counter/resync", eventId)
                        .session(session(1L, UserRole.ADMIN)))
                .andExpect(status().isOk());
        assertThat(counter(eventId)).isNull();
        assertThat(redisTemplate.hasKey(CouponIssueCounter.issuedUsersKey(eventId))).isFalse();

        for (Long userId : leakedUsers) {
            assertThat(issueAndGetErrorCode(eventId, userId)).isNull();
        }

        // 재동기화 직후에는 기존 발급자가 Redis 에 없으므로, 기존 발급자와 신규 사용자가 섞여 동시에 몰리는 경우를 검증한다.
        List<Long> newcomers = createUsers(20);
        List<Long> mixed = new ArrayList<>(winners);
        mixed.addAll(newcomers);
        Collections.shuffle(mixed);
        Outcome outcome = issueConcurrently(eventId, mixed);

        outcome.assertNoUnexpectedErrors();
        assertThat(issuedRowsOf(eventId, winners)).as("기존 발급자는 한 장도 더 받지 못한다").isEqualTo(20);
        assertThat(issuedRowsOf(eventId, leakedUsers)).isEqualTo(3);
        assertThat(issuedRowsOf(eventId, newcomers)).isEqualTo(outcome.successCount());
        assertThat(issuedRows(eventId)).isEqualTo(issuedCount(eventId));
        assertThat(duplicatedUsers(eventId)).isZero();

        // 기존 발급자의 재요청은 자리를 잠시 차지했다가 유니크 제약에 걸려 돌려준다. 그 순간에는 신규 사용자나 기존 발급자가
        // 매진을 볼 수 있지만 자리는 사라지지 않았으므로, 남은 수량을 채우면 정확히 한도에서 멈춰야 한다.
        issueToNewUsersUntilSoldOut(eventId, 30 - issuedCount(eventId));
        assertThat(issuedRows(eventId)).isEqualTo(30);
        assertThat(issuedCount(eventId)).isEqualTo(30);
        assertThat(counter(eventId)).isEqualTo("0");
        assertThat(duplicatedUsers(eventId)).isZero();
    }

    @Test
    @DisplayName("매진 뒤의 요청과 이미 받은 사용자의 요청은 DB 를 거치지 않고 Redis 에서 거절된다")
    void rejectsWithoutDatabase_afterSoldOut() throws InterruptedException {
        Long eventId = createEvent(5);
        List<Long> winners = createUsers(5);
        issueConcurrently(eventId, winners).assertNoUnexpectedErrors();

        // DB 를 거치면 비활성 이벤트라 COUPON_NOT_ACTIVE 가 나와야 한다. 다른 코드가 나오면 DB 앞에서 거절된 것이다.
        couponService.deactivateCouponEvent(eventId);

        assertThat(issueAndGetErrorCode(eventId, createUsers(1).get(0))).isEqualTo(ErrorCode.COUPON_SOLD_OUT);
        assertThat(issueAndGetErrorCode(eventId, winners.get(0))).isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
    }

    // 시작일은 과거로 만들 수 없으므로(허용 오차 1분) 허용 범위 안의 10초 전으로 두어, 생성 직후 바로 발급 가능한 상태로 만든다.
    private Long createEvent(int issueCount) {
        LocalDateTime now = LocalDateTime.now();
        Long eventId = couponService.createPlatformCouponEvent(new CouponEventCreateRequest(
                "동시성테스트", DiscountType.FIXED, 1000, 0, issueCount, now.minusSeconds(10), now.plusDays(1)
        )).couponEventId();
        eventIds.add(eventId);
        return eventId;
    }

    // 수량만 바꾸는 수정이다. 진행 중인 이벤트는 시작일을 그대로 보내야 수정할 수 있으므로 저장된 기간을 그대로 쓴다.
    private CouponEventUpdateRequest updateRequest(Long eventId, int issueCount) {
        CouponEvent event = couponEventRepository.findById(eventId).orElseThrow();
        return new CouponEventUpdateRequest(issueCount, 0, event.getStartedAt(), event.getExpiredAt());
    }

    private List<Long> createUsers(int count) {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 19);
            User user = userRepository.save(User.create(
                    "c" + suffix, "c" + suffix + "@test.com", "password", LocalDate.of(1995, 1, 1), UserRole.USER
            ));
            created.add(user.getUserId());
        }
        userIds.addAll(created);
        return created;
    }

    private Runnable updateTask(Long eventId, int issueCount, AtomicInteger failures) {
        return () -> {
            try {
                couponService.updateCouponEvent(eventId, updateRequest(eventId, issueCount));
            } catch (RuntimeException e) {
                failures.incrementAndGet();
            }
        };
    }

    private Outcome issueConcurrently(Long eventId, List<Long> requestUserIds) throws InterruptedException {
        return issueConcurrentlyWithInterleavedTasks(eventId, requestUserIds, Map.of());
    }

    // 키(발급 요청 순번) 직후 순서에 작업을 끼워 넣어, 같은 출발 신호로 발급 요청과 함께 실행한다.
    private Outcome issueConcurrentlyWithInterleavedTasks(Long eventId, List<Long> requestUserIds,
                                                          Map<Integer, Runnable> interleavedTasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        int taskCount = requestUserIds.size() + interleavedTasks.size();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(taskCount);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> failures = new ConcurrentHashMap<>();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < requestUserIds.size(); i++) {
                Long userId = requestUserIds.get(i);
                executor.submit(() -> {
                    try {
                        start.await();
                        couponApplicationService.issueCoupon(userId, eventId);
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        failures.computeIfAbsent(e.getErrorCode(), code -> new AtomicInteger()).incrementAndGet();
                    } catch (Throwable e) {
                        unexpected.add(e);
                    } finally {
                        done.countDown();
                    }
                });
                Runnable interleaved = interleavedTasks.get(i);
                if (interleaved != null) {
                    executor.submit(() -> {
                        try {
                            start.await();
                            interleaved.run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        return new Outcome(success.get(), failures, unexpected);
    }

    private ErrorCode issueAndGetErrorCode(Long eventId, Long userId) {
        try {
            couponApplicationService.issueCoupon(userId, eventId);
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    // 남은 자리 수만큼은 반드시 발급에 성공하고, 그다음 요청에서 매진이 되어야 한다(과소 발급·초과 발급 모두 실패로 드러남).
    private void issueToNewUsersUntilSoldOut(Long eventId, int expectedRemaining) {
        for (int i = 0; i < expectedRemaining; i++) {
            assertThat(issueAndGetErrorCode(eventId, createUsers(1).get(0)))
                    .as("남은 자리 %d 중 %d 번째 발급", expectedRemaining, i + 1)
                    .isNull();
        }
        assertThat(issueAndGetErrorCode(eventId, createUsers(1).get(0))).isEqualTo(ErrorCode.COUPON_SOLD_OUT);
    }

    private int issuedRows(Long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE coupon_event_id = ?", Integer.class, eventId);
    }

    private int issuedRowsOf(Long eventId, List<Long> targetUserIds) {
        return targetUserIds.stream()
                .mapToInt(userId -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_coupons WHERE coupon_event_id = ? AND user_id = ?",
                        Integer.class, eventId, userId))
                .sum();
    }

    private int issuedCount(Long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT issued_count FROM coupon_events WHERE coupon_event_id = ?", Integer.class, eventId);
    }

    private int issueCount(Long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT issue_count FROM coupon_events WHERE coupon_event_id = ?", Integer.class, eventId);
    }

    private int duplicatedUsers(Long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT user_id FROM user_coupons WHERE coupon_event_id = ? "
                        + "GROUP BY user_id HAVING COUNT(*) > 1) duplicated", Integer.class, eventId);
    }

    private String counter(Long eventId) {
        return redisTemplate.opsForValue().get(CouponIssueCounter.counterKey(eventId));
    }

    private boolean isIssuedUser(Long eventId, Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(CouponIssueCounter.issuedUsersKey(eventId), userId.toString()));
    }

    private MockHttpSession session(Long userId, UserRole role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
        return session;
    }

    private record Outcome(int successCount, Map<ErrorCode, AtomicInteger> failures, Queue<Throwable> unexpected) {

        int count(ErrorCode errorCode) {
            AtomicInteger value = failures.get(errorCode);
            return value == null ? 0 : value.get();
        }

        // 락 대기 초과 같은 예상 밖 예외가 섞이면 결과 수치가 우연히 맞아도 검증이 무의미하므로 먼저 확인한다.
        void assertNoUnexpectedErrors() {
            assertThat(unexpected).as("예상 밖 예외: %s", unexpected).isEmpty();
            assertThat(failures.keySet()).as("허용되지 않은 거절 코드").isSubsetOf(
                    ErrorCode.COUPON_SOLD_OUT, ErrorCode.COUPON_ALREADY_ISSUED, ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE);
        }
    }
}
