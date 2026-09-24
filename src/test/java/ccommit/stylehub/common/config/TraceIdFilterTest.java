package ccommit.stylehub.common.config;

import ccommit.stylehub.common.aop.ApiLoggingAspect;
import ccommit.stylehub.common.constants.TraceConstants;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.exception.GlobalExceptionHandler;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.RegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.core.Ordered;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 요청 하나의 traceId 가 응답 헤더(X-Request-Id)·에러 응답 바디·로그 MDC 에서 같은 값인지, 안전하지 않은 요청 ID 는 버리는지,
 * 요청이 끝나면 MDC 가 비는지 검증한다. 필터가 Spring Session 필터보다 앞에 등록되는지도 확인한다.
 * </p>
 *
 * <p>
 * <b>@SpringBootTest + MockMvc 에 필터를 직접 더하는 이유</b>
 * 로그 MDC 까지 확인하려면 실제 컨트롤러 → 서비스 → 예외 핸들러를 거쳐야 한다. MockMvcBuilders 는 서블릿 필터를 자동 등록하지 않으므로
 * 컨텍스트의 TraceIdFilter 빈을 직접 더한다. 필터 등록 순서는 MockMvc 로 알 수 없어, 내장 톰캣이 필터를 등록할 때 쓰는
 * ServletContextInitializerBeans 로 같은 정렬 결과를 만들어 비교한다.
 * </p>
 */
@SpringBootTest
class TraceIdFilterTest {

    private static final String UUID_FORMAT = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TraceIdFilter traceIdFilter;

    @Autowired
    private ListableBeanFactory beanFactory;

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Logger apiLogger = (Logger) LoggerFactory.getLogger(ApiLoggingAspect.class);

    // MDC 는 요청이 끝나면 비워지므로, 로그가 찍히는 순간의 MDC 를 이벤트에 고정해 둔다(logback 은 MDC 를 늦게 복사한다).
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(traceIdFilter)
                .build();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
        apiLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        apiLogger.detachAppender(logAppender);
        logAppender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("형식이 안전한 X-Request-Id 는 그대로 traceId 가 되어 응답 헤더·에러 바디·로그 MDC 에 같은 값으로 남는다")
    void 안전한_요청ID는_그대로_쓴다() throws Exception {
        // given
        String requestId = "gw-" + UUID.randomUUID().toString().replace("-", "");

        // when
        MvcResult result = mockMvc.perform(failingRequest().header(TraceConstants.REQUEST_ID_HEADER, requestId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.traceId").value(requestId))
                .andReturn();

        // then
        assertThat(result.getResponse().getHeader(TraceConstants.REQUEST_ID_HEADER)).isEqualTo(requestId);
        assertLogsCarryTraceId(requestId);
    }

    @Test
    @DisplayName("X-Request-Id 가 없으면 서버가 만든 traceId 가 응답 헤더·에러 바디·로그 MDC 에 같은 값으로 남는다")
    void 요청ID가_없으면_새로_만든다() throws Exception {
        // when
        MvcResult result = mockMvc.perform(failingRequest())
                .andExpect(status().isNotFound())
                .andReturn();

        // then
        String traceId = result.getResponse().getHeader(TraceConstants.REQUEST_ID_HEADER);
        assertThat(traceId).matches(UUID_FORMAT);
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + traceId + "\"");
        assertLogsCarryTraceId(traceId);
    }

    static Stream<String> unsafeRequestIds() {
        return Stream.of(
                "abcdefgh\r\n2026-09-17 INFO forged log line",  // 줄바꿈으로 가짜 로그 줄 끼워 넣기
                "abc def ghi",                                    // 공백
                "trace<script>",                                  // 허용하지 않는 문자
                "short",                                          // 8자 미만
                "a".repeat(65)                                    // 64자 초과
        );
    }

    @ParameterizedTest
    @MethodSource("unsafeRequestIds")
    @DisplayName("형식이 안전하지 않은 X-Request-Id 는 버리고 서버가 새 traceId 를 만든다 (요청은 거절하지 않는다)")
    void 안전하지_않은_요청ID는_무시한다(String unsafeRequestId) throws Exception {
        // when
        MvcResult result = mockMvc.perform(failingRequest().header(TraceConstants.REQUEST_ID_HEADER, unsafeRequestId))
                .andExpect(status().isNotFound())
                .andReturn();

        // then
        String traceId = result.getResponse().getHeader(TraceConstants.REQUEST_ID_HEADER);
        assertThat(traceId).isNotEqualTo(unsafeRequestId).matches(UUID_FORMAT);
        assertLogsCarryTraceId(traceId);
    }

    @Test
    @DisplayName("요청이 끝나면 MDC 가 비워져, 같은 스레드의 다음 작업에 이전 traceId 가 남지 않는다")
    void 요청이_끝나면_MDC가_빈다() throws Exception {
        // given — 앞선 작업이 남긴 값이 있어도 요청 중에는 이번 요청 값으로 덮인다
        MDC.put(TraceConstants.TRACE_ID_MDC_KEY, "stale-trace-id-from-previous");
        String requestId = "req-" + UUID.randomUUID().toString().replace("-", "");

        // when
        mockMvc.perform(failingRequest().header(TraceConstants.REQUEST_ID_HEADER, requestId))
                .andExpect(status().isNotFound());

        // then
        assertLogsCarryTraceId(requestId);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("TraceIdFilter 는 가장 앞 순서로 등록되어 Spring Session 필터보다 먼저 실행된다")
    void Spring_Session_필터보다_앞에_등록된다() {
        // when — 내장 톰캣이 필터를 등록할 때와 같은 방식으로 정렬한 목록
        List<ServletContextInitializer> initializers = new ServletContextInitializerBeans(beanFactory).stream().toList();
        int traceFilterIndex = indexOf(initializers, initializer ->
                initializer instanceof FilterRegistrationBean<?> registration && registration.getFilter() instanceof TraceIdFilter);
        int sessionFilterIndex = indexOf(initializers, DelegatingFilterProxyRegistrationBean.class::isInstance);

        // then
        assertThat(traceFilterIndex).isNotNegative();
        assertThat(sessionFilterIndex).as("Spring Session 필터 등록 빈").isNotNegative();
        assertThat(traceFilterIndex).isLessThan(sessionFilterIndex);
        assertThat(((RegistrationBean) initializers.get(traceFilterIndex)).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    // 인증 없이 호출할 수 있고, 없는 주문이라 서비스가 PAYMENT_NOT_FOUND 를 던져 컨트롤러 AOP 로그와 예외 핸들러 로그가 함께 남는 경로
    private MockHttpServletRequestBuilder failingRequest() {
        return get("/api/v1/payments/success")
                .param("paymentKey", "pk-trace-test")
                .param("orderId", "ORD-TRACE-" + UUID.randomUUID())
                .param("amount", "10000");
    }

    private void assertLogsCarryTraceId(String traceId) {
        assertThat(logAppender.list)
                .as("컨트롤러 AOP 와 예외 핸들러가 로그를 남긴다")
                .extracting(ILoggingEvent::getLoggerName)
                .contains(ApiLoggingAspect.class.getName(), GlobalExceptionHandler.class.getName());
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry(TraceConstants.TRACE_ID_MDC_KEY, traceId));
    }

    private static int indexOf(List<ServletContextInitializer> initializers, Predicate<ServletContextInitializer> condition) {
        for (int i = 0; i < initializers.size(); i++) {
            if (condition.test(initializers.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
