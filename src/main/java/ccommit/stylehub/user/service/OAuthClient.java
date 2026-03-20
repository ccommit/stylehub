package ccommit.stylehub.user.service;

import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.enums.Provider;

public interface OAuthClient {

    Provider provider();

    String getAuthorizationUrl();

    OAuthUserInfo authenticate(String code);
}
