package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.repository.ProductQueryRepository;
import ccommit.stylehub.product.repository.ProductRepository;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 *
 * <p>
 * ProductService 의 단위 테스트이다.
 * FIRST 원칙에 따라 Mock 만 사용해 DB·네트워크 의존 없이 빠르고 독립적으로 검증한다.
 * BDD 스타일(given-when-then)로 작성하여 시나리오 가독성을 높인다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // 공용 setUp/헬퍼의 스텁이 일부 테스트에서만 쓰여도 허용
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductQueryRepository productQueryRepository;

    @InjectMocks
    private ProductService productService;

    private User storeUser;

    @BeforeEach
    void setUp() {
        storeUser = mock(User.class);
        given(storeUser.getUserId()).willReturn(10L);
        given(storeUser.getStoreName()).willReturn("테스트스토어");
    }

    @Nested
    @DisplayName("getProducts (목록 조회)")
    class GetProducts {

        @Test
        @DisplayName("필터 없이 호출하면 기본 페이지 크기만큼 조회 쿼리가 실행된다")
        void callsQueryWithDefaultPageSize_whenNoFilter() {
            // given
            Product product = createMockProduct(1L);
            given(productQueryRepository.findProductsWithCursor(null, null, null, null, 21))
                    .willReturn(List.of(product));

            // when
            CursorResponse<ProductListResponse> response =
                    productService.getProducts(null, null, null, null, null);

            // then
            assertThat(response.items()).hasSize(1);
            assertThat(response.hasNext()).isFalse();
            then(productQueryRepository).should()
                    .findProductsWithCursor(null, null, null, null, 21);
        }

        @Test
        @DisplayName("스토어·카테고리 필터를 함께 전달하면 쿼리에 그대로 반영된다")
        void appliesStoreAndCategoryFilters() {
            // given
            Long storeId = 10L;
            MainCategory mainCategory = MainCategory.TOP;
            SubCategory subCategory = SubCategory.T_SHIRT;
            given(productQueryRepository.findProductsWithCursor(null, storeId, mainCategory, subCategory, 21))
                    .willReturn(List.of());

            // when
            productService.getProducts(null, storeId, mainCategory, subCategory, null);

            // then
            then(productQueryRepository).should()
                    .findProductsWithCursor(null, storeId, mainCategory, subCategory, 21);
        }

        @Test
        @DisplayName("pageSize + 1건이 조회되면 hasNext 가 true 로 표시되고 nextCursor 가 설정된다")
        void setsHasNextAndCursor_whenExtraItemExists() {
            // given
            int pageSize = 2;
            Product r1 = createMockProduct(101L);
            Product r2 = createMockProduct(102L);
            Product r3 = createMockProduct(103L);   // +1 건 (hasNext 판정용)
            given(productQueryRepository.findProductsWithCursor(null, null, null, null, 3))
                    .willReturn(List.of(r1, r2, r3));

            // when
            CursorResponse<ProductListResponse> response =
                    productService.getProducts(null, null, null, null, pageSize);

            // then
            assertThat(response.items()).hasSize(pageSize);
            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextCursor()).isEqualTo(102L);
        }
    }

    @Nested
    @DisplayName("getProduct (단건 조회)")
    class GetProduct {

        @Test
        @DisplayName("존재하는 productId 면 ProductResponse 를 반환한다")
        void returnsProductResponse_whenProductExists() {
            // given
            Long productId = 1L;
            Product product = createMockProduct(productId);
            given(product.getOptions()).willReturn(List.of());
            given(productRepository.findByIdWithUserAndOptions(productId))
                    .willReturn(Optional.of(product));

            // when
            ProductResponse response = productService.getProduct(productId);

            // then
            assertThat(response.productId()).isEqualTo(productId);
            assertThat(response.storeId()).isEqualTo(10L);
            then(productRepository).should().findByIdWithUserAndOptions(productId);
        }

        @Test
        @DisplayName("존재하지 않는 productId 면 PRODUCT_NOT_FOUND 예외를 던진다")
        void throwsNotFound_whenProductMissing() {
            // given
            Long productId = 999L;
            given(productRepository.findByIdWithUserAndOptions(productId))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> productService.getProduct(productId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    // ===== Helper =====

    // ProductListResponse.from / ProductResponse.from 둘 다 Product 엔티티를 입력으로 받으므로 mock 한 개로 통합한다.
    private Product createMockProduct(Long productId) {
        Product product = mock(Product.class);
        given(product.getProductId()).willReturn(productId);
        given(product.getUser()).willReturn(storeUser);
        given(product.getName()).willReturn("상품-" + productId);
        given(product.getMainCategory()).willReturn(MainCategory.TOP);
        given(product.getSubCategory()).willReturn(SubCategory.T_SHIRT);
        given(product.getPrice()).willReturn(10000);
        given(product.getImageUrl()).willReturn("https://img/" + productId);
        return product;
    }
}
