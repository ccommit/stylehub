package ccommit.stylehub.product.dto.request;

import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 상품 등록 시 상품 정보와 옵션 리스트를 전달하는 요청 DTO이다.
 * </p>
 */
public record ProductCreateRequest(

        @NotBlank(message = "상품명은 필수입니다")
        @Size(max = 20, message = "상품명은 20자 이내여야 합니다")
        String name,

        @NotNull(message = "대분류는 필수입니다")
        MainCategory mainCategory,

        @NotNull(message = "소분류는 필수입니다")
        SubCategory subCategory,

        @NotBlank(message = "상품 설명은 필수입니다")
        String description,

        @NotNull(message = "가격은 필수입니다")
        @Positive(message = "가격은 0보다 커야 합니다")
        Integer price,

        @NotBlank(message = "이미지 URL은 필수입니다")
        @Size(max = 300, message = "이미지 URL은 300자 이내여야 합니다")
        String imageUrl,

        @NotEmpty(message = "옵션은 최소 1개 이상이어야 합니다")
        @Valid
        List<ProductOptionRequest> options
) {}
