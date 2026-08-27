package ccommit.stylehub.coupon.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.enums.CouponType;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * CouponEvent의 생성 불변식, 발급 수량 증가, 할인 계산, 기간 판정 로직을 검증하는 단위테스트이다.
 * </p>
 */
class CouponEventTest {

    private final LocalDateTime started = LocalDateTime.now().minusDays(1);
    private final LocalDateTime expired = LocalDateTime.now().plusDays(10);

    @Nested
    @DisplayName("create (스토어 쿠폰)")
    class Create {

        @Test
        @DisplayName("스토어 소유자가 있으면 couponType이 STORE로 생성된다")
        void 스토어_소유자가_있으면_STORE_타입으로_생성된다() {
            // given
            User store = User.builder().userId(1L).storeName("스토어").build();

            // when
            CouponEvent event = CouponEvent.create(
                    store, "쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);

            // then
            assertThat(event.getCouponType()).isEqualTo(CouponType.STORE);
            assertThat(event.getStoreUser()).isEqualTo(store);
            assertThat(event.getIssuedCount()).isZero();
            assertThat(event.getActive()).isTrue();
        }

        @Test
        @DisplayName("스토어 소유자가 없으면 INVALID_COUPON_TYPE 예외가 발생한다")
        void 스토어_소유자가_없으면_예외() {
            // when & then
            assertThatThrownBy(() -> CouponEvent.create(
                    null, "쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COUPON_TYPE);
        }
    }

    @Nested
    @DisplayName("createPlatform (플랫폼 쿠폰)")
    class CreatePlatform {

        @Test
        @DisplayName("스토어 소유자 없이 PLATFORM 타입으로 생성된다")
        void PLATFORM_타입으로_생성된다() {
            // when
            CouponEvent event = CouponEvent.createPlatform(
                    "플랫폼 쿠폰", DiscountType.RATE, 10, 0, 100, started, expired);

            // then
            assertThat(event.getCouponType()).isEqualTo(CouponType.PLATFORM);
            assertThat(event.getStoreUser()).isNull();
        }
    }

    @Nested
    @DisplayName("increaseIssuedCount")
    class IncreaseIssuedCount {

        @Test
        @DisplayName("발행 수량보다 적게 발급되었으면 발급 수량이 증가한다")
        void 발급수량이_증가한다() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 2, started, expired);

            // when
            event.increaseIssuedCount();

            // then
            assertThat(event.getIssuedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("발행 수량만큼 모두 소진되면 다음 발급 시 COUPON_SOLD_OUT 예외가 발생한다 (경계값)")
        void 모두_소진되면_예외() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 1, started, expired);
            event.increaseIssuedCount(); // 1/1 소진

            // when & then
            assertThatThrownBy(event::increaseIssuedCount)
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_SOLD_OUT);
            assertThat(event.getIssuedCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("발행수량, 최소주문금액, 기간을 갱신한다")
        void 필드를_갱신한다() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);
            LocalDateTime newStarted = started.plusDays(1);
            LocalDateTime newExpired = expired.plusDays(1);

            // when
            event.update(50, 5000, newStarted, newExpired);

            // then
            assertThat(event.getIssueCount()).isEqualTo(50);
            assertThat(event.getMinOrderAmount()).isEqualTo(5000);
            assertThat(event.getStartedAt()).isEqualTo(newStarted);
            assertThat(event.getExpiredAt()).isEqualTo(newExpired);
        }
    }

    @Nested
    @DisplayName("calculateDiscount")
    class CalculateDiscount {

        @Test
        @DisplayName("FIXED 타입은 할인 값과 주문 금액 중 작은 값을 할인한다")
        void FIXED_타입_할인_계산() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 5000, 0, 100, started, expired);

            // when & then
            assertThat(event.calculateDiscount(3000)).isEqualTo(3000);
            assertThat(event.calculateDiscount(10000)).isEqualTo(5000);
        }

        @Test
        @DisplayName("RATE 타입은 주문 금액에 비율을 곱해 할인한다")
        void RATE_타입_할인_계산() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.RATE, 10, 0, 100, started, expired);

            // when & then
            assertThat(event.calculateDiscount(10000)).isEqualTo(1000);
        }
    }

    @Nested
    @DisplayName("기간/상태 판정")
    class PeriodAndStatus {

        @Test
        @DisplayName("deactivate 호출 시 active가 false가 된다")
        void deactivate_호출시_비활성화된다() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);

            // when
            event.deactivate();

            // then
            assertThat(event.getActive()).isFalse();
        }

        @Test
        @DisplayName("현재 시각이 만료일 이후면 isExpired가 true다")
        void 만료일_이후면_isExpired가_true다() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 100,
                    LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1));

            // when & then
            assertThat(event.isExpired()).isTrue();
        }

        @Test
        @DisplayName("현재 시각이 시작일 이전이면 isNotStarted가 true다")
        void 시작일_이전이면_isNotStarted가_true다() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 100,
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2));

            // when & then
            assertThat(event.isNotStarted()).isTrue();
        }

        @Test
        @DisplayName("진행 기간 중이면 isExpired와 isNotStarted 모두 false다")
        void 진행_기간중이면_둘다_false다() {
            // given
            CouponEvent event = CouponEvent.createPlatform(
                    "쿠폰", DiscountType.FIXED, 1000, 0, 100, started, expired);

            // when & then
            assertThat(event.isExpired()).isFalse();
            assertThat(event.isNotStarted()).isFalse();
        }
    }
}
