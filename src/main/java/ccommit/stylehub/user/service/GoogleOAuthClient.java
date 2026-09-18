package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.GoogleOAuthProperties;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.enums.OAuthProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * @author WonJin Bae
 * @created 2026/03/16
 * @modified 2026/09/17 by WonJin - fix: 인가 URL 에 state 추가, IllegalStateException(500) 대신 4xx 는 OAUTH_AUTHENTICATION_FAILED(401), 통신 실패·비정상 응답은 OAUTH_PROVIDER_UNAVAILABLE(502) 로 전환
 *
 * <p>
 * 구글 OAuth 2.0 인가 코드 흐름(인가 URL 생성, 토큰 교환, 사용자 정보 조회)을 담당하는 클라이언트이다.
 * 구글이 거절한 요청(사용자 쪽 원인)과 구글 장애(외부 원인)를 서로 다른 비즈니스 예외로 구분해, 둘 다 서버 오류로 집계되지 않게 한다.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient implements OAuthClient {

    private final GoogleOAuthProperties properties;
    private final RestClient restClient = RestClient.create();

    private record GoogleTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Integer expiresIn
    ) {}

    private record GoogleUserInfoResponse(
            String sub,
            String name,
            String email
    ) {}

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public String getAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.authUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "email profile")
                .queryParam("state", state)
                .toUriString();
    }

    @Override
    public OAuthUserInfo authenticate(String code) {
        GoogleTokenResponse tokenResponse = exchangeCodeForToken(code);
        GoogleUserInfoResponse userInfo = getUserInfo(tokenResponse.accessToken());
        return new OAuthUserInfo(userInfo.name(), userInfo.email(), userInfo.sub());
    }

    private GoogleTokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", properties.clientId());
        params.add("client_secret", properties.clientSecret());
        params.add("redirect_uri", properties.redirectUri());
        params.add("grant_type", "authorization_code");

        GoogleTokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (HttpClientErrorException e) {
            throw rejected("토큰 교환", e);
        } catch (RestClientException e) {
            throw unavailable("토큰 교환", e);
        }

        if (response == null || response.accessToken() == null) {
            throw invalidResponse("토큰 교환");
        }
        return response;
    }

    private GoogleUserInfoResponse getUserInfo(String accessToken) {
        GoogleUserInfoResponse response;
        try {
            response = restClient.get()
                    .uri(properties.userinfoUrl())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);
        } catch (HttpClientErrorException e) {
            throw rejected("사용자 정보 조회", e);
        } catch (RestClientException e) {
            throw unavailable("사용자 정보 조회", e);
        }

        // email 과 sub 는 가입·로그인 식별에 쓰이므로 없으면 진행할 수 없다. scope 에 email 을 요청했는데 빠졌다면 제공자 쪽 이상이다.
        if (response == null || response.email() == null || response.sub() == null) {
            throw invalidResponse("사용자 정보 조회");
        }
        return response;
    }

    // 4xx: 잘못되거나 이미 사용된 인가 코드처럼 요청 자체가 거절된 경우다. 사용자 쪽 원인이라 스택트레이스 없이 남긴다.
    private BusinessException rejected(String step, HttpClientErrorException e) {
        log.warn("구글 {} 거절: status={}", step, e.getStatusCode());
        return new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
    }

    // 5xx, 타임아웃, 연결 실패, 역직렬화 실패: 외부 장애로 보고 원인 추적을 위해 스택트레이스를 남긴다.
    private BusinessException unavailable(String step, RestClientException e) {
        log.error("구글 {} 통신 실패", step, e);
        return new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }

    private BusinessException invalidResponse(String step) {
        log.error("구글 {} 응답에 필수 값이 없습니다", step);
        return new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
    }
}
