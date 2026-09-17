package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.enums.CouponStatus;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.CouponIssueCounter;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.coupon.validator.CouponValidator;
import ccommit.stylehub.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 * @modified 2026/09/15 by WonJin - test: 발급 흐름 변경(동기 저장 + Redis 보상 + DB 조건부 UPDATE)에 맞춰 발급 테스트 재작성
 * @modified 2026/09/17 by WonJin - test: 행 락 기반 카운터 초기화, 한도 도달 시 무효화, 보상 실패 시 원래 예외 보존, Redis 장애 503, 수정·비활성화 락, 재동기화 검증 추가
 *
 * <p>
 * CouponService의 선착순 발급 분기(사전 거절·카운터 초기화·한도 도달·보상)와 이벤트 수정·재동기화, 내 쿠폰 조회를 검증하는 단위 테스트이다.
 * 실제 Redis·DB 동시성은 CouponIssueConcurrencyTest에서 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COUPON_EVENT_ID = 100L;
    private static final LocalDateTime EXPIRED_AT = LocalDateTime.of(2026, 12, 31, 23, 59);

    @Mock
    private CouponEventRepository couponEventRepository;

    @Mock
    private UserCouponRepository userCouponRepository;

    @Mock
    private CouponValidator couponValidator;

    @Mock
    private CouponIssueCounter couponIssueCounter;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private CouponService couponService;

    @Nested
    @DisplayName("issueCoupon (선착순 발급)")
    class IssueCoupon {

        private User user;
        private CouponEvent event;

        @BeforeEach
        void setUp() {
            user = mock(User.class);
            given(user.getUserId()).willReturn(USER_ID);
            event = mock(CouponEvent.class);
            given(event.remainingIssueCount()).willReturn(7);
            given(event.getExpiredAt()).willReturn(EXPIRED_AT);
            given(couponEventRepository.findById(COUPON_EVENT_ID)).willReturn(Optional.of(event));
            given(couponIssueCounter.precheck(COUPON_EVENT_ID, USER_ID)).willReturn(CouponIssueCounter.Result.AVAILABLE);
            given(couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, EXPIRED_AT)).willReturn(CouponIssueCounter.Result.AVAILABLE);
            given(couponEventRepository.increaseIssuedCount(COUPON_EVENT_ID)).willReturn(1);
            stubTransactionTemplatePassthrough();
        }

        @Test
        @DisplayName("자리를 확보하고 발급 수 증가와 발급 기록 저장까지 마치면 Redis 를 되돌리지 않는다")
        void issuesCouponSuccessfully() {
            couponService.issueCoupon(user, COUPON_EVENT_ID);

            then(couponEventRepository).should().increaseIssuedCount(COUPON_EVENT_ID);
            then(userCouponRepository).should().save(any(UserCoupon.class));
            then(couponIssueCounter).should(never()).release(anyLong(), anyLong());
            then(couponIssueCounter).should(never()).invalidateOnLimitReached(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Redis 사전 확인에서 매진이면 쿠폰 이벤트를 조회하지 않고 COUPON_SOLD_OUT 을 던진다")
        void rejectsSoldOutBeforeReadingDatabase() {
            given(couponIssueCounter.precheck(COUPON_EVENT_ID, USER_ID)).willReturn(CouponIssueCounter.Result.SOLD_OUT);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_SOLD_OUT);
            then(couponEventRepository).should(never()).findById(anyLong());
        }

        @Test
        @DisplayName("Redis 사전 확인에서 이미 받은 사용자면 쿠폰 이벤트를 조회하지 않고 COUPON_ALREADY_ISSUED 를 던진다")
        void rejectsDuplicateBeforeReadingDatabase() {
            given(couponIssueCounter.precheck(COUPON_EVENT_ID, USER_ID)).willReturn(CouponIssueCounter.Result.ALREADY_ISSUED);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ALREADY_ISSUED);
            then(couponEventRepository).should(never()).findById(anyLong());
        }

        @Test
        @DisplayName("Redis 에서 자리 확보 결과로 매진을 받으면 아무것도 확보하지 않았으므로 해제하지 않는다")
        void doesNotRelease_whenReserveReturnsSoldOut() {
            given(couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, EXPIRED_AT)).willReturn(CouponIssueCounter.Result.SOLD_OUT);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_SOLD_OUT);
            then(couponIssueCounter).should(never()).release(anyLong(), anyLong());
            then(transactionTemplate).should(never()).execute(any());
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰 이벤트면 자리를 확보하지 않고 COUPON_NOT_FOUND 를 던진다")
        void throwsNotFound_whenEventMissing() {
            given(couponEventRepository.findById(COUPON_EVENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_NOT_FOUND);
            then(couponIssueCounter).should(never()).reserve(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("validator 가 만료 예외를 던지면 자리를 확보하지 않는다")
        void doesNotReserve_whenValidatorThrows() {
            willThrow(new BusinessException(ErrorCode.COUPON_EXPIRED))
                    .given(couponValidator).validateIssuable(event);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_EXPIRED);
            then(couponIssueCounter).should(never()).reserve(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("카운터가 없으면 앞서 읽은 이벤트가 아니라 행 락으로 다시 읽은 값으로 초기화한 뒤 자리를 확보한다")
        void initializesCounterFromLockedRow_whenCounterMissing() {
            LocalDateTime extendedExpiredAt = EXPIRED_AT.plusDays(3);
            CouponEvent locked = mock(CouponEvent.class);
            given(locked.remainingIssueCount()).willReturn(3);
            given(locked.getExpiredAt()).willReturn(extendedExpiredAt);
            given(couponEventRepository.findByIdWithLock(COUPON_EVENT_ID)).willReturn(Optional.of(locked));
            given(couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, EXPIRED_AT))
                    .willReturn(CouponIssueCounter.Result.NOT_INITIALIZED, CouponIssueCounter.Result.AVAILABLE);

            couponService.issueCoupon(user, COUPON_EVENT_ID);

            then(entityManager).should().refresh(locked, LockModeType.PESSIMISTIC_WRITE);
            then(couponIssueCounter).should().initializeIfAbsent(COUPON_EVENT_ID, 3, extendedExpiredAt);
            then(couponIssueCounter).should(never()).initializeIfAbsent(COUPON_EVENT_ID, 7, EXPIRED_AT);
            then(userCouponRepository).should().save(any(UserCoupon.class));
        }

        @Test
        @DisplayName("초기화를 재시도해도 카운터가 계속 비어 있으면 500 이 아니라 503 을 던지고 해제하지 않는다")
        void respondsTemporarilyUnavailable_whenCounterStaysUninitialized() {
            CouponEvent locked = mock(CouponEvent.class);
            given(locked.getExpiredAt()).willReturn(EXPIRED_AT);
            given(couponEventRepository.findByIdWithLock(COUPON_EVENT_ID)).willReturn(Optional.of(locked));
            given(couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, EXPIRED_AT))
                    .willReturn(CouponIssueCounter.Result.NOT_INITIALIZED);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE);
            then(couponIssueCounter).should(times(2)).initializeIfAbsent(anyLong(), anyInt(), any());
            then(couponIssueCounter).should(never()).release(anyLong(), anyLong());
            then(userCouponRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Redis 에 자리가 남아 있어도 DB 한도에 도달했으면 저장하지 않고 카운터를 0 으로 덮는 대신 무효화한다")
        void invalidatesCounter_whenDatabaseLimitReached() {
            given(couponEventRepository.increaseIssuedCount(COUPON_EVENT_ID)).willReturn(0);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_SOLD_OUT);
            then(userCouponRepository).should(never()).save(any());
            then(couponIssueCounter).should().invalidateOnLimitReached(COUPON_EVENT_ID, USER_ID);
            then(couponIssueCounter).should(never()).release(anyLong(), anyLong());
        }

        @Test
        @DisplayName("DB 한도 도달 뒤 카운터 무효화가 실패해도 COUPON_SOLD_OUT 을 그대로 던지고 실패는 suppressed 로 남긴다")
        void keepsSoldOut_whenInvalidationFails() {
            given(couponEventRepository.increaseIssuedCount(COUPON_EVENT_ID)).willReturn(0);
            BusinessException redisDown = redisUnavailable();
            willThrow(redisDown).given(couponIssueCounter).invalidateOnLimitReached(COUPON_EVENT_ID, USER_ID);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_SOLD_OUT)
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(redisDown));
        }

        @Test
        @DisplayName("DB 에 이미 발급 이력이 있어 유니크 제약에 걸리면 자리만 돌려주고 COUPON_ALREADY_ISSUED 를 던진다")
        void releasesSlotKeepingIssued_whenAlreadyIssuedInDatabase() {
            given(userCouponRepository.save(any(UserCoupon.class)))
                    .willThrow(new DataIntegrityViolationException("duplicate"));
            given(userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(USER_ID, COUPON_EVENT_ID))
                    .willReturn(true);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ALREADY_ISSUED);
            then(couponIssueCounter).should().releaseKeepingIssued(COUPON_EVENT_ID, USER_ID, EXPIRED_AT);
            then(couponIssueCounter).should(never()).release(anyLong(), anyLong());
        }

        @Test
        @DisplayName("그 밖의 이유로 저장에 실패하면 확보한 자리와 발급 기록을 되돌리고 예외를 그대로 던진다")
        void releasesSlot_whenSaveFails() {
            RuntimeException failure = new IllegalStateException("db down");
            given(userCouponRepository.save(any(UserCoupon.class))).willThrow(failure);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isSameAs(failure);
            then(couponIssueCounter).should().release(COUPON_EVENT_ID, USER_ID);
            then(couponIssueCounter).should(never()).initializeIfAbsent(anyLong(), anyInt(), any());
        }

        @Test
        @DisplayName("저장 실패 후 Redis 해제마저 실패해도 원래 저장 예외를 던지고 해제 실패는 suppressed 로 남긴다")
        void keepsOriginalException_whenReleaseFails() {
            RuntimeException failure = new IllegalStateException("db down");
            given(userCouponRepository.save(any(UserCoupon.class))).willThrow(failure);
            BusinessException redisDown = redisUnavailable();
            willThrow(redisDown).given(couponIssueCounter).release(COUPON_EVENT_ID, USER_ID);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isSameAs(failure);
            assertThat(failure.getSuppressed()).containsExactly(redisDown);
        }

        @Test
        @DisplayName("Redis 사전 확인에서 연결·타임아웃 장애가 나면 503 을 그대로 던지고 DB 를 조회하지 않는다")
        void rejectsWithServiceUnavailable_whenRedisDownOnPrecheck() {
            BusinessException redisDown = redisUnavailable();
            given(couponIssueCounter.precheck(COUPON_EVENT_ID, USER_ID)).willThrow(redisDown);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isSameAs(redisDown)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE);
            then(couponEventRepository).should(never()).findById(anyLong());
            then(couponIssueCounter).should(never()).release(anyLong(), anyLong());
        }

        @Test
        @DisplayName("자리 확보 호출이 결과 없이 실패하면(스크립트 실행 여부 모름) 해제를 시도하고 503 을 던진다")
        void triesRelease_whenReserveOutcomeUnknown() {
            BusinessException timeout = redisUnavailable();
            given(couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, EXPIRED_AT)).willThrow(timeout);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isSameAs(timeout);
            then(couponIssueCounter).should().release(COUPON_EVENT_ID, USER_ID);
            then(transactionTemplate).should(never()).execute(any());
        }

        @Test
        @DisplayName("자리 확보와 해제가 모두 실패해도 자리 확보 예외를 던지고 해제 실패는 suppressed 로 남긴다")
        void keepsReserveException_whenReleaseAlsoFails() {
            BusinessException timeout = redisUnavailable();
            BusinessException releaseFailure = redisUnavailable();
            given(couponIssueCounter.reserve(COUPON_EVENT_ID, USER_ID, EXPIRED_AT)).willThrow(timeout);
            willThrow(releaseFailure).given(couponIssueCounter).release(COUPON_EVENT_ID, USER_ID);

            assertThatThrownBy(() -> couponService.issueCoupon(user, COUPON_EVENT_ID))
                    .isSameAs(timeout);
            assertThat(timeout.getSuppressed()).containsExactly(releaseFailure);
        }

        private BusinessException redisUnavailable() {
            return new BusinessException(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE,
                    new QueryTimeoutException("Redis command timed out"));
        }

        // execute·executeWithoutResult 는 목 객체에서 콜백을 실행하지 않으므로 직접 실행해 트랜잭션 안의 동작을 검증한다.
        private void stubTransactionTemplatePassthrough() {
            willAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            }).given(transactionTemplate).execute(any());
            willAnswer(invocation -> {
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(mock(TransactionStatus.class));
                return null;
            }).given(transactionTemplate).executeWithoutResult(any());
        }
    }

    @Nested
    @DisplayName("updateCouponEvent / deactivateCouponEvent (행 락으로 발급과 직렬화)")
    class UpdateCouponEvent {

        private CouponEvent event;

        @BeforeEach
        void setUp() {
            TransactionSynchronizationManager.initSynchronization();
            event = mock(CouponEvent.class);
            given(couponEventRepository.findByIdWithLock(COUPON_EVENT_ID)).willReturn(Optional.of(event));
        }

        @AfterEach
        void tearDown() {
            TransactionSynchronizationManager.clearSynchronization();
        }

        @Test
        @DisplayName("수정은 락 조회한 이벤트로 검증하고, 커밋 뒤에만 카운터를 무효화한다")
        void updatesWithLockedRow_andInvalidatesAfterCommit() {
            LocalDateTime now = LocalDateTime.now();
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(15, 0, now, now.plusDays(1));

            couponService.updateCouponEvent(COUPON_EVENT_ID, request);

            then(couponEventRepository).should(never()).findById(anyLong());
            then(entityManager).should().refresh(event, LockModeType.PESSIMISTIC_WRITE);
            then(couponValidator).should().validateUpdate(event, request);
            then(couponIssueCounter).should(never()).invalidate(anyLong());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            then(couponIssueCounter).should().invalidate(COUPON_EVENT_ID);
        }

        @Test
        @DisplayName("수량 축소 검증에 실패하면 수정하지 않고 카운터 무효화도 등록하지 않는다")
        void doesNotUpdate_whenValidationFails() {
            LocalDateTime now = LocalDateTime.now();
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(1, 0, now, now.plusDays(1));
            willThrow(new BusinessException(ErrorCode.INVALID_ISSUE_COUNT))
                    .given(couponValidator).validateUpdate(event, request);

            assertThatThrownBy(() -> couponService.updateCouponEvent(COUPON_EVENT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ISSUE_COUNT);
            then(event).should(never()).update(any(), any(), any(), any());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        @Test
        @DisplayName("비활성화도 락 조회한 이벤트로 수행한다")
        void deactivatesWithLockedRow() {
            couponService.deactivateCouponEvent(COUPON_EVENT_ID);

            then(couponEventRepository).should(never()).findById(anyLong());
            then(entityManager).should().refresh(event, LockModeType.PESSIMISTIC_WRITE);
            then(event).should().deactivate();
        }
    }

    @Nested
    @DisplayName("resyncIssueCounter (관리자 재동기화)")
    class ResyncIssueCounter {

        @Test
        @DisplayName("이벤트가 있으면 카운터와 발급자 기록을 함께 지운다")
        void clearsCounterAndIssuedUsers() {
            given(couponEventRepository.existsById(COUPON_EVENT_ID)).willReturn(true);

            couponService.resyncIssueCounter(COUPON_EVENT_ID);

            then(couponIssueCounter).should().clear(COUPON_EVENT_ID);
        }

        @Test
        @DisplayName("이벤트가 없으면 COUPON_NOT_FOUND 를 던지고 Redis 를 건드리지 않는다")
        void throwsNotFound_whenEventMissing() {
            given(couponEventRepository.existsById(COUPON_EVENT_ID)).willReturn(false);

            assertThatThrownBy(() -> couponService.resyncIssueCounter(COUPON_EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_NOT_FOUND);
            then(couponIssueCounter).should(never()).clear(anyLong());
        }
    }

    @Nested
    @DisplayName("getMyCoupons (내 쿠폰 목록 조회)")
    class GetMyCoupons {

        @Test
        @DisplayName("보유 쿠폰이 있으면 UserCouponResponse 리스트로 변환해 반환한다")
        void returnsMyCouponList() {
            // given
            Long userId = 1L;
            UserCoupon uc = createUserCouponMock(11L, 100L);
            given(userCouponRepository.findByUserIdWithCouponEvent(userId)).willReturn(List.of(uc));

            // when
            List<UserCouponResponse> result = couponService.getMyCoupons(userId);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).userCouponId()).isEqualTo(11L);
            assertThat(result.get(0).couponEventId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("보유 쿠폰이 없으면 빈 리스트를 반환한다")
        void returnsEmptyList_whenNoCoupons() {
            // given
            given(userCouponRepository.findByUserIdWithCouponEvent(1L)).willReturn(List.of());

            // when
            List<UserCouponResponse> result = couponService.getMyCoupons(1L);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ===== Helper =====

    private UserCoupon createUserCouponMock(Long userCouponId, Long couponEventId) {
        CouponEvent event = mock(CouponEvent.class);
        given(event.getCouponEventId()).willReturn(couponEventId);
        given(event.getName()).willReturn("10% 할인");
        given(event.getStoreUser()).willReturn(null);   // 플랫폼 쿠폰
        given(event.getDiscountType()).willReturn(DiscountType.RATE);
        given(event.getDiscountValue()).willReturn(10);
        given(event.getMinOrderAmount()).willReturn(0);
        given(event.getStartedAt()).willReturn(LocalDateTime.now().minusDays(1));
        given(event.getExpiredAt()).willReturn(LocalDateTime.now().plusDays(7));

        UserCoupon uc = mock(UserCoupon.class);
        given(uc.getUserCouponId()).willReturn(userCouponId);
        given(uc.getCouponEvent()).willReturn(event);
        given(uc.getStatus()).willReturn(CouponStatus.UNUSED);
        given(uc.getUsedAt()).willReturn(null);
        return uc;
    }
}
