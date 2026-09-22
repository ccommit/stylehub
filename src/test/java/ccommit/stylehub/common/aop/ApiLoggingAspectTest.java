package ccommit.stylehub.common.aop;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.exception.GlobalExceptionHandler;
import ccommit.stylehub.payment.service.PaymentService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * ApiLoggingAspect 가 주문·결제 컨트롤러 진입점에서만 요청당 INFO 한 줄을 남기고, 인자는 DEBUG 로만, 예외는 기록하지 않는지 검증한다.
 * 이전에는 order·payment 패키지 전체를 감싸 서비스·리포지토리·PG 클라이언트 호출마다 INFO 가 쌓이고
 * 같은 비즈니스 예외가 계층마다 ERROR 로 반복 기록됐다.
 * </p>
 *
 * <p>
 * <b>@SpringBootTest 를 쓰는 이유</b>
 * 포인트컷이 실제 프록시에 어떻게 걸리는지(컨트롤러는 감싸고 서비스는 감싸지 않는지)는 스프링 AOP 가 적용된 빈으로만 확인할 수 있다.
 * </p>
 */
@SpringBootTest
class ApiLoggingAspectTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentService paymentService;

    private final Logger apiLogger = (Logger) LoggerFactory.getLogger(ApiLoggingAspect.class);
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        logAppender.start();
        apiLogger.addAppender(logAppender);
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        apiLogger.detachAppender(logAppender);
        handlerLogger.detachAppender(logAppender);
        apiLogger.setLevel(null);
        logAppender.stop();
    }

    @Test
    @DisplayName("결제 API 요청 하나에 컨트롤러 AOP 는 INFO 한 줄만 남기고, 비즈니스 예외는 예외 핸들러가 warn 으로 한 번만 기록한다")
    void 요청당_INFO_한줄과_예외로그_한번() throws Exception {
        // given
        String pgOrderId = "ORD-AOP-" + UUID.randomUUID();

        // when — 없는 주문이라 서비스가 PAYMENT_NOT_FOUND 를 던진다
        mockMvc.perform(failCallback(pgOrderId))
                .andExpect(status().isNotFound());

        // then
        List<ILoggingEvent> apiLogs = eventsOf(ApiLoggingAspect.class);
        assertThat(apiLogs).hasSize(1);
        ILoggingEvent apiLog = apiLogs.get(0);
        assertThat(apiLog.getLevel()).isEqualTo(Level.INFO);
        assertThat(apiLog.getFormattedMessage())
                .contains("GET /api/v1/payments/fail")
                .contains("PaymentController.paymentFail")
                .contains("outcome=BusinessException")
                // 인자·쿼리 문자열은 INFO 에 남기지 않는다
                .doesNotContain("args")
                .doesNotContain(pgOrderId);

        List<ILoggingEvent> handlerLogs = eventsOf(GlobalExceptionHandler.class);
        assertThat(handlerLogs).hasSize(1);
        assertThat(handlerLogs.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("인자 값은 DEBUG 레벨에서만 남는다")
    void 인자는_DEBUG에서만_남긴다() throws Exception {
        // given
        apiLogger.setLevel(Level.DEBUG);
        String pgOrderId = "ORD-AOP-" + UUID.randomUUID();

        // when
        mockMvc.perform(failCallback(pgOrderId))
                .andExpect(status().isNotFound());

        // then
        List<ILoggingEvent> apiLogs = eventsOf(ApiLoggingAspect.class);
        assertThat(apiLogs).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.INFO);
        assertThat(apiLogs.get(0).getFormattedMessage()).contains("args=").contains(pgOrderId);
    }

    @Test
    @DisplayName("컨트롤러를 거치지 않는 서비스 호출은 API 로그를 남기지 않는다 (서비스·리포지토리 계층은 포인트컷 밖)")
    void 서비스_직접_호출은_기록하지_않는다() {
        // when
        assertThatThrownBy(() -> paymentService.handlePaymentFailure("ORD-AOP-" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);

        // then
        assertThat(eventsOf(ApiLoggingAspect.class)).isEmpty();
    }

    private MockHttpServletRequestBuilder failCallback(String pgOrderId) {
        return get("/api/v1/payments/fail")
                .param("code", "PAY_PROCESS_CANCELED")
                .param("orderId", pgOrderId);
    }

    private List<ILoggingEvent> eventsOf(Class<?> loggerClass) {
        return logAppender.list.stream()
                .filter(event -> event.getLoggerName().equals(loggerClass.getName()))
                .toList();
    }
}
