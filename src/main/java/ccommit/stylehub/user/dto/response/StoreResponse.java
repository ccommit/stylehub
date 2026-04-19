package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/04/19 by WonJin - refactor: Store 테이블 제거, User에서 스토어 정보 추출
 *
 * <p>
 * 스토어 정보를 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record StoreResponse(
        Long userId,
        String name,
        String description,
        StoreStatus status,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
    public static StoreResponse from(User user) {
        return StoreResponse.builder()
                .userId(user.getUserId())
                .name(user.getStoreName())
                .description(user.getStoreDescription())
                .status(user.getStoreStatus())
                .approvedAt(user.getApprovedAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
