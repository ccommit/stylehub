package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.enums.CouponStatus;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.coupon.validator.CouponValidator;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 *
 * <p>
 * CouponService 의 단위 테스트이다.
 * 선착순 발급과 내 쿠폰 목록 조회를 대상으로 정상/에러 경로를 모두 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceTest {

    @Mock
    private CouponEventRepository couponEventRepository;

    @Mock
    private UserCouponRepository userCouponRepository;

    @Mock
    private CouponValidator couponValidator;

    @InjectMocks
    private CouponService couponService;

    @Nested
    @DisplayName("issueCoupon (선착순 발급)")
    class IssueCoupon {

        @Test
        @DisplayName("정상 발급 시 이벤트 수량 증가 + UserCoupon 저장이 호출된다")
        void issuesCouponSuccessfully() {
            // given
            Long userId = 1L;
            Long couponEventId = 100L;
            User user = mock(User.class);
            given(user.getUserId()).willReturn(userId);
            CouponEvent event = mock(CouponEvent.class);
            given(couponEventRepository.findByIdWithLock(couponEventId)).willReturn(Optional.of(event));
            willDoNothing().given(couponValidator).validateIssuable(event);
            given(userCouponRepository
                    .existsByUserUserIdAndCouponEventCouponEventId(userId, couponEventId))
                    .willReturn(false);

            // when
            couponService.issueCoupon(user, couponEventId);

            // then
            then(event).should().increaseIssuedCount();
            ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
            then(userCouponRepository).should().save(captor.capture());
            assertThat(captor.getValue()).isNotNull();
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰 이벤트면 COUPON_NOT_FOUND 를 던진다")
        void throwsNotFound_whenEventMissing() {
            // given
            User user = mock(User.class);
            given(couponEventRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> couponService.issueCoupon(user, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_NOT_FOUND);
            then(userCouponRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("이미 발급받은 유저가 재요청하면 COUPON_ALREADY_ISSUED 를 던진다")
        void throwsAlreadyIssued_whenDuplicateRequest() {
            // given
            Long userId = 1L;
            Long couponEventId = 100L;
            User user = mock(User.class);
            given(user.getUserId()).willReturn(userId);
            CouponEvent event = mock(CouponEvent.class);
            given(couponEventRepository.findByIdWithLock(couponEventId)).willReturn(Optional.of(event));
            willDoNothing().given(couponValidator).validateIssuable(event);
            given(userCouponRepository
                    .existsByUserUserIdAndCouponEventCouponEventId(userId, couponEventId))
                    .willReturn(true);     // 이미 발급

            // when / then
            assertThatThrownBy(() -> couponService.issueCoupon(user, couponEventId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_ALREADY_ISSUED);
            then(event).should(never()).increaseIssuedCount();
            then(userCouponRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("validator 가 만료 예외를 던지면 저장 로직까지 진행되지 않는다")
        void doesNotIssue_whenValidatorThrows() {
            // given
            Long couponEventId = 100L;
            User user = mock(User.class);
            CouponEvent event = mock(CouponEvent.class);
            given(couponEventRepository.findByIdWithLock(couponEventId)).willReturn(Optional.of(event));
            willThrow(new BusinessException(ErrorCode.COUPON_EXPIRED))
                    .given(couponValidator).validateIssuable(event);

            // when / then
            assertThatThrownBy(() -> couponService.issueCoupon(user, couponEventId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_EXPIRED);
            then(userCouponRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
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
