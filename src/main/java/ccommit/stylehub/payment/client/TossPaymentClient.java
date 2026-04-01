package ccommit.stylehub.payment.client;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.payment.config.TossPaymentProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 토스페이먼츠 결제 승인/취소 API를 호출하는 클라이언트이다.
 * Secret Key를 Base64 인코딩하여 Authorization 헤더에 담아 요청한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentClient.class);

    private final TossPaymentProperties tossProperties;
    private final RestTemplate restTemplate;

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodeSecretKey());

        Map<String, Object> body = Map.of(
                "paymentKey", paymentKey,
                "orderId", orderId,
                "amount", amount
        );

        try {
            restTemplate.postForEntity(
                    tossProperties.getConfirmUrl(),
                    new HttpEntity<>(body, headers),
                    String.class
            );
            log.info("토스 결제 승인 성공: orderId={}", orderId);
        } catch (HttpClientErrorException e) {
            log.error("토스 결제 승인 실패: orderId={}, status={}, body={}", orderId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
        } catch (RestClientException e) {
            log.error("토스 결제 승인 실패: orderId={}, error={}", orderId, e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
        }
    }

    /**
     * 토스페이먼츠 결제 취소/부분 취소 API를 호출한다.
     * POST https://api.tosspayments.com/v1/payments/{paymentKey}/cancel
     * cancelAmount가 null이면 전액 취소, 값이 있으면 부분 취소.
     */
    @Override
    public void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodeSecretKey());

        Map<String, Object> body = new HashMap<>();
        body.put("cancelReason", cancelReason);
        if (cancelAmount != null) {
            body.put("cancelAmount", cancelAmount);
        }

        String cancelUrl = "https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel";

        try {
            restTemplate.postForEntity(cancelUrl, new HttpEntity<>(body, headers), String.class);
            log.info("토스 결제 취소 성공: paymentKey={}, cancelAmount={}", paymentKey, cancelAmount);
        } catch (HttpClientErrorException e) {
            log.error("토스 결제 취소 실패: paymentKey={}, status={}, body={}", paymentKey, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
        } catch (RestClientException e) {
            log.error("토스 결제 취소 실패: paymentKey={}, error={}", paymentKey, e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }
    }

    @Override
    public String getType() {
        return "TOSS";
    }

    private String encodeSecretKey() {
        return Base64.getEncoder().encodeToString(
                (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8)
        );
    }
}
