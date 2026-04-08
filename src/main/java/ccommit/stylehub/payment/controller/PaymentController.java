package ccommit.stylehub.payment.controller;

import ccommit.stylehub.payment.dto.request.PaymentCancelRequest;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 토스페이먼츠 결제 콜백 및 취소 API를 제공한다.
 * 토스 인증 완료 후 successUrl/failUrl로 리다이렉트되어 호출된다.
 * </p>
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 토스 인증 성공 후 리다이렉트되는 엔드포인트이다.
     * paymentKey, orderId, amount를 받아 금액 검증 후 최종 승인 요청을 한다.
     */
    @GetMapping("/success")
    public ResponseEntity<PaymentResponse> paymentSuccess(
            @RequestParam String paymentKey,
            @RequestParam("orderId") String pgOrderId,
            @RequestParam("amount") Integer tossAmount) {
        return ResponseEntity.ok(paymentService.confirmPayment(paymentKey, pgOrderId, tossAmount));
    }

    /**
     * 토스 인증 실패 시 리다이렉트되는 엔드포인트이다.
     * 주문 취소 + 재고 복구를 처리한다.
     */
    @GetMapping("/fail")
    public ResponseEntity<String> paymentFail(
            @RequestParam String code,
            @RequestParam String message,
            @RequestParam("orderId") String pgOrderId) {
        paymentService.handlePaymentFailure(pgOrderId);
        return ResponseEntity.badRequest()
                .body("결제 실패: " + message + " (code=" + code + ", orderId=" + pgOrderId + ")");
    }

    /**
     * 결제를 취소한다. cancelAmount가 없으면 전액 취소, 있으면 부분 취소.
     */
    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody PaymentCancelRequest request) {
        return ResponseEntity.ok(
                paymentService.cancelPayment(paymentId, request.cancelReason(), request.cancelAmount())
        );
    }
}
