package ccommit.stylehub.store.dto.response;

import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 스토어 회원가입 + 입점 신청 결과를 클라이언트에 반환하는 응답 DTO이다.
 * 회원 정보와 스토어 정보를 함께 전달한다.
 * </p>
 */
@Builder
public record StoreSignUpResponse(
        Long userId,
        String name,
        String email,
        UserRole role,
        Long storeId,
        String storeName,
        StoreStatus storeStatus
) {
    public static StoreSignUpResponse from(User user, Store store) {
        return StoreSignUpResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .storeId(store.getStoreId())
                .storeName(store.getName())
                .storeStatus(store.getStatus())
                .build();
    }
}
