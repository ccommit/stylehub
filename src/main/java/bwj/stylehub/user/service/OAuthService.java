package bwj.stylehub.user.service;

import bwj.stylehub.user.dto.response.OAuthLoginResponse;
import bwj.stylehub.user.dto.response.OAuthUserInfo;
import bwj.stylehub.user.entity.User;
import bwj.stylehub.user.enums.Provider;
import bwj.stylehub.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OAuthService {

    private final Map<Provider, OAuthClient> clients;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public OAuthService(List<OAuthClient> clientList,
                        UserRepository userRepository,
                        TransactionTemplate transactionTemplate) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(OAuthClient::provider, Function.identity()));
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public String getAuthorizationUrl(Provider provider) {
        return getClient(provider).getAuthorizationUrl();
    }

    // TODO: 글로벌 예외 처리 PR에서 커스텀 예외 도입 예정
    public OAuthLoginResponse login(Provider provider, String code) {
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

    private OAuthLoginResponse handleExistingUser(User user, Provider provider) {
        if (user.getProvider() == null) {
            throw new IllegalArgumentException("이미 일반 회원가입으로 등록된 이메일입니다");
        }
        user.rewardLoginPoint(LocalDate.now());
        return OAuthLoginResponse.from(user, false);
    }

    private OAuthLoginResponse handleNewUser(OAuthUserInfo userInfo, Provider provider) {
        User newUser = User.createOAuth(
                userInfo.name(), userInfo.email(), provider, userInfo.providerId()
        );
        newUser.rewardLoginPoint(LocalDate.now());
        return OAuthLoginResponse.from(userRepository.save(newUser), true);
    }

    // 동시 요청으로 이미 저장된 경우 → 재조회하여 기존 유저로 로그인 처리
    private OAuthLoginResponse handleConcurrentSignUp(OAuthUserInfo userInfo) {
        return transactionTemplate.execute(status -> {
            User user = userRepository.findByEmail(userInfo.email()).orElseThrow();
            user.rewardLoginPoint(LocalDate.now());
            return OAuthLoginResponse.from(user, false);
        });
    }

    private OAuthClient getClient(Provider provider) {
        OAuthClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalArgumentException("지원하지 않는 OAuth Provider: " + provider);
        }
        return client;
    }
}
