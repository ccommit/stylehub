package ccommit.stylehub.user.dto.request;

import static ccommit.stylehub.common.constants.ValidationPatterns.PHONE_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.PHONE_PATTERN;
import static ccommit.stylehub.common.constants.ValidationPatterns.ZIP_CODE_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.ZIP_CODE_PATTERN;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 배송지 등록 요청 데이터를 담는 불변 DTO이다.
 * 길이 상한은 addresses 컬럼 길이를 넘지 않게 맞춰, 스키마를 바꾸지 않고도 DB 오류 대신 400 으로 거절한다.
 * </p>
 */
public record AddressCreateRequest(

        @NotBlank(message = "배송지 이름은 필수입니다")
        @Size(max = 20, message = "배송지 이름은 20자 이하여야 합니다")
        String label,

        @NotBlank(message = "수취인 이름은 필수입니다")
        @Size(min = 2, max = 20, message = "수취인 이름은 2자 이상 20자 이하여야 합니다")
        String recipientName,

        // 하이픈 없이 숫자만 받는다 (예: 01012345678)
        @NotBlank(message = "연락처는 필수입니다")
        @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE)
        String phone,

        @NotBlank(message = "우편번호는 필수입니다")
        @Pattern(regexp = ZIP_CODE_PATTERN, message = ZIP_CODE_MESSAGE)
        String zipCode,

        @NotBlank(message = "기본 주소는 필수입니다")
        @Size(max = 40, message = "기본 주소는 40자 이하여야 합니다")
        String streetAddress,

        // 기획서는 100자지만 detail_address 컬럼이 VARCHAR(40)이다. 스키마 변경 없이 저장 가능한 범위로 제한한다
        @Size(max = 40, message = "상세 주소는 40자 이하여야 합니다")
        String detailAddress
) {
}
