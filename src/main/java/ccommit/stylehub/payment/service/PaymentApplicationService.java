package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.idempotency.IdempotencyGuard;
import ccommit.stylehub.common.idempotency.IdempotencyRequest;
import ccommit.stylehub.common.idempotency.IdempotentOperation;
import ccommit.stylehub.payment.dto.request.PaymentCancelRequest;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 결제 취소 유스케이스의 Application 계층 서비스이다.
 * 같은 Idempotency-Key 재요청은 우리 DB 에서 한 번만 취소하고, PG 에도 같은 키를 넘겨 응답 유실 뒤 재시도가 이중 환불되지 않게 한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PaymentApplicationService {

    private final PaymentService paymentService;
    private final IdempotencyGuard idempotencyGuard;

    public PaymentResponse cancelPayment(Long paymentId, Long requesterId, UserRole requesterRole,
                                         String idempotencyKey, PaymentCancelRequest request) {
        IdempotencyRequest idempotency = IdempotencyRequest.of(requesterId, IdempotentOperation.PAYMENT_CANCEL,
                idempotencyKey, paymentId, request.cancelReason(), request.cancelAmount());
        String pgIdempotencyKey = idempotency == null ? null : idempotency.derivedKey();

        return idempotencyGuard.execute(idempotency,
                () -> paymentService.cancelPayment(paymentId, requesterId, requesterRole,
                        request.cancelReason(), request.cancelAmount(), pgIdempotencyKey),
                PaymentResponse::paymentId,
                paymentService::getPayment);
    }
}
