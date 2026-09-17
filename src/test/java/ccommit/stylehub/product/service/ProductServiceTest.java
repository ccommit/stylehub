package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.response.ProductListResponse;
import ccommit.stylehub.product.dto.response.ProductOptionResponse;
import ccommit.stylehub.product.dto.response.ProductResponse;
import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.product.repository.ProductQueryRepository;
import ccommit.stylehub.product.repository.ProductRepository;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 * @modified 2026/09/17 by WonJin - test: 재고 변경 옵션 소속 검증(IDOR), 스토어 승인 조건 재고 차감의 실패 원인 구분, 상세 조회 승인 상태 조건 테스트 추가
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

    @Mock
    private ProductOptionRepository productOptionRepository;

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
            ProductListResponse dto = ProductListResponse.from(product);
            given(productQueryRepository.findProductsWithCursor(null, null, null, null, 21))
                    .willReturn(List.of(dto));

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
            ProductListResponse dto1 = ProductListResponse.from(r1);
            ProductListResponse dto2 = ProductListResponse.from(r2);
            ProductListResponse dto3 = ProductListResponse.from(r3);
            given(productQueryRepository.findProductsWithCursor(null, null, null, null, 3))
                    .willReturn(List.of(dto1, dto2, dto3));

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
            given(productRepository.findByIdWithUserAndOptions(productId, StoreStatus.APPROVED))
                    .willReturn(Optional.of(product));

            // when
            ProductResponse response = productService.getProduct(productId);

            // then — 승인 스토어 조건으로 조회해야 정지 스토어 상품이 상세에 노출되지 않는다
            assertThat(response.productId()).isEqualTo(productId);
            assertThat(response.storeId()).isEqualTo(10L);
            then(productRepository).should().findByIdWithUserAndOptions(productId, StoreStatus.APPROVED);
        }

        @Test
        @DisplayName("존재하지 않거나 승인 스토어의 상품이 아니면 PRODUCT_NOT_FOUND 예외를 던진다")
        void throwsNotFound_whenProductMissing() {
            // given
            Long productId = 999L;
            given(productRepository.findByIdWithUserAndOptions(productId, StoreStatus.APPROVED))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> productService.getProduct(productId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("updateStock (스토어 재고 수동 변경)")
    class UpdateStock {

        private static final Long STORE_ID = 10L;
        private static final Long PRODUCT_ID = 1L;
        private static final Long OPTION_ID = 100L;

        @Test
        @DisplayName("옵션이 요청한 상품·스토어에 속하면 재고를 변경한다")
        void updatesStock_whenOptionBelongsToProductAndStore() {
            // given
            ProductOption option = createOption(createMockProduct(PRODUCT_ID), OPTION_ID, 10);
            given(productOptionRepository.findByIdWithLock(OPTION_ID)).willReturn(Optional.of(option));

            // when
            ProductOptionResponse response = productService.updateStock(STORE_ID, PRODUCT_ID, OPTION_ID, 3);

            // then
            assertThat(response.stockQuantity()).isEqualTo(3);
            assertThat(option.getStockQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("다른 스토어의 옵션이면 존재 여부를 숨기기 위해 PRODUCT_OPTION_NOT_FOUND 를 던지고 재고를 바꾸지 않는다")
        void throwsNotFound_whenOptionBelongsToOtherStore() {
            // given — 옵션의 실제 소유 스토어는 10L, 요청은 20L
            ProductOption option = createOption(createMockProduct(PRODUCT_ID), OPTION_ID, 10);
            given(productOptionRepository.findByIdWithLock(OPTION_ID)).willReturn(Optional.of(option));

            // when / then
            assertThatThrownBy(() -> productService.updateStock(20L, PRODUCT_ID, OPTION_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_OPTION_NOT_FOUND);
            assertThat(option.getStockQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("옵션이 경로의 상품에 속하지 않으면 PRODUCT_OPTION_NOT_FOUND 를 던지고 재고를 바꾸지 않는다")
        void throwsNotFound_whenOptionBelongsToOtherProduct() {
            // given — 옵션의 실제 상품은 1L, 요청은 2L
            ProductOption option = createOption(createMockProduct(PRODUCT_ID), OPTION_ID, 10);
            given(productOptionRepository.findByIdWithLock(OPTION_ID)).willReturn(Optional.of(option));

            // when / then
            assertThatThrownBy(() -> productService.updateStock(STORE_ID, 2L, OPTION_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_OPTION_NOT_FOUND);
            assertThat(option.getStockQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("옵션이 없으면 PRODUCT_OPTION_NOT_FOUND 를 던진다")
        void throwsNotFound_whenOptionMissing() {
            // given
            given(productOptionRepository.findByIdWithLock(OPTION_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> productService.updateStock(STORE_ID, PRODUCT_ID, OPTION_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_OPTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("decreaseStockWithLock (주문 재고 차감)")
    class DecreaseStock {

        private static final Long OPTION_ID = 100L;

        @Test
        @DisplayName("승인 스토어 조건의 원자 UPDATE 가 1건이면 옵션을 반환하고 원인 조회는 하지 않는다")
        void returnsOption_whenDecreased() {
            // given
            ProductOption option = createOption(createMockProduct(1L), OPTION_ID, 9);
            given(productOptionRepository.decreaseStockAtomic(OPTION_ID, 1, StoreStatus.APPROVED)).willReturn(1);
            given(productOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(option));

            // when
            ProductOption result = productService.decreaseStockWithLock(OPTION_ID, 1);

            // then
            assertThat(result).isSameAs(option);
            then(productOptionRepository).should(never()).findByIdWithProductAndStore(anyLong());
        }

        @Test
        @DisplayName("0건이고 옵션이 없으면 PRODUCT_OPTION_NOT_FOUND 를 던진다")
        void throwsOptionNotFound_whenOptionMissing() {
            // given
            given(productOptionRepository.decreaseStockAtomic(OPTION_ID, 1, StoreStatus.APPROVED)).willReturn(0);
            given(productOptionRepository.findByIdWithProductAndStore(OPTION_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> productService.decreaseStockWithLock(OPTION_ID, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_OPTION_NOT_FOUND);
        }

        @Test
        @DisplayName("0건이고 스토어가 승인 상태가 아니면 재고와 무관하게 PRODUCT_NOT_ON_SALE 을 던진다")
        void throwsNotOnSale_whenStoreNotApproved() {
            // given — 재고는 충분하지만 스토어가 정지됨
            Product product = createMockProduct(1L);
            given(product.isOnSale()).willReturn(false);
            ProductOption option = createOption(product, OPTION_ID, 10);
            given(productOptionRepository.decreaseStockAtomic(OPTION_ID, 1, StoreStatus.APPROVED)).willReturn(0);
            given(productOptionRepository.findByIdWithProductAndStore(OPTION_ID)).willReturn(Optional.of(option));

            // when / then
            assertThatThrownBy(() -> productService.decreaseStockWithLock(OPTION_ID, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_ON_SALE);
        }

        @Test
        @DisplayName("0건이고 옵션이 있으며 스토어가 승인 상태면 INSUFFICIENT_STOCK 을 던진다")
        void throwsInsufficientStock_whenStoreApproved() {
            // given
            Product product = createMockProduct(1L);
            given(product.isOnSale()).willReturn(true);
            ProductOption option = createOption(product, OPTION_ID, 0);
            given(productOptionRepository.decreaseStockAtomic(OPTION_ID, 1, StoreStatus.APPROVED)).willReturn(0);
            given(productOptionRepository.findByIdWithProductAndStore(OPTION_ID)).willReturn(Optional.of(option));

            // when / then
            assertThatThrownBy(() -> productService.decreaseStockWithLock(OPTION_ID, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK);
            then(productOptionRepository).should(never()).findById(anyLong());
        }
    }

    // ===== Helper =====

    private ProductOption createOption(Product product, Long optionId, int stock) {
        return ProductOption.builder()
                .productOptionId(optionId)
                .product(product)
                .color("black")
                .size("M")
                .stockQuantity(stock)
                .build();
    }

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
