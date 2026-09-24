package ccommit.stylehub.user.service;

import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.enums.OAuthProvider;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - fix: 인가 URL 에 state 를 싣도록 시그니처 변경, 구현체의 예외 규약 명시
 *
 * <p>
 * OAuth 제공자별 클라이언트의 공통 인터페이스이다.
 * </p>
 */

public interface OAuthClient {

    OAuthProvider provider();

    // state는 호출자가 세션에 저장한 값이며, 제공자가 콜백에 그대로 돌려준다
    String getAuthorizationUrl(String state);

    // 제공자가 코드를 거절하면 OAUTH_AUTHENTICATION_FAILED, 통신 실패나 비정상 응답이면 OAUTH_PROVIDER_UNAVAILABLE을 던진다
    OAuthUserInfo authenticate(String code);
}
