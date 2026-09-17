package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.AddressRepository;
import ccommit.stylehub.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 배송지 API의 인증·인가·입력 검증·응답 형식을 HTTP 계층에서 검증한다.
 * 인터셉터, /api/v1 프리픽스, GlobalExceptionHandler가 실제로 조합돼야 401/403/400을 확인할 수 있어 전체 컨텍스트 위에 MockMvc를 올린다.
 * </p>
 */
@SpringBootTest
class AddressControllerTest {

    private static final String BASE_URL = "/api/v1/users/me/addresses";

    private static final String VALID_BODY = """
            {
              "label": "집",
              "recipientName": "홍길동",
              "phone": "01012345678",
              "zipCode": "06236",
              "streetAddress": "서울시 강남구 테헤란로 1",
              "detailAddress": "101호"
            }
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AddressRepository addressRepository;

    private MockMvc mockMvc;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // FK 의존 순서대로 지운다: addresses → users
    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(userId ->
                addressRepository.deleteAll(addressRepository.findAllByUserIdOrderByDefaultFirst(userId)));
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("비로그인 요청은 401(A001)로 거절된다")
    void returns401_whenNotLoggedIn() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A001"));
    }

    @Test
    @DisplayName("STORE 역할 세션은 403(A002)으로 거절된다")
    void returns403_whenStoreRole() throws Exception {
        MockHttpSession storeSession = session(1L, UserRole.STORE);

        mockMvc.perform(post(BASE_URL)
                        .session(storeSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A002"));
    }

    @Test
    @DisplayName("정상 등록은 201 과 저장된 배송지를 반환하고, 첫 배송지는 기본으로 표시된다")
    void returns201WithBody_whenValidRequest() throws Exception {
        MockHttpSession userSession = session(createUser(), UserRole.USER);

        mockMvc.perform(post(BASE_URL)
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressId").isNumber())
                .andExpect(jsonPath("$.label").value("집"))
                .andExpect(jsonPath("$.recipientName").value("홍길동"))
                .andExpect(jsonPath("$.phone").value("01012345678"))
                .andExpect(jsonPath("$.zipCode").value("06236"))
                .andExpect(jsonPath("$.streetAddress").value("서울시 강남구 테헤란로 1"))
                .andExpect(jsonPath("$.detailAddress").value("101호"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("연락처에 하이픈이 있으면 400(C001)으로 거절된다")
    void returns400_whenPhoneHasHyphen() throws Exception {
        MockHttpSession userSession = session(createUser(), UserRole.USER);
        String body = VALID_BODY.replace("01012345678", "010-1234-5678");

        mockMvc.perform(post(BASE_URL)
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value(startsWith("phone")));
    }

    @Test
    @DisplayName("우편번호가 5자리 숫자가 아니거나 수취인이 1자면 400(C001)으로 거절된다")
    void returns400_whenZipCodeOrRecipientInvalid() throws Exception {
        MockHttpSession userSession = session(createUser(), UserRole.USER);

        mockMvc.perform(post(BASE_URL)
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("06236", "1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"));

        mockMvc.perform(post(BASE_URL)
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("홍길동", "홍")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"));
    }

    @Test
    @DisplayName("기본 변경은 200, 목록은 기본 먼저, 삭제는 204 로 응답한다")
    void changeDefaultListAndDelete_returnExpectedStatuses() throws Exception {
        MockHttpSession userSession = session(createUser(), UserRole.USER);
        long firstId = registerAndGetId(userSession);
        long secondId = registerAndGetId(userSession);

        mockMvc.perform(patch(BASE_URL + "/" + secondId + "/default").session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(secondId))
                .andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(get(BASE_URL).session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].addressId").value(secondId))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].addressId").value(firstId))
                .andExpect(jsonPath("$[1].isDefault").value(false));

        mockMvc.perform(delete(BASE_URL + "/" + firstId).session(userSession))
                .andExpect(status().isNoContent());

        assertThat(addressRepository.findById(firstId)).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 배송지를 삭제하려 하면 404(OR005)로 응답해 존재 여부를 드러내지 않는다")
    void returns404_whenDeletingOthersAddress() throws Exception {
        MockHttpSession ownerSession = session(createUser(), UserRole.USER);
        long ownersAddressId = registerAndGetId(ownerSession);
        MockHttpSession otherSession = session(createUser(), UserRole.USER);

        mockMvc.perform(delete(BASE_URL + "/" + ownersAddressId).session(otherSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OR005"));

        assertThat(addressRepository.findById(ownersAddressId)).isPresent();
    }

    // ===== Helper =====

    private MockHttpSession session(Long userId, UserRole role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
        return session;
    }

    private Long createUser() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.create(
                "addr" + unique,
                "addr-" + unique + "@test.com",
                "password",
                LocalDate.of(2000, 1, 1),
                UserRole.USER
        ));
        createdUserIds.add(user.getUserId());
        return user.getUserId();
    }

    private long registerAndGetId(MockHttpSession userSession) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_URL)
                        .session(userSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        Number addressId = JsonPath.read(result.getResponse().getContentAsString(), "$.addressId");
        return addressId.longValue();
    }
}
