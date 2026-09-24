package ccommit.stylehub.payment.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.idempotency.IdempotencyRequest;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.payment.dto.request.PaymentCancelRequest;
import ccommit.stylehub.payment.dto.response.PaymentFailResponse;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.service.PaymentApplicationService;
import ccommit.stylehub.payment.service.PaymentService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/09/17 by WonJin - fix: 결제 취소 API에 로그인·역할 검증 적용, 세션 사용자 정보를 서비스로 전달
 * @modified 2026/09/17 by WonJin - fix: 실패 콜백 응답에서 요청 파라미터 반사 제거, 사용자 취소를 오류(400)가 아닌 처리 결과(200)로 응답
 * @modified 2026/09/18 by WonJin - feat: 결제 취소에 Idempotency-Key 헤더 지원 (PaymentApplicationService 경유)
 *
 * <p>
 * 토스페이먼츠 결제 콜백 및 취소 API를 제공한다.
 * 콜백은 토스 리다이렉트로 호출되어 인증 없이 열려 있고, 취소는 주문자 본인과 관리자만 호출할 수 있다.
 * </p>
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    // 로그 위조 방지: 토스 에러 코드 형식일 때만 로그에 남긴다
    private static final Pattern PG_ERROR_CODE = Pattern.compile("^[A-Z_]{1,50}$");

    private final PaymentService paymentService;
    private final PaymentApplicationService paymentApplicationService;

    // 토스 인증 성공 후 리다이렉트된다. 금액을 검증한 뒤 최종 승인을 요청한다.
    @GetMapping("/success")
    public ResponseEntity<PaymentResponse> paymentSuccess(
            @RequestParam String paymentKey,
            @RequestParam("orderId") String pgOrderId,
            @RequestParam("amount") Integer tossAmount) {
        return ResponseEntity.ok(paymentService.confirmPayment(paymentKey, pgOrderId, tossAmount));
    }

    // 토스 인증 실패·결제창 닫기 시 리다이렉트된다. 반사형 XSS 방지를 위해 요청 message는 응답에 담지 않는다.
    @GetMapping("/fail")
    public ResponseEntity<PaymentFailResponse> paymentFail(
            @RequestParam(required = false) String code,
            @RequestParam("orderId") String pgOrderId) {
        paymentService.handlePaymentFailure(pgOrderId);
        log.info("토스 결제 실패 콜백 처리: pgOrderId={}, code={}", pgOrderId,
                code != null && PG_ERROR_CODE.matcher(code).matches() ? code : "UNKNOWN");
        return ResponseEntity.ok(PaymentFailResponse.of(pgOrderId));
    }

    // cancelAmount가 없으면 전액 취소, 있으면 부분 취소. Idempotency-Key 를 보내면 같은 키의 재요청을 한 번만 취소한다
    @PostMapping("/{paymentId}/cancel")
    @RequiredRole({UserRole.USER, UserRole.ADMIN})
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable Long paymentId,
            @RequestHeader(value = IdempotencyRequest.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentCancelRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        UserRole userRole = SessionUtils.getUserRole(httpRequest);
        return ResponseEntity.ok(
                paymentApplicationService.cancelPayment(paymentId, userId, userRole, idempotencyKey, request)
        );
    }
}
