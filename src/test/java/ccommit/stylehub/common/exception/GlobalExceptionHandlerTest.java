package ccommit.stylehub.common.exception;

import ccommit.stylehub.common.constants.TraceConstants;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * GlobalExceptionHandler 가 프레임워크·인프라 예외를 원인별 상태 코드와 에러 코드로 바꾸고, 예외 로그를 요청당 한 번만 남기는지 검증한다.
 * 추가 전에는 이 예외들이 모두 Exception 핸들러로 떨어져 500 이 됐다.
 * </p>
 *
 * <p>
 * <b>standaloneSetup 을 쓰는 이유</b>
 * 415·헤더 누락·경로 변수 누락은 서비스 코드가 아니라 스프링 MVC 가 인자를 해석하다 던진다. 실제 디스패처가 예외를 만들고
 * @ExceptionHandler 를 고르는 과정(가장 가까운 타입 우선)을 그대로 거쳐야 Exception 핸들러에 가로채이지 않는다는 것을 확인할 수 있다.
 * 애플리케이션 컨텍스트가 필요 없어 테스트용 컨트롤러와 핸들러만 올린다.
 * 테스트용 컨트롤러를 static 이 아닌 내부 클래스로 두는 이유: @SpringBootTest 의 컴포넌트 스캔은 테스트 클래스패스도 훑는데,
 * 독립 클래스가 아닌 내부 클래스는 후보에서 빠져 다른 통합 테스트 컨텍스트에 테스트 API 가 등록되지 않는다.
 * </p>
 */
class GlobalExceptionHandlerTest {

    private static final String UUID_FORMAT = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private final ThrowingController controller = new ThrowingController();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("무결성 제약 위반은 409 DATA_INTEGRITY_CONFLICT 이고, 응답에 제약 이름·SQL·입력값이 없으며 로그에는 제약 이름만 남는다")
    void 무결성_위반은_409() throws Exception {
        // given — MySQL 중복 키 위반이 하이버네이트·스프링 예외로 감싸져 올라오는 형태
        controller.exceptionToThrow = new DataIntegrityViolationException(
                "could not execute statement [Duplicate entry 'dup@stylehub.com' for key 'uk_users_email'] "
                        + "[insert into users (email) values (?)]; SQL [insert into users (email) values (?)]; constraint [uk_users_email]",
                new ConstraintViolationException(
                        "could not execute statement",
                        new SQLIntegrityConstraintViolationException("Duplicate entry 'dup@stylehub.com' for key 'uk_users_email'", "23000", 1062),
                        "insert into users (email) values (?)",
                        ConstraintViolationException.ConstraintKind.UNIQUE,
                        "uk_users_email"));

        // when
        MvcResult result = mockMvc.perform(get("/test/throw"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DATA_INTEGRITY_CONFLICT.getCode()))
                .andReturn();

        // then
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("uk_users_email")
                .doesNotContain("insert into")
                .doesNotContain("SQL")
                .doesNotContain("dup@stylehub.com");
        ILoggingEvent logged = loggedOnceAt(Level.WARN);
        assertThat(logged.getFormattedMessage())
                .contains("constraint=uk_users_email")
                .doesNotContain("dup@stylehub.com");
    }

