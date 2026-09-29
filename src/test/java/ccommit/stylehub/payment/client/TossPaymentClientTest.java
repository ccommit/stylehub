package ccommit.stylehub.payment.client;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.payment.config.TossPaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 토스 승인 응답이 2xx 여도 DONE 상태·요청 금액이 아니면 결제 완료로 보지 않는지 검증한다.
 * </p>
 */
class TossPaymentClientTest {

    private static final String CONFIRM_URL = "https://pg.test/v1/payments/confirm";
    private static final String PAYMENT_KEY = "pk-1";
    private static final String ORDER_ID = "ORD-1";
    private static final int AMOUNT = 10_000;

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        TossPaymentProperties properties = new TossPaymentProperties();
        properties.setSecretKey("test_sk");
        properties.setConfirmUrl(CONFIRM_URL);
        client = new TossPaymentClient(properties, restTemplate);
    }

    @Test
    @DisplayName("DONE 상태이고 승인 금액이 요청 금액과 같으면 승인 성공이다")
    void approves_whenDoneWithSameAmount() {
        respondConfirm("DONE", AMOUNT);

        assertThatCode(() -> client.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2xx 라도 가상계좌 입금 대기 상태면 결과 불명으로 처리한다")
    void resultUnknown_whenWaitingForDeposit() {
        respondConfirm("WAITING_FOR_DEPOSIT", AMOUNT);

        assertThatThrownBy(() -> client.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("2xx 라도 승인 금액이 요청 금액과 다르면 결과 불명으로 처리한다")
    void resultUnknown_whenAmountDiffers() {
        respondConfirm("DONE", AMOUNT - 1);

        assertThatThrownBy(() -> client.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("4xx 는 PG 가 거절한 것이므로 승인 실패로 처리한다")
    void approvalFailed_whenRejected() {
        server.expect(requestTo(CONFIRM_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_APPROVAL_FAILED);
    }

    private void respondConfirm(String status, int totalAmount) {
        server.expect(requestTo(CONFIRM_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"paymentKey": "%s", "orderId": "%s", "status": "%s", "totalAmount": %d}
                        """.formatted(PAYMENT_KEY, ORDER_ID, status, totalAmount), MediaType.APPLICATION_JSON));
    }
}
