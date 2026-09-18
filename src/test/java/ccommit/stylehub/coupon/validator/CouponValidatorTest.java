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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 쿠폰 이벤트 생성·수정 검증 규칙이 기획서(정률 90% 이하, 기간 최소 1일, 시작일 과거 불가)와 일치하는지 검증하는 단위 테스트이다.
 * </p>
 */
class CouponValidatorTest {

    private final CouponValidator validator = new CouponValidator();

    private CouponEventCreateRequest createRequest(DiscountType type, int value, LocalDateTime start, LocalDateTime end) {
        return new CouponEventCreateRequest("테스트쿠폰", type, value, 0, 100, start, end);
    }

    @Nested
    @DisplayName("validateCreate")
    class ValidateCreate {

        @Test
        @DisplayName("정률 할인 90% 는 허용하고 91% 는 INVALID_DISCOUNT_VALUE 로 거절한다")
        void rateUpperBound() {
            LocalDateTime start = LocalDateTime.now().plusHours(1);
            assertThatCode(() -> validator.validateCreate(createRequest(DiscountType.RATE, 90, start, start.plusDays(1))))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validateCreate(createRequest(DiscountType.RATE, 91, start, start.plusDays(1))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_DISCOUNT_VALUE);
        }

        @Test
        @DisplayName("종료일이 시작일보다 1일 미만 뒤면 INVALID_COUPON_PERIOD 로 거절한다")
        void minimumPeriod() {
            LocalDateTime start = LocalDateTime.now().plusHours(1);
            assertThatThrownBy(() -> validator.validateCreate(
                    createRequest(DiscountType.FIXED, 1000, start, start.plusHours(23))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_COUPON_PERIOD);
        }

        @Test
        @DisplayName("지금 시작하는 이벤트는 허용하고, 허용 오차(1분)를 넘는 과거 시작일은 거절한다")
        void startTimeNotInPast() {
            LocalDateTime now = LocalDateTime.now();
            assertThatCode(() -> validator.validateCreate(
                    createRequest(DiscountType.FIXED, 1000, now.minusSeconds(5), now.plusDays(2))))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validateCreate(
                    createRequest(DiscountType.FIXED, 1000, now.minusMinutes(10), now.plusDays(2))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_COUPON_PERIOD);
        }
    }

    @Nested
    @DisplayName("validateUpdate")
    class ValidateUpdate {

        private CouponEvent startedEvent(LocalDateTime startedAt, int issuedCount) {
            CouponEvent event = CouponEvent.createPlatform("진행중", DiscountType.FIXED, 1000, 0, 100,
                    startedAt, startedAt.plusDays(7));
            ReflectionTestUtils.setField(event, "issuedCount", issuedCount);
            return event;
        }

        @Test
        @DisplayName("이미 시작된 이벤트도 시작일을 그대로 두면 수량·종료일을 수정할 수 있다")
        void allowsUpdateOfStartedEventKeepingStart() {
            LocalDateTime startedAt = LocalDateTime.now().minusDays(1);
            CouponEvent event = startedEvent(startedAt, 10);

            assertThatCode(() -> validator.validateUpdate(event,
                    new CouponEventUpdateRequest(200, 0, startedAt, startedAt.plusDays(10))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("시작일을 과거로 옮기면 INVALID_COUPON_PERIOD 로 거절한다")
        void rejectsMovingStartToPast() {
            LocalDateTime startedAt = LocalDateTime.now().plusDays(1);
            CouponEvent event = startedEvent(startedAt, 0);
            LocalDateTime past = LocalDateTime.now().minusDays(2);

            assertThatThrownBy(() -> validator.validateUpdate(event,
                    new CouponEventUpdateRequest(100, 0, past, past.plusDays(10))))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_COUPON_PERIOD);
        }
    }
}
