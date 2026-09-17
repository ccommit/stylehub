package ccommit.stylehub.payment.client;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.payment.config.TossPaymentProperties;
import ccommit.stylehub.payment.dto.response.PgPaymentSnapshot;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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
 * @modified 2026/09/17 by WonJin - fix: 승인 실패를 PG 거절(4xx)과 결과 불명(5xx·타임아웃)으로 구분
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

    private static final String APPROVED_STATUS = "DONE";

    private final TossPaymentProperties tossProperties;
    private final RestTemplate restTemplate;

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        HttpHeaders headers = createAuthHeaders();

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
            // 4xx 는 PG 가 요청을 거절했다는 명확한 응답이다. 결제는 일어나지 않았다.
            log.error("토스 결제 승인 거절: orderId={}, status={}, body={}", orderId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
        } catch (RestClientException e) {
            // 5xx·연결 실패·읽기 타임아웃은 PG 에서 승인이 끝났는지 알 수 없다. 거절과 섞으면 결제된 주문을 되돌리게 된다.
            log.error("토스 결제 승인 결과 불명: orderId={}, error={}", orderId, e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN);
        }
    }

    // cancelAmount가 null이면 전액 취소, 있으면 부분 취소
    @Override
    public void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount) {
        HttpHeaders headers = createAuthHeaders();

        Map<String, Object> body = new HashMap<>();
        body.put("cancelReason", cancelReason);
        if (cancelAmount != null) {
            body.put("cancelAmount", cancelAmount);
        }

        String cancelUrl = tossProperties.getCancelUrl() + "/" + paymentKey + "/cancel";

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

    // 404는 이 주문으로 결제가 시작되지 않았다는 뜻이라 오류가 아니라 미승인으로 다룬다.
    // PG 응답 스키마가 바뀌어도 파싱이 깨지지 않도록 필요한 세 값만 Map으로 받는다.
    @Override
    @SuppressWarnings("unchecked")
    public PgPaymentSnapshot findPayment(String pgOrderId) {
        String url = tossProperties.getFindByOrderIdUrl() + "/" + pgOrderId;

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createAuthHeaders()), Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                log.warn("토스 결제 조회 응답 본문 없음: orderId={}", pgOrderId);
                return PgPaymentSnapshot.notFound();
            }

            boolean approved = APPROVED_STATUS.equals(body.get("status"));
            log.info("토스 결제 조회: orderId={}, status={}", pgOrderId, body.get("status"));

            return new PgPaymentSnapshot(
                    approved,
                    (String) body.get("paymentKey"),
                    (Integer) body.get("totalAmount")
            );
        } catch (HttpClientErrorException.NotFound e) {
            // 해당 주문으로 결제가 시작되지 않은 정상 케이스
            log.info("토스에 결제 기록 없음: orderId={}", pgOrderId);
            return PgPaymentSnapshot.notFound();
        }
        // 그 외 오류는 호출자가 판단하도록 그대로 전파한다.
        // 조회에 실패했다는 것과 승인되지 않았다는 것은 다르므로 여기서 삼키면 안 된다.
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodeSecretKey());
        return headers;
    }

    private String encodeSecretKey() {
        return Base64.getEncoder().encodeToString(
                (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8)
        );
    }
}
