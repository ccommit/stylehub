package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.GoogleOAuthProperties;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.enums.OAuthProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

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
    public String getAuthorizationUrl() {
        return UriComponentsBuilder.fromUriString(properties.authUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "email profile")
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

        try {
            return restClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientException e) {
            //TODO : 글로벌 예외 도입후 커스텀 예외로 전환 예정
            throw new IllegalStateException("구글 인증 서버와 통신에 실패했습니다", e);
        }
    }

    private GoogleUserInfoResponse getUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri(properties.userinfoUrl())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("구글 사용자 정보 조회에 실패했습니다", e);
        }
    }
}
