package ccommit.stylehub.payment.dto.response;

import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 결제 승인 결과를 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record PaymentResponse(
        Long paymentId,
        String paymentKey,
        String orderId,
        String orderName,
        PaymentStatus status,
        Integer totalAmount,
        Integer approvedAmount,
        LocalDateTime approvedAt
) {
    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .paymentKey(payment.getPaymentKey())
                .orderId(payment.getOrder().getPgOrderId())
                .orderName(payment.getOrderName())
                .status(payment.getStatus())
                .totalAmount(payment.getTotalAmount())
                .approvedAmount(payment.getApprovedAmount())
                .approvedAt(payment.getApprovedAt())
                .build();
    }
}
