package ccommit.stylehub.user.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/16 18:16 by WonJin - feat: 회원 API 개발 (회원가입, 로그인, 구글 OAuth, 포인트 지급)
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 지원하는 OAuth 인증 제공자를 정의한다.
 * 새 제공자 추가 시 OAuthClient 구현체만 만들면 자동 등록된다.
 * </p>
 */

@Getter
public enum Provider {
    GOOGLE  // 구글

}
