package ccommit.stylehub.user.event;

import ccommit.stylehub.user.enums.UserRole;

import java.time.LocalDate;

/**
 * @author WonJin Bae
 * @created 2026/03/21
 * @modified 2026/03/25 by WonJin - feat: role 필드 추가 (USER만 포인트 지급)
 *
 * <p>
 * 로그인 성공 시 발행되는 이벤트이다.
 * user 도메인이 point 도메인에 직접 의존하지 않도록 이벤트로 분리한다.
 * </p>
 */
public record LoginEvent(
        Long userId,
        LocalDate loginDate,
        UserRole role
) {
}
