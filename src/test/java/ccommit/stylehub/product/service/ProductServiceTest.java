package ccommit.stylehub.product.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.product.dto.request.ProductCreateRequest;
import ccommit.stylehub.product.dto.request.ProductOptionRequest;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * ProductService의 상품 등록, 재고 증감, 커서 기반 조회 로직을 검증하는 단위테스트이다.
 * Repository는 전부 Mock으로 대체해 DB 없이 도메인 서비스의 분기와 예외를 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private ProductQueryRepository productQueryRepository;

    @InjectMocks
    private ProductService productService;

    private User store(long storeId, String storeName) {
        return User.builder()
                .userId(storeId)
                .storeName(storeName)
                .build();
    }

    private Product product(long productId, User owner, String name, int price) {
        Product product = Product.builder()
                .user(owner)
                .name(name)
                .mainCategory(MainCategory.TOP)
                .subCategory(SubCategory.T_SHIRT)
                .description("설명")
                .price(price)
                .imageUrl("http://image")
                .build();
        ReflectionTestUtils.setField(product, "productId", productId);
        return product;
    }

    @Nested
    @DisplayName("registerProduct")
    class RegisterProduct {

        @Test
        @DisplayName("카테고리 조합이 유효하면 상품과 옵션을 저장하고 응답을 반환한다")
        void 상품과_옵션을_저장하고_응답을_반환한다() {
            // given
            User owner = store(10L, "무신사 스토어");
            ProductOptionRequest optionRequest = new ProductOptionRequest("black", "M", 10, 500);
            ProductCreateRequest request = new ProductCreateRequest(
                    "맨투맨", MainCategory.TOP, SubCategory.SWEATSHIRT, "설명", 39000, "http://image",
                    List.of(optionRequest));

            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
                Product saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "productId", 100L);
                return saved;
            });
            when(productOptionRepository.saveAll(anyList())).thenAnswer(invocation -> {
                List<ProductOption> options = invocation.getArgument(0);
                long id = 1L;
                for (ProductOption option : options) {
                    ReflectionTestUtils.setField(option, "productOptionId", id++);
                }
                return options;
            });

            // when
            ProductResponse response = productService.registerProduct(owner, request);

            // then
            assertThat(response.productId()).isEqualTo(100L);
            assertThat(response.storeId()).isEqualTo(10L);
            assertThat(response.storeName()).isEqualTo("무신사 스토어");
            assertThat(response.name()).isEqualTo("맨투맨");
            assertThat(response.options()).hasSize(1);
            assertThat(response.options().get(0).productOptionId()).isEqualTo(1L);
            assertThat(response.options().get(0).stockQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("메인/서브 카테고리 조합이 일치하지 않으면 저장 없이 예외가 발생한다")
        void 카테고리_조합이_불일치하면_예외가_발생한다() {
            // given
            User owner = store(10L, "무신사 스토어");
            ProductOptionRequest optionRequest = new ProductOptionRequest("black", "M", 10, 500);
            // TOP 대분류에 SHOES 전용 소분류(SNEAKERS)를 조합 — 불일치
            ProductCreateRequest request = new ProductCreateRequest(
                    "잘못된 상품", MainCategory.TOP, SubCategory.SNEAKERS, "설명", 10000, "http://image",
                    List.of(optionRequest));

            // when & then
            assertThatThrownBy(() -> productService.registerProduct(owner, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CATEGORY_COMBINATION);

            verify(productRepository, never()).save(any());
            verify(productOptionRepository, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("getMyStoreProducts")
    class GetMyStoreProducts {

        private List<Product> products(int count, User owner) {
            List<Product> result = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                result.add(product(i, owner, "상품" + i, 1000 * i));
            }
            return result;
        }

        @Test
        @DisplayName("pageSize를 지정하지 않으면 기본 페이지 크기(20)로 조회하고, 결과가 그 이하면 hasNext는 false다")
        void pageSize가_null이면_기본값으로_조회한다() {
            // given
            User owner = store(5L, "스토어");
            when(productQueryRepository.findProductsWithCursor(isNull(), eq(5L), eq(21)))
                    .thenReturn(products(20, owner));

            // when
            CursorResponse<ProductListResponse> result = productService.getMyStoreProducts(5L, null, null);

            // then
            assertThat(result.items()).hasSize(20);
            assertThat(result.hasNext()).isFalse();
            assertThat(result.nextCursor()).isNull();
            verify(productQueryRepository).findProductsWithCursor(isNull(), eq(5L), eq(21));
        }

        @Test
        @DisplayName("요청한 크기보다 1건 더 조회되면 hasNext가 true이고 다음 커서가 채워진다")
        void 다음_페이지가_있으면_hasNext가_true다() {
            // given
            User owner = store(5L, "스토어");
            when(productQueryRepository.findProductsWithCursor(isNull(), eq(5L), eq(21)))
                    .thenReturn(products(21, owner));

            // when
            CursorResponse<ProductListResponse> result = productService.getMyStoreProducts(5L, null, null);

            // then
            assertThat(result.items()).hasSize(20);
            assertThat(result.hasNext()).isTrue();
            assertThat(result.nextCursor()).isEqualTo(20L);
        }

        @Test
        @DisplayName("pageSize가 최대값(100)을 초과하면 100으로 제한된다")
        void pageSize가_최댓값을_초과하면_제한된다() {
            // given
            User owner = store(5L, "스토어");
            when(productQueryRepository.findProductsWithCursor(isNull(), eq(5L), eq(101)))
                    .thenReturn(products(100, owner));

            // when
            productService.getMyStoreProducts(5L, null, 500);

            // then
            verify(productQueryRepository).findProductsWithCursor(isNull(), eq(5L), eq(101));
        }

        @ParameterizedTest
        @DisplayName("pageSize가 0 이하면 기본값(20)이 적용된다")
        @ValueSource(ints = {0, -5})
        void pageSize가_0이하이면_기본값이_적용된다(int invalidPageSize) {
            // given
            User owner = store(5L, "스토어");
            when(productQueryRepository.findProductsWithCursor(isNull(), eq(5L), eq(21)))
                    .thenReturn(products(1, owner));

            // when
            productService.getMyStoreProducts(5L, null, invalidPageSize);

            // then
            verify(productQueryRepository).findProductsWithCursor(isNull(), eq(5L), eq(21));
        }
    }

    @Nested
    @DisplayName("getProducts (공개 목록 조회)")
    class GetProducts {

        @Test
        @DisplayName("커서, 스토어, 카테고리 필터를 그대로 전달해 조회한다")
        void 필터를_그대로_전달한다() {
            // given
            when(productQueryRepository.findProductsWithCursor(
                    eq(10L), eq(5L), eq(MainCategory.TOP), eq(SubCategory.T_SHIRT), eq(11)))
                    .thenReturn(List.of());

            // when
            productService.getProducts(10L, 5L, MainCategory.TOP, SubCategory.T_SHIRT, 10);

            // then
            verify(productQueryRepository).findProductsWithCursor(
                    eq(10L), eq(5L), eq(MainCategory.TOP), eq(SubCategory.T_SHIRT), eq(11));
        }
    }

    @Nested
    @DisplayName("updateStock")
    class UpdateStock {

        @Test
        @DisplayName("옵션이 존재하면 재고를 지정한 값으로 변경한다")
        void 재고를_변경한다() {
            // given
            User owner = store(1L, "스토어");
            Product product = product(1L, owner, "상품", 1000);
            ProductOption option = ProductOption.create(product, "black", "M", 10, 100);
            ReflectionTestUtils.setField(option, "productOptionId", 1L);
            when(productOptionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when
            ProductOptionResponse response = productService.updateStock(1L, 50);

            // then
            assertThat(response.stockQuantity()).isEqualTo(50);
            assertThat(option.getStockQuantity()).isEqualTo(50);
        }

        @Test
        @DisplayName("옵션이 존재하지 않으면 PRODUCT_OPTION_NOT_FOUND 예외가 발생한다")
        void 옵션이_없으면_예외가_발생한다() {
            // given
            when(productOptionRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.updateStock(999L, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_OPTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("decreaseStockWithLock")
    class DecreaseStockWithLock {

        @Test
        @DisplayName("재고가 충분하면 차감된 옵션을 반환한다")
        void 재고를_차감한다() {
            // given
            User owner = store(1L, "스토어");
            Product product = product(1L, owner, "상품", 1000);
            ProductOption option = ProductOption.create(product, "black", "M", 10, 100);
            when(productOptionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when
            ProductOption result = productService.decreaseStockWithLock(1L, 3);

            // then
            assertThat(result.getStockQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("재고보다 많은 수량을 요청하면 INSUFFICIENT_STOCK 예외가 발생한다")
        void 재고가_부족하면_예외가_발생한다() {
            // given
            User owner = store(1L, "스토어");
            Product product = product(1L, owner, "상품", 1000);
            ProductOption option = ProductOption.create(product, "black", "M", 2, 100);
            when(productOptionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when & then
            assertThatThrownBy(() -> productService.decreaseStockWithLock(1L, 5))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("옵션이 존재하지 않으면 PRODUCT_OPTION_NOT_FOUND 예외가 발생한다")
        void 옵션이_없으면_예외가_발생한다() {
            // given
            when(productOptionRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.decreaseStockWithLock(999L, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_OPTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("increaseStock")
    class IncreaseStock {

        @Test
        @DisplayName("재고를 요청 수량만큼 복구한다")
        void 재고를_복구한다() {
            // given
            User owner = store(1L, "스토어");
            Product product = product(1L, owner, "상품", 1000);
            ProductOption option = ProductOption.create(product, "black", "M", 5, 100);
            when(productOptionRepository.findByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when
            productService.increaseStock(1L, 10);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(15);
        }

        @Test
        @DisplayName("옵션이 존재하지 않으면 PRODUCT_OPTION_NOT_FOUND 예외가 발생한다")
        void 옵션이_없으면_예외가_발생한다() {
            // given
            when(productOptionRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.increaseStock(999L, 1))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_OPTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getProduct")
    class GetProduct {

        @Test
        @DisplayName("상품이 존재하면 옵션 목록과 함께 상세 정보를 반환한다")
        void 상품_상세를_반환한다() {
            // given
            User owner = store(1L, "스토어");
            Product product = product(1L, owner, "상품", 1000);
            ProductOption option = ProductOption.create(product, "black", "M", 5, 100);
            product.getOptions().add(option);
            when(productRepository.findByIdWithUserAndOptions(1L)).thenReturn(Optional.of(product));

            // when
            ProductResponse response = productService.getProduct(1L);

            // then
            assertThat(response.productId()).isEqualTo(1L);
            assertThat(response.options()).hasSize(1);
        }

        @Test
        @DisplayName("상품이 존재하지 않으면 PRODUCT_NOT_FOUND 예외가 발생한다")
        void 상품이_없으면_예외가_발생한다() {
            // given
            when(productRepository.findByIdWithUserAndOptions(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.getProduct(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }
}
