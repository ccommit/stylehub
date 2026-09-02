package ccommit.stylehub.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author WonJin Bae
 * @created 2026/05/06
 *
 * <p>
 * 비동기 작업용 스레드풀 설정. 도메인별로 별도 풀을 두어 *한 영역의 폭주가 다른 영역까지 전염* 되지 않게 분리.
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 선착순 쿠폰 발급의 *DB INSERT* 비동기 처리용 스레드풀.
     *
     * <p>- core/max 20/50 → 동시 50 INSERT 처리 가능
     * <br>- queue 1000 → 폭주 시 1,000 이벤트까지 버퍼링
     * <br>- CALLER_RUNS 정책 → 큐 가득 차면 publisher 스레드가 직접 처리 (드롭 방지)
     */
    @Bean(name = "couponInsertExecutor")
    public Executor couponInsertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("coupon-insert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
