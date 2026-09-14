package ccommit.stylehub.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/09/08 by WonJin - fix: RestTemplate 에 연결/읽기 타임아웃 설정 (기본값이 무제한이라 PG 무응답 시 무한 대기)
 *
 * <p>
 * 결제 관련 Bean 설정을 담당한다.
 * </p>
 */
@Configuration
public class PaymentConfig {

    /**
     * 연결 타임아웃. 3초 안에 TCP 연결이 되지 않으면 네트워크나 PG 측 장애로 보고 더 기다리지 않는다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 읽기 타임아웃. PG 의 승인 처리 시간을 감안하되, 결제 대기 주문의 만료 시간(10분)보다는 훨씬 짧아야
     * 만료 처리 전에 요청이 정리된다.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 외부 PG 호출용 RestTemplate.
     *
     * <p>인자 없는 {@code new RestTemplate()} 은 SimpleClientHttpRequestFactory 기본값을 사용하는데,
     * 연결/읽기 타임아웃이 모두 무제한이다. PG 가 응답하지 않으면 스레드가 무한정 대기한다.
     * 승인 호출은 트랜잭션 안에서 결제 행에 비관적 락을 잡은 상태로 이루어지므로, 그 동안 DB 커넥션과
     * 행 락, 톰캣 스레드를 함께 점유한다. 요청이 쌓이면 커넥션 풀이 고갈되어 상품 조회나 로그인처럼
     * 결제와 무관한 API 까지 함께 밀린다. 타임아웃을 두는 것은 응답을 빨리 받기 위해서가 아니라
     * 이 전파 경로를 끊기 위해서다.
     *
     * <p><strong>재시도는 두지 않는다.</strong> 승인 요청은 응답을 받지 못했을 뿐 PG 쪽에서는 이미
     * 승인이 완료됐을 수 있어, 재시도가 그대로 이중 결제가 된다. 응답 유실 구간은 재시도가 아니라
     * 승인 결과를 다시 조회해 대조하거나 웹훅을 수신하는 방식으로 닫아야 한다.
     *
     * <p><strong>이 설정은 공짜가 아니다.</strong> 타임아웃이 없을 때는 PG 가 느려도 결국 응답을 받으면
     * 정상 처리됐다. 타임아웃을 걸면 제한 시간에서 끊고 롤백하는데, 그 사이 PG 는 승인을 완료했을 수 있다.
     * 즉 무한 대기라는 큰 위험을 줄이는 대신 응답 유실이라는 작은 위험의 빈도를 높인다.
     * 무한 대기는 커넥션과 락을 점유해 서비스 전체를 마비시키므로 이 교환 자체는 타당하지만,
     * 유실 구간이 남는다는 사실은 그대로다.
     *
     * <p>읽기 제한 시간 10초가 적절한 값인지는 PG 의 실제 승인 응답 시간 분포를 측정해야 알 수 있는데
     * 아직 측정하지 않았다. 늘리면 유실은 줄고 자원 점유 시간은 늘어난다.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }
}
