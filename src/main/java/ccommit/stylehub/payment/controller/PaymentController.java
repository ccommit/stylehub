package ccommit.stylehub.payment.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.payment.dto.request.PaymentCancelRequest;
import ccommit.stylehub.payment.dto.response.PaymentFailResponse;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/09/17 by WonJin - fix: 결제 취소 API에 로그인·역할 검증 적용, 세션 사용자 정보를 서비스로 전달
 * @modified 2026/09/17 by WonJin - fix: 실패 콜백 응답에서 요청 파라미터 반사 제거, 사용자 취소를 오류(400)가 아닌 처리 결과(200)로 응답
 *
 * <p>
 * 토스페이먼츠 결제 콜백 및 취소 API를 제공한다.
 * 콜백(success/fail)은 토스 인증 완료 후 successUrl/failUrl로 리다이렉트되어 호출되므로 인증 없이 열려 있고,
 * 취소는 로그인한 주문자 본인 또는 관리자만 호출할 수 있다.
 * </p>
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    // 토스 실패 코드는 대문자·밑줄 조합이다. 이 형식이 아니면 로그에 남기지 않는다(로그 위조 방지).
    private static final Pattern PG_ERROR_CODE = Pattern.compile("^[A-Z_]{1,50}$");

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
     * 토스 인증 실패·사용자 결제 취소 시 리다이렉트되는 엔드포인트이다.
     * 승인 대기 중인 결제라면 실패 처리하고 주문 취소와 재고 복구로 이어진다.
     *
     * <p>토스가 함께 보내는 message 는 응답에 되돌려 쓰지 않는다. 인증 없이 열린 GET 경로에서 쿼리 파라미터를
     * 그대로 본문에 넣으면, 브라우저가 text/html 로 해석할 때 반사형 XSS 가 된다.
     * 사용자가 결제창을 닫은 것은 서버 오류도 클라이언트 오류도 아닌 정상 흐름이라 200 으로 응답한다.
     */
    @GetMapping("/fail")
    public ResponseEntity<PaymentFailResponse> paymentFail(
            @RequestParam(required = false) String code,
            @RequestParam("orderId") String pgOrderId) {
        paymentService.handlePaymentFailure(pgOrderId);
        // pgOrderId 는 위에서 실제 결제와 매칭된 값만 여기까지 온다. code 는 형식이 맞을 때만 남긴다.
        log.info("토스 결제 실패 콜백 처리: pgOrderId={}, code={}", pgOrderId,
                code != null && PG_ERROR_CODE.matcher(code).matches() ? code : "UNKNOWN");
        return ResponseEntity.ok(PaymentFailResponse.of(pgOrderId));
    }

    /**
     * 결제를 취소한다. cancelAmount가 없으면 전액 취소, 있으면 부분 취소.
     * 로그인이 필요하며, 주문자 본인(USER) 또는 관리자(ADMIN)만 취소할 수 있다.
     */
    @PostMapping("/{paymentId}/cancel")
    @RequiredRole({UserRole.USER, UserRole.ADMIN})
    public ResponseEntity<PaymentResponse> cancelPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody PaymentCancelRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        UserRole userRole = SessionUtils.getUserRole(httpRequest);
        return ResponseEntity.ok(
                paymentService.cancelPayment(paymentId, userId, userRole,
                        request.cancelReason(), request.cancelAmount())
        );
    }
}
