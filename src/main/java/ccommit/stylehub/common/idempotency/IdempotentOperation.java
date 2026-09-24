package ccommit.stylehub.common.idempotency;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * Idempotency-Key 를 받는 API 종류. 같은 키라도 작업이 다르면 별개로 본다.
 * </p>
 */
public enum IdempotentOperation {
    ORDER_CREATE,
    PAYMENT_CANCEL
}
