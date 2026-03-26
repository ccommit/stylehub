package ccommit.stylehub.store.dto.response;

import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.enums.StoreStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 스토어 정보를 클라이언트에 반환하는 응답 DTO이다.
 * </p>
 */
@Builder
public record StoreResponse(
        Long storeId,
        String name,
        String description,
        StoreStatus status,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
    public static StoreResponse from(Store store) {
        return StoreResponse.builder()
                .storeId(store.getStoreId())
                .name(store.getName())
                .description(store.getDescription())
                .status(store.getStatus())
                .approvedAt(store.getApprovedAt())
                .createdAt(store.getCreatedAt())
                .build();
    }
}
