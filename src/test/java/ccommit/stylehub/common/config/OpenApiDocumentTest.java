package ccommit.stylehub.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/24
 *
 * <p>
 * springdoc 이 만드는 API 명세가 실제 API·접근 정책과 맞는지, 저장소의 docs/api/openapi.json 이 최신인지 검증한다.
 * 운영에서는 문서 엔드포인트를 끄므로 저장소 파일이 공개 명세다. 코드를 바꾸고 파일을 갱신하지 않으면 이 테스트가 실패한다.
 * 갱신: ./gradlew test --tests '*OpenApiDocumentTest' -Dopenapi.update=true
 * </p>
 */
@SpringBootTest
class OpenApiDocumentTest {

    private static final Path COMMITTED_SPEC = Path.of("docs/api/openapi.json");
    private static final String UPDATE_FLAG = "openapi.update";
    private static final String APPLICATION_PACKAGE = "ccommit.stylehub";
    private static final String SESSION_SCHEME = "session";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("명세와 Swagger UI 는 /api/v1 접두사 없이 제공된다 (접두사는 애플리케이션 컨트롤러에만 붙는다)")
    void docsAreServedWithoutApiPrefix() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("등록된 모든 API 가 명세에 빠짐없이 있다")
    void everyApiIsDocumented() throws Exception {
        JsonNode paths = generatedSpec().path("paths");
        Set<String> documented = new TreeSet<>();
        paths.properties().forEach(path -> path.getValue().properties()
                .forEach(operation -> documented.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey())));

        assertThat(documented).containsExactlyInAnyOrderElementsOf(registeredApis());
    }

    @Test
    @DisplayName("공개 API 에는 세션 요구가 없고, 로그인 API 에는 세션 요구와 필요 역할이 표시된다")
    void accessPolicyIsDocumented() throws Exception {
        JsonNode paths = generatedSpec().path("paths");
        JsonNode publicList = paths.path("/api/v1/products").path("get");
        JsonNode createOrder = paths.path("/api/v1/orders").path("post");
        JsonNode updateStoreStatus = paths.path("/api/v1/admin/stores/{storeId}/status").path("patch");

        assertThat(publicList.has("security")).isFalse();
        assertThat(createOrder.path("security").toString()).contains(SESSION_SCHEME);
        assertThat(createOrder.path("description").asString()).contains("필요 역할: USER");
        assertThat(updateStoreStatus.path("description").asString()).contains("필요 역할: ADMIN");
    }

    @Test
    @DisplayName("저장소의 docs/api/openapi.json 이 현재 코드로 만든 명세와 같다")
    void committedSpecIsUpToDate() throws Exception {
        JsonNode generated = generatedSpec();
        if (Boolean.getBoolean(UPDATE_FLAG)) {
            Files.createDirectories(COMMITTED_SPEC.getParent());
            Files.writeString(COMMITTED_SPEC, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generated) + "\n",
                    StandardCharsets.UTF_8);
        }

        assertThat(COMMITTED_SPEC).as("명세 파일이 없다. -D%s=true 로 생성한다", UPDATE_FLAG).exists();
        JsonNode committed = MAPPER.readTree(Files.readString(COMMITTED_SPEC, StandardCharsets.UTF_8));
        assertThat(committed)
                .as("API 가 바뀌었는데 명세 파일을 갱신하지 않았다. -D%s=true 로 다시 생성한다", UPDATE_FLAG)
                .isEqualTo(generated);
    }

    // ===== Helper =====

    private JsonNode generatedSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(body);
    }

    private List<String> registeredApis() {
        List<String> apis = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!handlerMethod.getBeanType().getPackageName().startsWith(APPLICATION_PACKAGE)) {
                return;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                for (String pattern : info.getPatternValues()) {
                    apis.add(method.name() + " " + pattern);
                }
            }
        });
        return apis;
    }
}
