package ccommit.stylehub.coupon.service;

import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * CouponApplicationService의 오케스트레이션 로직을 검증하는 단위테스트이다.
 * UserPort와 CouponService를 Mock으로 대체해 권한/조회 위임 여부에 집중한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CouponApplicationServiceTest {

    @Mock
    private UserPort userPort;

    @Mock
    private CouponService couponService;

    @InjectMocks
    private CouponApplicationService couponApplicationService;

    @Test
    @DisplayName("createStoreCouponEvent는 스토어 소유자를 조회한 뒤 생성을 위임한다")
    void createStoreCouponEvent_소유자_조회_후_위임() {
        // given
        Long userId = 1L;
        Long storeId = 10L;
        User owner = User.builder().userId(storeId).storeName("스토어").build();
        CouponEventCreateRequest request = new CouponEventCreateRequest(
                "쿠폰", DiscountType.FIXED, 1000, 0, 100,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));
        CouponEventResponse expected = CouponEventResponse.builder().couponEventId(1L).build();
        when(userPort.findApprovedStoreByOwner(userId, storeId)).thenReturn(owner);
        when(couponService.createStoreCouponEvent(owner, request)).thenReturn(expected);

        // when
        CouponEventResponse response = couponApplicationService.createStoreCouponEvent(userId, storeId, request);

        // then
        assertThat(response).isEqualTo(expected);
        verify(userPort).findApprovedStoreByOwner(userId, storeId);
    }

    @Test
    @DisplayName("createPlatformCouponEvent는 소유권 검증 없이 생성을 위임한다")
    void createPlatformCouponEvent_검증없이_위임() {
        // given
        CouponEventCreateRequest request = new CouponEventCreateRequest(
                "쿠폰", DiscountType.FIXED, 1000, 0, 100,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));
        CouponEventResponse expected = CouponEventResponse.builder().couponEventId(1L).build();
        when(couponService.createPlatformCouponEvent(request)).thenReturn(expected);

        // when
        CouponEventResponse response = couponApplicationService.createPlatformCouponEvent(request);

        // then
        assertThat(response).isEqualTo(expected);
        verifyNoInteractions(userPort);
    }

    @Test
    @DisplayName("updateCouponEvent는 소유권 검증 없이 수정을 위임한다")
    void updateCouponEvent_검증없이_위임() {
        // given
        CouponEventUpdateRequest request = new CouponEventUpdateRequest(
                50, 0, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));
        CouponEventResponse expected = CouponEventResponse.builder().couponEventId(1L).build();
        when(couponService.updateCouponEvent(1L, request)).thenReturn(expected);

        // when
        CouponEventResponse response = couponApplicationService.updateCouponEvent(1L, request);

        // then
        assertThat(response).isEqualTo(expected);
        verifyNoInteractions(userPort);
    }

    @Test
    @DisplayName("deactivateCouponEvent는 소유권 검증 없이 비활성화를 위임한다")
    void deactivateCouponEvent_검증없이_위임() {
        // when
        couponApplicationService.deactivateCouponEvent(1L);

        // then
        verify(couponService).deactivateCouponEvent(1L);
        verifyNoInteractions(userPort);
    }

    @Test
    @DisplayName("issueCoupon은 사용자를 조회한 뒤 발급을 위임한다")
    void issueCoupon_사용자_조회_후_위임() {
        // given
        Long userId = 1L;
        User user = User.builder().userId(userId).build();
        when(userPort.findUserById(userId)).thenReturn(user);

        // when
        couponApplicationService.issueCoupon(userId, 100L);

        // then
        verify(userPort).findUserById(userId);
        verify(couponService).issueCoupon(user, 100L);
    }

    @Nested
    @DisplayName("공개 조회 API")
    class Read {

        @Test
        @DisplayName("getMyCoupons는 UserPort와 상호작용 없이 위임한다")
        void getMyCoupons_위임한다() {
            // given
            when(couponService.getMyCoupons(1L)).thenReturn(List.of());

            // when
            List<UserCouponResponse> result = couponApplicationService.getMyCoupons(1L);

            // then
            assertThat(result).isEmpty();
            verifyNoInteractions(userPort);
        }

        @Test
        @DisplayName("getActiveCouponEvents는 UserPort와 상호작용 없이 위임한다")
        void getActiveCouponEvents_위임한다() {
            // given
            when(couponService.getActiveCouponEvents()).thenReturn(List.of());

            // when
            List<CouponEventResponse> result = couponApplicationService.getActiveCouponEvents();

            // then
            assertThat(result).isEmpty();
            verifyNoInteractions(userPort);
        }
    }
}
