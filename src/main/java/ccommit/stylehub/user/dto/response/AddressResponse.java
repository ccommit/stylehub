package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.entity.Address;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 배송지 정보를 클라이언트에 반환하는 응답 DTO이다.
 * 소유자 정보는 세션 사용자 자신이므로 담지 않는다.
 * </p>
 */
@Builder
public record AddressResponse(
        Long addressId,
        String label,
        String recipientName,
        String phone,
        String zipCode,
        String streetAddress,
        String detailAddress,
        boolean isDefault,
        LocalDateTime createdAt
) {
    public static AddressResponse from(Address address) {
        return AddressResponse.builder()
                .addressId(address.getAddressId())
                .label(address.getLabel())
                .recipientName(address.getRecipientName())
                .phone(address.getPhone())
                .zipCode(address.getZipCode())
                .streetAddress(address.getStreetAddress())
                .detailAddress(address.getDetailAddress())
                .isDefault(address.isDefault())
                .createdAt(address.getCreatedAt())
                .build();
    }
}
