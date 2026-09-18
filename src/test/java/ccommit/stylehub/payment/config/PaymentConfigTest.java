package ccommit.stylehub.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/08
 *
 * <p>
 * PG 호출용 RestTemplate 에 타임아웃이 실제로 설정되는지 검증한다.
 *
 * <b>이 테스트가 막는 것</b>
 * 인자 없는 {@code new RestTemplate()} 은 연결/읽기 타임아웃이 모두 무제한이다.
 * 기본값이라 아무 에러도 나지 않고, PG 가 응답하지 않는 상황이 실제로 발생하기 전까지 드러나지 않는다.
 * 누군가 설정을 되돌리거나 빈을 단순화해도 컴파일과 기존 테스트는 전부 통과하므로,
 * 값이 기본값으로 돌아가는 것 자체를 실패로 잡는다.
 *
 * <b>왜 실제 호출로 검증하지 않는가</b>
 * 응답하지 않는 서버를 띄워 타임아웃을 재는 방식은 읽기 타임아웃만큼(10초) 테스트가 지연되고
 * 네트워크 환경에 따라 불안정하다. 설정값이 전달됐는지만 확인하면 이 결함을 막기에 충분하다.
 * </p>
 */
class PaymentConfigTest {

    private static final int NO_TIMEOUT = -1;

    @Test
    @DisplayName("PG 호출용 RestTemplate 에 연결·읽기 타임아웃이 설정된다")
    void configuresTimeouts_onPaymentRestTemplate() {
        // given
        RestTemplate restTemplate = new PaymentConfig().restTemplate();

        // when
        ClientHttpRequestFactory factory = restTemplate.getRequestFactory();

        // then
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);

        int connectTimeout = (int) ReflectionTestUtils.getField(factory, "connectTimeout");
        int readTimeout = (int) ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(connectTimeout)
                .as("연결 타임아웃이 기본값(무제한)이면 PG 장애가 그대로 전파된다")
                .isNotEqualTo(NO_TIMEOUT)
                .isPositive();

        assertThat(readTimeout)
                .as("읽기 타임아웃이 기본값(무제한)이면 응답을 기다리며 커넥션과 락을 계속 점유한다")
                .isNotEqualTo(NO_TIMEOUT)
                .isPositive();
    }

    @Test
    @DisplayName("읽기 타임아웃은 결제 대기 주문의 만료 시간(10분)보다 짧다")
    void readTimeoutIsShorterThanOrderExpiry() {
        // given — 만료 처리가 돌기 전에 대기 중인 요청이 정리되어야 한다
        long orderExpiryMillis = 10 * 60 * 1000L;
        RestTemplate restTemplate = new PaymentConfig().restTemplate();

        // when
        int readTimeout = (int) ReflectionTestUtils.getField(restTemplate.getRequestFactory(), "readTimeout");

        // then
        assertThat((long) readTimeout).isLessThan(orderExpiryMillis);
    }
}
