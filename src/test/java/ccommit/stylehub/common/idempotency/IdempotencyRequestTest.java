package ccommit.stylehub.common.idempotency;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * Idempotency-Key 형식 검증과 요청 해시·PG 멱등 키 파생 규칙을 검증한다.
 * </p>
 */
class IdempotencyRequestTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    @DisplayName("키를 보내지 않으면 멱등 처리 대상이 아니다")
    void returnsNull_whenKeyAbsent() {
        assertThat(IdempotencyRequest.of(USER_ID, IdempotentOperation.ORDER_CREATE, null, 1L)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "한글키", "a/b"})
    @DisplayName("영문·숫자·-·_ 이외의 문자가 있거나 비어 있는 키는 INVALID_IDEMPOTENCY_KEY 로 거절한다")
    void rejectsMalformedKey(String key) {
        assertThatThrownBy(() -> IdempotencyRequest.of(USER_ID, IdempotentOperation.ORDER_CREATE, key, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("64자를 넘는 키는 거절한다")
    void rejectsTooLongKey() {
        String key = "a".repeat(65);

        assertThatThrownBy(() -> IdempotencyRequest.of(USER_ID, IdempotentOperation.ORDER_CREATE, key, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("요청 내용이 같으면 해시가 같고, 하나라도 다르면 해시가 다르다")
    void hashesRequestContent() {
        IdempotencyRequest first = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, KEY, 10L, "단순 변심", 1000);
        IdempotencyRequest same = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, KEY, 10L, "단순 변심", 1000);
        IdempotencyRequest otherAmount = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, KEY, 10L, "단순 변심", 2000);

        assertThat(first.requestHash()).isEqualTo(same.requestHash()).hasSize(64);
        assertThat(first.requestHash()).isNotEqualTo(otherAmount.requestHash());
    }

    @Test
    @DisplayName("PG 멱등 키는 같은 사용자·키·요청이면 같고, 키나 요청 내용이 바뀌면 달라진다")
    void derivesStablePgKey() {
        IdempotencyRequest first = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, KEY, 10L, "사유", 1000);
        IdempotencyRequest retry = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, KEY, 10L, "사유", 1000);
        IdempotencyRequest otherKey = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, "other-key", 10L, "사유", 1000);
        IdempotencyRequest otherAmount = IdempotencyRequest.of(USER_ID, IdempotentOperation.PAYMENT_CANCEL, KEY, 10L, "사유", 2000);

        assertThat(first.derivedKey()).isEqualTo(retry.derivedKey());
        assertThat(first.derivedKey()).isNotEqualTo(otherKey.derivedKey());
        assertThat(first.derivedKey()).isNotEqualTo(otherAmount.derivedKey());
    }
}
