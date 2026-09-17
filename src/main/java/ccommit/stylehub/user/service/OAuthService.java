package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/20 11:40 by WonJin - refactor: OAuthService.login() 메서드 추출로 가독성 개선
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/04/19 by WonJin - refactor: LoginEvent 제거, UserService.rewardLoginPoint() 직접 호출
 * @modified 2026/09/17 by WonJin - fix: 표시 이름을 닉네임 규칙에 맞춰 정규화·중복 시 접미사 부여, 동시 가입 재조회가 비면 NoSuchElementException(500) 대신 새 접미사로 1회 재시도 후 OAUTH_NICKNAME_CONFLICT(409)
 *
 * <p>
 * OAuth 소셜 로그인의 비즈니스 로직을 처리한다.
 * 동시 가입은 유니크 제약 위반 시 이메일로 재조회하고, 비어 있으면 닉네임 충돌로 보고 새 접미사로 한 번 더 가입한다.
 * </p>
 */
@Slf4j
@Service
public class OAuthService {

    private final Map<OAuthProvider, OAuthClient> clients;
    private final UserRepository userRepository;
    private final UserService userService;
    private final TransactionTemplate transactionTemplate;
    private final SocialNicknameGenerator nicknameGenerator;

    public OAuthService(List<OAuthClient> clientList,
                        UserRepository userRepository,
                        UserService userService,
                        TransactionTemplate transactionTemplate,
                        SocialNicknameGenerator nicknameGenerator) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(OAuthClient::provider, Function.identity()));
        this.userRepository = userRepository;
        this.userService = userService;
        this.transactionTemplate = transactionTemplate;
        this.nicknameGenerator = nicknameGenerator;
    }

    public String getAuthorizationUrl(OAuthProvider provider, String state) {
        return getClient(provider).getAuthorizationUrl(state);
    }

    public OAuthLoginResponse login(OAuthProvider provider, String code) {
        OAuthUserInfo userInfo = getClient(provider).authenticate(code);

        try {
            return transactionTemplate.execute(status ->
                    userRepository.findByEmail(userInfo.email())
                            .map(this::handleExistingUser)
                            .orElseGet(() -> handleNewUser(userInfo, provider, false))
            );
        } catch (DataIntegrityViolationException e) {
            return handleConcurrentSignUp(userInfo, provider);
        }
    }

    private OAuthLoginResponse handleExistingUser(User user) {
        if (user.getProvider() == null) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED_EMAIL);
        }
        rewardIfUser(user);
        return OAuthLoginResponse.from(user, false);
    }

    private OAuthLoginResponse handleNewUser(OAuthUserInfo userInfo, OAuthProvider provider, boolean forceSuffix) {
        String nickname = forceSuffix
                ? nicknameGenerator.generateWithSuffix(userInfo.name(), userRepository::existsByName)
                : nicknameGenerator.generate(userInfo.name(), userRepository::existsByName);
        User newUser = User.createOAuth(nickname, userInfo.email(), provider, userInfo.providerId());
        User savedUser = userRepository.save(newUser);
        rewardIfUser(savedUser);
        return OAuthLoginResponse.from(savedUser, true);
    }

    // 재조회가 비면 닉네임 선점으로 보고 한 번만 재가입하고, 그마저 실패하면 반복 재시도 대신 409로 응답한다.
    // 원인을 제약 이름으로 가르지 않는 것은 운영 스키마의 제약 이름을 저장소에서 확인할 수 없어서다.
    private OAuthLoginResponse handleConcurrentSignUp(OAuthUserInfo userInfo, OAuthProvider provider) {
        try {
            return transactionTemplate.execute(status ->
                    userRepository.findByEmail(userInfo.email())
                            .map(this::handleExistingUser)
                            .orElseGet(() -> handleNewUser(userInfo, provider, true))
            );
        } catch (DataIntegrityViolationException e) {
            log.warn("소셜 가입 재시도 실패: 닉네임 유니크 제약 위반이 반복됨 provider={}", provider);
            throw new BusinessException(ErrorCode.OAUTH_NICKNAME_CONFLICT);
        }
    }

    private void rewardIfUser(User user) {
        if (user.getRole() == UserRole.USER) {
            userService.rewardLoginPoint(user.getUserId(), LocalDate.now());
        }
    }

    private OAuthClient getClient(OAuthProvider provider) {
        OAuthClient client = clients.get(provider);
        if (client == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
        return client;
    }
}