    static Stream<RuntimeException> lockFailures() {
        return Stream.of(
                new PessimisticLockingFailureException("lock failure"),
                new CannotAcquireLockException("Lock wait timeout exceeded"),
                // EntityManager 를 직접 쓰는 서비스 경로는 스프링 예외로 변환되지 않고 JPA 표준 예외가 올라온다
                new PessimisticLockException("pessimistic lock"),
                new LockTimeoutException("lock timeout")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockFailures")
    @DisplayName("락 획득 실패(스프링 변환 예외와 JPA 표준 예외 모두)는 409 LOCK_ACQUISITION_FAILED 로 응답하고 warn 을 한 번 남긴다")
    void 락_획득_실패는_409(RuntimeException lockFailure) throws Exception {
        // given
        controller.exceptionToThrow = lockFailure;

        // when & then
        mockMvc.perform(get("/test/throw"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.LOCK_ACQUISITION_FAILED.getCode()));

        loggedOnceAt(Level.WARN);
    }

    static Stream<RuntimeException> temporaryInfrastructureFailures() {
        return Stream.of(
                new RedisConnectionFailureException("Unable to connect to Redis"),
                // Lettuce 명령 타임아웃(RedisCommandTimeoutException)은 스프링 데이터 레디스가 이 타입으로 바꾼다
                new QueryTimeoutException("Redis command timed out")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("temporaryInfrastructureFailures")
    @DisplayName("Redis 연결 실패·명령 타임아웃은 503 SERVICE_TEMPORARILY_UNAVAILABLE 로 응답하고 error 를 한 번 남긴다")
    void Redis_일시장애는_503(RuntimeException failure) throws Exception {
        // given
        controller.exceptionToThrow = failure;

        // when & then
        mockMvc.perform(get("/test/throw"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE.getCode()));

        loggedOnceAt(Level.ERROR);
    }

    @Test
    @DisplayName("인프라 예외를 cause 로 감싼 비즈니스 예외는 도메인 에러 코드를 유지한다 (Redis 핸들러에 가로채이지 않음)")
    void 비즈니스_예외는_감싼_원인과_무관하게_자기_코드로_응답한다() throws Exception {
        // given — 선착순 쿠폰은 Redis 장애를 CP016 으로 바꿔 던진다
        controller.exceptionToThrow = new BusinessException(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE,
                new RedisConnectionFailureException("Unable to connect to Redis"));

        // when & then
        mockMvc.perform(get("/test/throw"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE.getCode()));

        loggedOnceAt(Level.WARN);
    }

    @Test
    @DisplayName("JSON 을 받는 API 에 text/plain 으로 요청하면 415 UNSUPPORTED_MEDIA_TYPE 이다")
    void 지원하지_않는_ContentType은_415() throws Exception {
        mockMvc.perform(post("/test/json-body")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=stylehub"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()));

        loggedOnceAt(Level.WARN);
    }

    @Test
    @DisplayName("필수 요청 헤더가 없으면 400 INVALID_INPUT 이고 메시지에 헤더 이름이 담긴다")
    void 필수_헤더_누락은_400() throws Exception {
        mockMvc.perform(get("/test/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()))
                .andExpect(jsonPath("$.message").value("X-Client-Version 헤더가 필요합니다"));

        loggedOnceAt(Level.WARN);
    }

    @Test
    @DisplayName("경로 변수 값이 변환 후 null 이면 클라이언트 입력 문제라 400 INVALID_INPUT 이다")
    void 경로_변수_변환결과_없음은_400() throws Exception {
        mockMvc.perform(get("/test/path-variable-converted-to-null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));

        loggedOnceAt(Level.WARN);
    }

    // URI 템플릿에 없는 경로 변수를 선언한 매핑 실수는 클라이언트가 고칠 수 없어 400 이 아니다(스프링 기본 분류와 같음).
    @Test
    @DisplayName("URI 템플릿과 @PathVariable 선언이 어긋난 매핑 실수는 서버 결함이라 500 INTERNAL_SERVER_ERROR 와 error 로그다")
    void 경로_변수_매핑_실수는_500() throws Exception {
        mockMvc.perform(get("/test/path-variable-mismatch"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_SERVER_ERROR.getCode()));

        loggedOnceAt(Level.ERROR);
    }

    @Test
    @DisplayName("분류되지 않은 예외는 기존대로 500 INTERNAL_SERVER_ERROR 와 error 로그다")
    void 처리하지_못한_예외는_500() throws Exception {
        controller.exceptionToThrow = new IllegalStateException("unexpected");

        mockMvc.perform(get("/test/throw"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_SERVER_ERROR.getCode()));

        loggedOnceAt(Level.ERROR);
    }

    @Test
    @DisplayName("요청 MDC 에 traceId 가 있으면 에러 응답의 traceId 는 그 값이다")
    void 에러응답_traceId는_MDC_값을_쓴다() throws Exception {
        // given — 운영에서는 TraceIdFilter 가 넣는 값
        String traceId = "trace-from-filter-0001";
        MDC.put(TraceConstants.TRACE_ID_MDC_KEY, traceId);
        controller.exceptionToThrow = new BusinessException(ErrorCode.ORDER_NOT_FOUND);

        // when & then
        mockMvc.perform(get("/test/throw"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    @DisplayName("필터를 거치지 않아 MDC 에 traceId 가 없으면 에러 응답의 traceId 를 새로 만든다")
    void MDC가_비어있으면_traceId를_새로_만든다() throws Exception {
        controller.exceptionToThrow = new BusinessException(ErrorCode.ORDER_NOT_FOUND);

        mockMvc.perform(get("/test/throw"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_FORMAT)));
    }

    private ILoggingEvent loggedOnceAt(Level level) {
        assertThat(logAppender.list).as("예외 로그는 요청당 한 번").hasSize(1);
        ILoggingEvent event = logAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(level);
        return event;
    }

    @RestController
    @RequestMapping("/test")
    class ThrowingController {

        private RuntimeException exceptionToThrow;

        @GetMapping("/throw")
        void throwPrepared() {
            throw exceptionToThrow;
        }

        @PostMapping("/json-body")
        void jsonBody(@RequestBody Map<String, Object> body) {
        }

        @GetMapping("/required-header")
        void requiredHeader(@RequestHeader("X-Client-Version") String clientVersion) {
        }

        // 템플릿에 {id} 가 없어 스프링이 MissingPathVariableException(변환 전 누락)을 던진다
        @GetMapping("/path-variable-mismatch")
        void pathVariableMismatch(@PathVariable("id") Long id) {
        }

        // 실제 요청으로는 만들기 어려운 "값은 있었지만 변환 결과가 null" 경우를 스프링과 같은 생성자로 재현한다
        @GetMapping("/path-variable-converted-to-null")
        void pathVariableConvertedToNull() throws Exception {
            MethodParameter idParameter = new MethodParameter(
                    ThrowingController.class.getDeclaredMethod("pathVariableMismatch", Long.class), 0);
            throw new MissingPathVariableException("id", idParameter, true);
        }
    }
}
