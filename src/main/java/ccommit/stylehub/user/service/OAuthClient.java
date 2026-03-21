package ccommit.stylehub.user.service;

import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.enums.Provider;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * OAuth 제공자별 클라이언트의 공통 인터페이스이다.
 * </p>
 */

public interface OAuthClient {

    Provider provider();

    String getAuthorizationUrl();

    OAuthUserInfo authenticate(String code);
}
