package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.user.entity.PointHistory;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.PointType;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.LoginPointState;
import ccommit.stylehub.user.repository.PointHistoryQueryRepository;
import ccommit.stylehub.user.repository.PointHistoryRepository;
import ccommit.stylehub.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * PointService 의 분기와 이력 기록 규칙(유형, 금액 부호, 잔여 포인트 출처)을 검증하는 단위 테스트이다.
 * 원자 UPDATE 의 동시성은 목으로 재현할 수 없어 LoginPointPersistenceTest·OrderPointIntegrationTest 가 실제 DB 로 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PointServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 99L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    @Mock
    private UserRepository userRepository;

    @Mock
    private PointHistoryRepository pointHistoryRepository;

    @Mock
    private PointHistoryQueryRepository pointHistoryQueryRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private PointService pointService;

    @Nested
    @DisplayName("deductPoint / recordPointUse (주문 포인트 사용)")
    class UsePoint {

        @Test
        @DisplayName("조건부 차감이 0건이면 잔액 부족(INSUFFICIENT_POINT)으로 거절한다")
        void throwsInsufficientPoint_whenConditionalUpdateMatchesNothing() {
            // given
            given(userRepository.deductPointIfEnough(USER_ID, 5_000)).willReturn(0);

            // when / then
            assertThatThrownBy(() -> pointService.deductPoint(USER_ID, 5_000))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_POINT);
        }

        @ParameterizedTest(name = "[{index}] amount={0}")
        @ValueSource(ints = {0, -100})
        @DisplayName("0 이하 금액은 UPDATE 없이 INVALID_INPUT 으로 거절한다 (음수 차감은 잔액을 늘린다)")
        void rejectsNonPositiveAmount(int amount) {
            assertThatThrownBy(() -> pointService.deductPoint(USER_ID, amount))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
            assertThatThrownBy(() -> pointService.restoreUsedPoint(USER_ID, ORDER_ID, amount))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

            then(userRepository).should(never()).deductPointIfEnough(any(), anyInt());
            then(userRepository).should(never()).addPoint(any(), anyInt());
        }

        // 잔여 포인트를 User 엔티티에서 읽으면 같은 트랜잭션에 먼저 로딩된 오래된 값이 기록될 수 있다.
        @Test
        @DisplayName("사용 이력은 USE·음수 금액이고, 잔여 포인트는 엔티티가 아니라 DB 스칼라 조회 값이다")
        void recordsNegativeUseWithScalarBalance() {
            // given
            given(userRepository.findPointBalance(USER_ID)).willReturn(7_000);
            stubReferences();

            // when
            pointService.recordPointUse(USER_ID, ORDER_ID, 3_000);

            // then
            PointHistory saved = captureSavedHistory();
            assertThat(saved.getPointType()).isEqualTo(PointType.USE);
            assertThat(saved.getAmount()).isEqualTo(-3_000);
            assertThat(saved.getBalanceSnapshot()).isEqualTo(7_000);
            assertThat(saved.getOrder()).isNotNull();
        }
    }

    @Nested
    @DisplayName("restoreUsedPoint (주문 취소 복구)")
    class RestoreUsedPoint {

        @Test
        @DisplayName("복구는 원자 UPDATE 로 더하고 USE·양수 금액(사용 취소 역분개)으로 기록한다")
        void addsAtomicallyAndRecordsPositiveUse() {
            // given
            given(userRepository.addPoint(USER_ID, 3_000)).willReturn(1);
            given(userRepository.findPointBalance(USER_ID)).willReturn(10_000);
            stubReferences();

            // when
            pointService.restoreUsedPoint(USER_ID, ORDER_ID, 3_000);

            // then
            PointHistory saved = captureSavedHistory();
            assertThat(saved.getPointType()).isEqualTo(PointType.USE);
            assertThat(saved.getAmount()).isEqualTo(3_000);
            assertThat(saved.getBalanceSnapshot()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("대상 사용자가 없어 UPDATE 가 0건이면 USER_NOT_FOUND 로 실패하고 이력을 남기지 않는다")
        void throwsUserNotFound_whenNoRowUpdated() {
            // given
            given(userRepository.addPoint(USER_ID, 3_000)).willReturn(0);

            // when / then
            assertThatThrownBy(() -> pointService.restoreUsedPoint(USER_ID, ORDER_ID, 3_000))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
            then(pointHistoryRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("rewardLoginPoint (로그인 적립)")
    class RewardLoginPoint {

        @Test
        @DisplayName("마지막 적립일이 없으면 첫 로그인 조건부 UPDATE 로 1000P 를 적립하고 WELCOME 이력을 남긴다")
        void rewardsWelcome_whenNeverRewarded() {
            // given
            given(userRepository.findLoginPointState(USER_ID)).willReturn(Optional.of(new LoginPointState(UserRole.USER, null)));
            given(userRepository.rewardFirstLoginPoint(USER_ID, 1_000, TODAY)).willReturn(1);
            given(userRepository.findPointBalance(USER_ID)).willReturn(1_000);
            stubReferences();

            // when
            pointService.rewardLoginPoint(USER_ID, TODAY);

            // then
            PointHistory saved = captureSavedHistory();
            assertThat(saved.getPointType()).isEqualTo(PointType.WELCOME);
            assertThat(saved.getAmount()).isEqualTo(1_000);
            assertThat(saved.getBalanceSnapshot()).isEqualTo(1_000);
            assertThat(saved.getOrder()).isNull();
            then(userRepository).should(never()).rewardDailyLoginPoint(any(), anyInt(), any());
        }

        @Test
        @DisplayName("마지막 적립일이 어제면 일일 조건부 UPDATE 로 10P 를 적립하고 DAILY_LOGIN 이력을 남긴다")
        void rewardsDaily_whenLastRewardedBeforeToday() {
            // given
            given(userRepository.findLoginPointState(USER_ID))
                    .willReturn(Optional.of(new LoginPointState(UserRole.USER, TODAY.minusDays(1))));
            given(userRepository.rewardDailyLoginPoint(USER_ID, 10, TODAY)).willReturn(1);
            given(userRepository.findPointBalance(USER_ID)).willReturn(1_010);
            stubReferences();

            // when
            pointService.rewardLoginPoint(USER_ID, TODAY);

            // then
            PointHistory saved = captureSavedHistory();
            assertThat(saved.getPointType()).isEqualTo(PointType.DAILY_LOGIN);
            assertThat(saved.getAmount()).isEqualTo(10);
            assertThat(saved.getBalanceSnapshot()).isEqualTo(1_010);
        }

        // 조회와 UPDATE 사이에 다른 로그인이 먼저 적립하면 UPDATE 조건이 거짓이 되어 0건이 된다.
        @Test
        @DisplayName("조회 뒤 다른 로그인이 먼저 적립해 조건부 UPDATE 가 0건이면 이력을 남기지 않는다")
        void skipsHistory_whenAnotherLoginRewardedFirst() {
            // given
            given(userRepository.findLoginPointState(USER_ID)).willReturn(Optional.of(new LoginPointState(UserRole.USER, null)));
            given(userRepository.rewardFirstLoginPoint(USER_ID, 1_000, TODAY)).willReturn(0);

            // when
            pointService.rewardLoginPoint(USER_ID, TODAY);

            // then
            then(pointHistoryRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("오늘 이미 적립됐으면 쓰기 쿼리 없이 끝낸다")
        void doesNothing_whenAlreadyRewardedToday() {
            // given
            given(userRepository.findLoginPointState(USER_ID)).willReturn(Optional.of(new LoginPointState(UserRole.USER, TODAY)));

            // when
            pointService.rewardLoginPoint(USER_ID, TODAY);

            // then
            then(userRepository).should(never()).rewardFirstLoginPoint(any(), anyInt(), any());
            then(userRepository).should(never()).rewardDailyLoginPoint(any(), anyInt(), any());
            then(pointHistoryRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("관리자는 적립하지 않는다")
        void skipsAdmin() {
            // given
            given(userRepository.findLoginPointState(USER_ID)).willReturn(Optional.of(new LoginPointState(UserRole.ADMIN, null)));

            // when
            pointService.rewardLoginPoint(USER_ID, TODAY);

            // then
            then(userRepository).should(never()).rewardFirstLoginPoint(any(), anyInt(), any());
            then(pointHistoryRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("존재하지 않는 사용자는 USER_NOT_FOUND 로 실패한다")
        void throwsUserNotFound_whenUserMissing() {
            // given
            given(userRepository.findLoginPointState(USER_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> pointService.rewardLoginPoint(USER_ID, TODAY))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }
    }

    // ===== Helper =====

    private void stubReferences() {
        given(userRepository.getReferenceById(USER_ID)).willReturn(mock(User.class));
        given(entityManager.getReference(Order.class, ORDER_ID)).willReturn(mock(Order.class));
    }

    private PointHistory captureSavedHistory() {
        ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
        then(pointHistoryRepository).should().save(captor.capture());
        return captor.getValue();
    }
}
