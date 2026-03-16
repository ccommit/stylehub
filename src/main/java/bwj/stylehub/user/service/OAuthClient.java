package bwj.stylehub.user.service;

import bwj.stylehub.user.dto.response.OAuthUserInfo;
import bwj.stylehub.user.enums.Provider;

public interface OAuthClient {

    Provider provider();

    String getAuthorizationUrl();

    OAuthUserInfo authenticate(String code);
}
