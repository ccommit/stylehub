package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.coupon.validator.CouponValidator;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * CouponService의 쿠폰 이벤트 생성/수정/비활성화와 선착순 발급 로직을 검증하는 단위테스트이다.
 * Repository와 CouponValidator는 Mock으로 대체해 DB 없이 협력 순서와 예외 전파를 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    private CouponEventRepository couponEventRepository;

    @Mock
    private UserCouponRepository userCouponRepository;

    @Mock
    private CouponValidator couponValidator;

    @InjectMocks
    private CouponService couponService;

    private final LocalDateTime started = LocalDateTime.now().minusDays(1);
    private final LocalDateTime expired = LocalDateTime.now().plusDays(10);

    private CouponEventCreateRequest createRequest() {
        return new CouponEventCreateRequest("쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
    }

    @Nested
    @DisplayName("createStoreCouponEvent")
    class CreateStoreCouponEvent {

        @Test
        @DisplayName("검증을 통과하면 스토어 쿠폰 이벤트를 저장한다")
        void 검증_통과시_저장한다() {
            // given
            User store = User.builder().userId(1L).storeName("스토어").build();
            CouponEventCreateRequest request = createRequest();
            when(couponEventRepository.save(any(CouponEvent.class))).thenAnswer(invocation -> {
                CouponEvent saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "couponEventId", 1L);
                return saved;
            });

            // when
            CouponEventResponse response = couponService.createStoreCouponEvent(store, request);

            // then
            assertThat(response.couponEventId()).isEqualTo(1L);
            assertThat(response.storeId()).isEqualTo(1L);
            verify(couponValidator).validateCreate(request);
        }

        @Test
        @DisplayName("검증에 실패하면 저장하지 않고 예외가 전파된다")
        void 검증_실패시_저장하지_않는다() {
            // given
            User store = User.builder().userId(1L).storeName("스토어").build();
            CouponEventCreateRequest request = createRequest();
            doThrow(new BusinessException(ErrorCode.INVALID_COUPON_PERIOD))
                    .when(couponValidator).validateCreate(request);

            // when & then
            assertThatThrownBy(() -> couponService.createStoreCouponEvent(store, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COUPON_PERIOD);
            verify(couponEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("createPlatformCouponEvent")
    class CreatePlatformCouponEvent {

        @Test
        @DisplayName("검증을 통과하면 플랫폼 쿠폰 이벤트를 저장한다")
        void 검증_통과시_저장한다() {
            // given
            CouponEventCreateRequest request = createRequest();
            when(couponEventRepository.save(any(CouponEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            CouponEventResponse response = couponService.createPlatformCouponEvent(request);

            // then
            assertThat(response.storeId()).isNull();
            assertThat(response.storeName()).isEqualTo("StyleHub");
            verify(couponValidator).validateCreate(request);
        }
    }

    @Nested
    @DisplayName("issueCoupon")
    class IssueCoupon {

        private final User user = User.builder().userId(1L).build();

        @Test
        @DisplayName("발급 가능하고 중복 발급이 아니면 쿠폰을 발급한다")
        void 정상적으로_발급한다() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 10, started, expired);
            when(couponEventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
            when(userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(1L, 1L)).thenReturn(false);

            // when
            couponService.issueCoupon(user, 1L);

            // then
            assertThat(event.getIssuedCount()).isEqualTo(1);
            verify(userCouponRepository).save(any(UserCoupon.class));
        }

        @Test
        @DisplayName("쿠폰 이벤트가 존재하지 않으면 COUPON_NOT_FOUND 예외가 발생한다")
        void 쿠폰이_없으면_예외() {
            // given
            when(couponEventRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.issueCoupon(user, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_NOT_FOUND);
            verify(userCouponRepository, never()).save(any());
        }

        @Test
        @DisplayName("발급 불가 상태(비활성/미시작/만료)면 검증 단계에서 예외가 전파되고 발급하지 않는다")
        void 발급불가_상태면_검증에서_예외가_전파된다() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 10, started, expired);
            when(couponEventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
            doThrow(new BusinessException(ErrorCode.COUPON_NOT_ACTIVE))
                    .when(couponValidator).validateIssuable(event);

            // when & then
            assertThatThrownBy(() -> couponService.issueCoupon(user, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_NOT_ACTIVE);
            verify(userCouponRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 발급받은 쿠폰이면 COUPON_ALREADY_ISSUED 예외가 발생하고 발급 수량이 증가하지 않는다")
        void 이미_발급받았으면_예외() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 10, started, expired);
            when(couponEventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
            when(userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(1L, 1L)).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> couponService.issueCoupon(user, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
            assertThat(event.getIssuedCount()).isZero();
            verify(userCouponRepository, never()).save(any());
        }

        @Test
        @DisplayName("발행 수량이 모두 소진되었으면 COUPON_SOLD_OUT 예외가 발생한다")
        void 소진되었으면_예외() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 1, started, expired);
            event.increaseIssuedCount(); // 1/1 소진
            when(couponEventRepository.findByIdWithLock(1L)).thenReturn(Optional.of(event));
            when(userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(1L, 1L)).thenReturn(false);

            // when & then
            assertThatThrownBy(() -> couponService.issueCoupon(user, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_SOLD_OUT);
            verify(userCouponRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateCouponEvent")
    class UpdateCouponEvent {

        @Test
        @DisplayName("검증을 통과하면 쿠폰 이벤트 정보를 갱신한다")
        void 검증_통과시_갱신한다() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(
                    50, 3000, started.plusDays(1), expired.plusDays(1));
            when(couponEventRepository.findById(1L)).thenReturn(Optional.of(event));

            // when
            CouponEventResponse response = couponService.updateCouponEvent(1L, request);

            // then
            assertThat(response.issueCount()).isEqualTo(50);
            assertThat(response.minOrderAmount()).isEqualTo(3000);
            verify(couponValidator).validateUpdate(event, request);
        }

        @Test
        @DisplayName("쿠폰 이벤트가 존재하지 않으면 COUPON_NOT_FOUND 예외가 발생한다")
        void 쿠폰이_없으면_예외() {
            // given
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(50, 3000, started, expired);
            when(couponEventRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.updateCouponEvent(999L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_NOT_FOUND);
        }

        @Test
        @DisplayName("검증에 실패하면 갱신하지 않고 예외가 전파된다")
        void 검증_실패시_갱신하지_않는다() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(1, 3000, started, expired);
            when(couponEventRepository.findById(1L)).thenReturn(Optional.of(event));
            doThrow(new BusinessException(ErrorCode.INVALID_DISCOUNT_VALUE))
                    .when(couponValidator).validateUpdate(event, request);

            // when & then
            assertThatThrownBy(() -> couponService.updateCouponEvent(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_DISCOUNT_VALUE);
            assertThat(event.getIssueCount()).isEqualTo(100); // 갱신되지 않아야 한다
        }
    }

    @Nested
    @DisplayName("deactivateCouponEvent")
    class DeactivateCouponEvent {

        @Test
        @DisplayName("쿠폰 이벤트를 비활성화한다")
        void 비활성화한다() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
            when(couponEventRepository.findById(1L)).thenReturn(Optional.of(event));

            // when
            couponService.deactivateCouponEvent(1L);

            // then
            assertThat(event.getActive()).isFalse();
        }

        @Test
        @DisplayName("쿠폰 이벤트가 존재하지 않으면 COUPON_NOT_FOUND 예외가 발생한다")
        void 쿠폰이_없으면_예외() {
            // given
            when(couponEventRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponService.deactivateCouponEvent(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("getMyCoupons는 사용자의 보유 쿠폰 목록을 반환한다")
        void getMyCoupons_보유쿠폰_목록을_반환한다() {
            // given
            User user = User.builder().userId(1L).build();
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
            UserCoupon userCoupon = UserCoupon.create(user, event);
            when(userCouponRepository.findByUserIdWithCouponEvent(1L)).thenReturn(List.of(userCoupon));

            // when
            List<UserCouponResponse> result = couponService.getMyCoupons(1L);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).couponName()).isEqualTo("쿠폰");
        }

        @Test
        @DisplayName("getActiveCouponEvents는 현재 발급 가능한 쿠폰 이벤트 목록을 반환한다")
        void getActiveCouponEvents_발급가능한_이벤트를_반환한다() {
            // given
            CouponEvent event = CouponEvent.createPlatform("쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
            when(couponEventRepository.findActiveCouponEvents(any())).thenReturn(List.of(event));

            // when
            List<CouponEventResponse> result = couponService.getActiveCouponEvents();

            // then
            assertThat(result).hasSize(1);
            verify(couponEventRepository).findActiveCouponEvents(any());
        }
    }
}
