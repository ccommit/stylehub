package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.ProductOptionRequest;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * ProductApplicationService의 오케스트레이션 로직을 검증하는 단위테스트이다.
 * UserPort(권한 검증/조회)와 ProductService(도메인 로직)를 각각 Mock으로 대체해
 * "어떤 순서로, 어떤 인자로 협력 객체를 호출하는지"에 집중한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProductApplicationServiceTest {

    @Mock
    private UserPort userPort;

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductApplicationService productApplicationService;

    @Nested
    @DisplayName("registerProduct")
    class RegisterProduct {

        @Test
        @DisplayName("승인된 스토어 소유자를 조회한 뒤 상품 등록을 위임한다")
        void 소유자를_조회하고_등록을_위임한다() {
            // given
            Long userId = 1L;
            Long storeId = 10L;
            User owner = User.builder().userId(storeId).storeName("스토어").build();
            ProductCreateRequest request = new ProductCreateRequest(
                    "상품", MainCategory.TOP, SubCategory.T_SHIRT, "설명", 1000, "http://image",
                    List.of(new ProductOptionRequest("black", "M", 10, 100)));
            ProductResponse expected = ProductResponse.builder().productId(100L).build();

            when(userPort.findApprovedStoreByOwner(userId, storeId)).thenReturn(owner);
            when(productService.registerProduct(owner, request)).thenReturn(expected);

            // when
            ProductResponse response = productApplicationService.registerProduct(userId, storeId, request);

            // then
            assertThat(response).isEqualTo(expected);
            verify(userPort).findApprovedStoreByOwner(userId, storeId);
            verify(productService).registerProduct(owner, request);
        }

        @Test
        @DisplayName("승인된 스토어 소유자가 아니면 예외가 전파되고 상품 등록은 시도하지 않는다")
        void 소유자가_아니면_예외가_전파된다() {
            // given
            Long userId = 1L;
            Long storeId = 10L;
            ProductCreateRequest request = new ProductCreateRequest(
                    "상품", MainCategory.TOP, SubCategory.T_SHIRT, "설명", 1000, "http://image",
                    List.of(new ProductOptionRequest("black", "M", 10, 100)));

            when(userPort.findApprovedStoreByOwner(userId, storeId))
                    .thenThrow(new BusinessException(ErrorCode.STORE_NOT_APPROVED));

            // when & then
            assertThatThrownBy(() -> productApplicationService.registerProduct(userId, storeId, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_APPROVED);
            verify(productService, never()).registerProduct(any(), any());
        }
    }

    @Nested
    @DisplayName("getMyStoreProducts")
    class GetMyStoreProducts {

        @Test
        @DisplayName("소유권을 검증한 뒤 내 스토어 상품 목록 조회를 위임한다")
        void 소유권_검증_후_조회를_위임한다() {
            // given
            Long userId = 1L;
            Long storeId = 10L;
            CursorResponse<ProductListResponse> expected =
                    CursorResponse.<ProductListResponse>builder().items(List.of()).hasNext(false).build();
            when(productService.getMyStoreProducts(storeId, 5L, 20)).thenReturn(expected);

            // when
            CursorResponse<ProductListResponse> response =
                    productApplicationService.getMyStoreProducts(userId, storeId, 5L, 20);

            // then
            assertThat(response).isEqualTo(expected);
            verify(userPort).validateApprovedStoreOwner(userId, storeId);
            verify(productService).getMyStoreProducts(storeId, 5L, 20);
        }

        @Test
        @DisplayName("소유권 검증에 실패하면 예외가 전파되고 조회를 시도하지 않는다")
        void 소유권_검증_실패시_예외가_전파된다() {
            // given
            Long userId = 1L;
            Long storeId = 10L;
            doThrow(new BusinessException(ErrorCode.UNAUTHORIZED_STORE_ACCESS))
                    .when(userPort).validateApprovedStoreOwner(userId, storeId);

            // when & then
            assertThatThrownBy(() -> productApplicationService.getMyStoreProducts(userId, storeId, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_STORE_ACCESS);
            verify(productService, never()).getMyStoreProducts(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("updateStock")
    class UpdateStock {

        @Test
        @DisplayName("소유권을 검증한 뒤 재고 변경을 위임한다")
        void 소유권_검증_후_재고변경을_위임한다() {
            // given
            Long userId = 1L;
            Long storeId = 10L;
            Long optionId = 100L;
            ProductOptionResponse expected = ProductOptionResponse.builder()
                    .productOptionId(optionId).stockQuantity(5).build();
            when(productService.updateStock(optionId, 5)).thenReturn(expected);

            // when
            ProductOptionResponse response =
                    productApplicationService.updateStock(userId, storeId, optionId, 5);

            // then
            assertThat(response).isEqualTo(expected);
            verify(userPort).validateApprovedStoreOwner(userId, storeId);
            verify(productService).updateStock(optionId, 5);
        }

        @Test
        @DisplayName("소유권 검증에 실패하면 재고 변경을 시도하지 않는다")
        void 소유권_검증_실패시_재고변경을_시도하지_않는다() {
            // given
            Long userId = 1L;
            Long storeId = 10L;
            doThrow(new BusinessException(ErrorCode.UNAUTHORIZED_STORE_ACCESS))
                    .when(userPort).validateApprovedStoreOwner(userId, storeId);

            // when & then
            assertThatThrownBy(() -> productApplicationService.updateStock(userId, storeId, 100L, 5))
                    .isInstanceOf(BusinessException.class);
            verify(productService, never()).updateStock(any(), any());
        }
    }

    @Nested
    @DisplayName("공개 조회 API (getProducts, getProduct)")
    class PublicRead {

        @Test
        @DisplayName("getProducts는 인증/소유권 검증 없이 바로 조회를 위임한다")
        void getProducts는_소유권_검증을_하지_않는다() {
            // given
            CursorResponse<ProductListResponse> expected =
                    CursorResponse.<ProductListResponse>builder().items(List.of()).hasNext(false).build();
            when(productService.getProducts(null, null, null, null, null)).thenReturn(expected);

            // when
            CursorResponse<ProductListResponse> response =
                    productApplicationService.getProducts(null, null, null, null, null);

            // then
            assertThat(response).isEqualTo(expected);
            verifyNoInteractions(userPort);
        }

        @Test
        @DisplayName("getProduct는 인증/소유권 검증 없이 바로 상세 조회를 위임한다")
        void getProduct는_소유권_검증을_하지_않는다() {
            // given
            ProductResponse expected = ProductResponse.builder().productId(1L).build();
            when(productService.getProduct(1L)).thenReturn(expected);

            // when
            ProductResponse response = productApplicationService.getProduct(1L);

            // then
            assertThat(response).isEqualTo(expected);
            verifyNoInteractions(userPort);
        }
    }
}
