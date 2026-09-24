package ccommit.stylehub.common.config;

import ccommit.stylehub.user.enums.UserRole;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/09/24 by WonJin - feat: springdoc 3.0.x 로 API 문서 활성화, 세션 쿠키 요구와 필요 역할을 인터셉터 설정에서 자동 표시
 *
 * <p>
 * Swagger(SpringDoc) 설정을 담당한다. 로컬에서 /swagger-ui/index.html 로 API 문서를 확인할 수 있고, 운영 프로파일에서는 끈다.
 * 로그인 필요 여부는 인증 인터셉터와 같은 공개 경로 목록(WebConfig.PUBLIC_API_PATTERNS)으로, 필요 역할은 @RequiredRole 로 표시해
 * 문서가 실제 접근 정책과 따로 놀지 않게 한다.
 * </p>
 */
@Configuration
public class SwaggerConfig {

    private static final String SESSION_SCHEME = "session";
    private static final String SESSION_COOKIE = "SESSION";

    private static final List<PathPattern> PUBLIC_PATHS = WebConfig.PUBLIC_API_PATTERNS.stream()
            .map(PathPatternParser.defaultInstance::parse)
            .toList();

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("StyleHub API")
                        .description("패션 이커머스 StyleHub 백엔드 API. 로그인이 필요한 API 는 POST /api/v1/users/login 으로 받은 SESSION 쿠키로 인증한다.")
                        .version("v1"))
                // 요청 호스트에 상관없이 문서를 연 서버로 호출하도록 상대 경로로 둔다
                .addServersItem(new Server().url("/"))
                .components(new Components().addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name(SESSION_COOKIE)));
    }

    // 공개 경로가 아닌 API 에만 세션 쿠키 요구를 붙인다
    @Bean
    public OpenApiCustomizer sessionRequirementCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (isPublic(path)) {
                return;
            }
            item.readOperations().forEach(operation ->
                    operation.addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME)));
        });
    }

    // RoleCheckInterceptor 와 같은 규칙(메서드 우선, 없으면 클래스)으로 필요 역할을 설명에 덧붙인다
    @Bean
    public OperationCustomizer requiredRoleCustomizer() {
        return (operation, handlerMethod) -> {
            RequiredRole requiredRole = handlerMethod.getMethodAnnotation(RequiredRole.class);
            if (requiredRole == null) {
                requiredRole = handlerMethod.getBeanType().getAnnotation(RequiredRole.class);
            }
            if (requiredRole != null) {
                appendDescription(operation, "필요 역할: " + roles(requiredRole.value()));
            }
            return operation;
        };
    }

    private static boolean isPublic(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pattern.matches(container));
    }

    private static String roles(UserRole[] roles) {
        return Arrays.stream(roles).map(UserRole::name).collect(Collectors.joining(", "));
    }

    private static void appendDescription(Operation operation, String line) {
        String current = operation.getDescription();
        operation.setDescription(current == null || current.isBlank() ? line : current + "\n\n" + line);
    }
}
