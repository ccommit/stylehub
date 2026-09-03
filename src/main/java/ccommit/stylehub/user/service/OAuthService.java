package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
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
 *
 * <p>
 * OAuth 소셜 로그인의 비즈니스 로직을 처리한다.
 * 동시 가입 요청에 대한 동시성 대응 로직을 포함한다.
 * </p>
 */
@Service
public class OAuthService {

    private final Map<OAuthProvider, OAuthClient> clients;
    private final UserRepository userRepository;
    private final UserService userService;
    private final TransactionTemplate transactionTemplate;

    public OAuthService(List<OAuthClient> clientList,
                        UserRepository userRepository,
                        UserService userService,
                        TransactionTemplate transactionTemplate) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(OAuthClient::provider, Function.identity()));
        this.userRepository = userRepository;
        this.userService = userService;
        this.transactionTemplate = transactionTemplate;
    }

    public String getAuthorizationUrl(OAuthProvider provider) {
        return getClient(provider).getAuthorizationUrl();
    }

    public OAuthLoginResponse login(OAuthProvider provider, String code) {
        OAuthUserInfo userInfo = getClient(provider).authenticate(code);

        try {
            return transactionTemplate.execute(status ->
                    userRepository.findByEmail(userInfo.email())
                            .map(user -> handleExistingUser(user, provider))
                            .orElseGet(() -> handleNewUser(userInfo, provider))
            );
        } catch (DataIntegrityViolationException e) {
            return handleConcurrentSignUp(userInfo);
        }
    }

    private OAuthLoginResponse handleExistingUser(User user, OAuthProvider provider) {
        if (user.getProvider() == null) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED_EMAIL);
        }
        rewardIfUser(user);
        return OAuthLoginResponse.from(user, false);
    }

    private OAuthLoginResponse handleNewUser(OAuthUserInfo userInfo, OAuthProvider provider) {
        User newUser = User.createOAuth(
                userInfo.name(), userInfo.email(), provider, userInfo.providerId()
        );
        User savedUser = userRepository.save(newUser);
        rewardIfUser(savedUser);
        return OAuthLoginResponse.from(savedUser, true);
    }

    // 동시 요청으로 이미 저장된 경우 → 재조회하여 기존 유저로 로그인 처리
    private OAuthLoginResponse handleConcurrentSignUp(OAuthUserInfo userInfo) {
        return transactionTemplate.execute(status -> {
            User user = userRepository.findByEmail(userInfo.email()).orElseThrow();
            rewardIfUser(user);
            return OAuthLoginResponse.from(user, false);
        });
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
