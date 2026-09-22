package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.enums.UserRole;

import java.time.LocalDate;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 로그인 포인트 적립 여부를 고르는 데 필요한 사용자 역할과 마지막 적립일이다.
 * 엔티티 대신 DTO 로 읽어 같은 트랜잭션의 영속성 컨텍스트에 남은 오래된 User 값과 섞이지 않게 한다.
 * </p>
 */
public record LoginPointState(UserRole role, LocalDate lastLoginDate) {
}
