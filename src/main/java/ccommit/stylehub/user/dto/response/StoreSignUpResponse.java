package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import ccommit.stylehub.user.enums.UserRole;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/04/19 by WonJin - refactor: Store 테이블 제거, User에서 스토어 정보 추출
 *
 * <p>
 * 스토어 회원가입 + 입점 신청 결과를 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record StoreSignUpResponse(
        Long userId,
        String name,
        String email,
        UserRole role,
        String storeName,
        StoreStatus storeStatus
) {
    public static StoreSignUpResponse from(User user) {
        return StoreSignUpResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .storeName(user.getStoreName())
                .storeStatus(user.getStoreStatus())
                .build();
    }
}
