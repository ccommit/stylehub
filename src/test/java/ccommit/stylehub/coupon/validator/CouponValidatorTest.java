package ccommit.stylehub.coupon.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.enums.DiscountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * CouponValidator의 쿠폰 발급/생성/수정 시점 검증 규칙을 검증하는 단위테스트이다.
 * 외부 의존성이 없는 순수 검증 로직이므로 Mock 없이 실제 객체로 검증한다.
 * </p>
 */
class CouponValidatorTest {

    private final CouponValidator validator = new CouponValidator();

    private CouponEvent activeEvent(LocalDateTime startedAt, LocalDateTime expiredAt, boolean active) {
        CouponEvent event = CouponEvent.createPlatform(
                "쿠폰", DiscountType.FIXED, 1000, 0, 100, startedAt, expiredAt);
        if (!active) {
            event.deactivate();
        }
        return event;
    }

    @Nested
    @DisplayName("validateIssuable")
    class ValidateIssuable {

        @Test
        @DisplayName("활성 상태이고 진행 기간 중이면 예외가 발생하지 않는다")
        void 발급_가능한_쿠폰이면_통과한다() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), true);

            // when & then
            assertThatCode(() -> validator.validateIssuable(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("비활성 쿠폰이면 COUPON_NOT_ACTIVE 예외가 발생한다")
        void 비활성_쿠폰이면_예외() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), false);

            // when & then
            assertThatThrownBy(() -> validator.validateIssuable(event))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_NOT_ACTIVE);
        }

        @Test
        @DisplayName("아직 시작되지 않았으면 COUPON_NOT_STARTED 예외가 발생한다")
        void 시작전이면_예외() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2), true);

            // when & then
            assertThatThrownBy(() -> validator.validateIssuable(event))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_NOT_STARTED);
        }

        @Test
        @DisplayName("만료되었으면 COUPON_EXPIRED 예외가 발생한다")
        void 만료되었으면_예외() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), true);

            // when & then
            assertThatThrownBy(() -> validator.validateIssuable(event))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COUPON_EXPIRED);
        }
    }

    @Nested
    @DisplayName("validateCreate")
    class ValidateCreate {

        private CouponEventCreateRequest request(DiscountType type, int value,
                                                  LocalDateTime startedAt, LocalDateTime expiredAt) {
            return new CouponEventCreateRequest("쿠폰", type, value, 0, 100, startedAt, expiredAt);
        }

        @Test
        @DisplayName("정상적인 기간과 할인값이면 예외가 발생하지 않는다")
        void 정상_생성요청이면_통과한다() {
            // given
            CouponEventCreateRequest request = request(
                    DiscountType.RATE, 50, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));

            // when & then
            assertThatCode(() -> validator.validateCreate(request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("시작일이 만료일보다 늦으면 INVALID_COUPON_PERIOD 예외가 발생한다")
        void 시작일이_만료일보다_늦으면_예외() {
            // given
            CouponEventCreateRequest request = request(
                    DiscountType.FIXED, 1000, LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(1));

            // when & then
            assertThatThrownBy(() -> validator.validateCreate(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COUPON_PERIOD);
        }

        @Test
        @DisplayName("RATE 타입 할인값이 100을 초과하면 INVALID_DISCOUNT_VALUE 예외가 발생한다")
        void RATE_할인값이_100초과면_예외() {
            // given
            CouponEventCreateRequest request = request(
                    DiscountType.RATE, 101, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));

            // when & then
            assertThatThrownBy(() -> validator.validateCreate(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_DISCOUNT_VALUE);
        }

        @Test
        @DisplayName("RATE 타입 할인값이 정확히 100이면 예외가 발생하지 않는다 (경계값)")
        void RATE_할인값이_정확히_100이면_통과한다() {
            // given
            CouponEventCreateRequest request = request(
                    DiscountType.RATE, 100, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));

            // when & then
            assertThatCode(() -> validator.validateCreate(request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("FIXED 타입은 할인값이 100을 초과해도 예외가 발생하지 않는다")
        void FIXED_타입은_100초과값도_허용한다() {
            // given
            CouponEventCreateRequest request = request(
                    DiscountType.FIXED, 50000, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));

            // when & then
            assertThatCode(() -> validator.validateCreate(request)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("validateUpdate")
    class ValidateUpdate {

        @Test
        @DisplayName("정상적인 기간과 발행 수량이면 예외가 발생하지 않는다")
        void 정상_수정요청이면_통과한다() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10), true);
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(
                    100, 0, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(20));

            // when & then
            assertThatCode(() -> validator.validateUpdate(event, request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("시작일이 만료일보다 늦으면 INVALID_COUPON_PERIOD 예외가 발생한다")
        void 시작일이_만료일보다_늦으면_예외() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10), true);
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(
                    100, 0, LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(1));

            // when & then
            assertThatThrownBy(() -> validator.validateUpdate(event, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_COUPON_PERIOD);
        }

        @Test
        @DisplayName("변경할 발행 수량이 이미 발급된 수량보다 적으면 예외가 발생한다")
        void 발행수량이_이미발급된수량보다_적으면_예외() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10), true);
            event.increaseIssuedCount();
            event.increaseIssuedCount(); // issuedCount = 2
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(
                    1, 0, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(20));

            // when & then
            assertThatThrownBy(() -> validator.validateUpdate(event, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_DISCOUNT_VALUE);
        }

        @Test
        @DisplayName("변경할 발행 수량이 이미 발급된 수량과 정확히 같으면 예외가 발생하지 않는다 (경계값)")
        void 발행수량이_이미발급된수량과_같으면_통과한다() {
            // given
            CouponEvent event = activeEvent(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10), true);
            event.increaseIssuedCount(); // issuedCount = 1
            CouponEventUpdateRequest request = new CouponEventUpdateRequest(
                    1, 0, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(20));

            // when & then
            assertThatCode(() -> validator.validateUpdate(event, request)).doesNotThrowAnyException();
        }
    }
}
