package ccommit.stylehub.user.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/16 18:16 by WonJin - feat: 회원 API 개발 (회원가입, 로그인, 구글 OAuth, 포인트 지급)
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 사용자 권한(USER/STORE/ADMIN)을 정의한다.
 * </p>
 */

@Getter
public enum Role {

    USER,  // 일반 사용자
    STORE,  // 스토어 관리자
    ADMIN  // 플랫폼 관리자
}
