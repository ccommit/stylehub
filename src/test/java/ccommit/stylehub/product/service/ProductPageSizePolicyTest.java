package ccommit.stylehub.product.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 상품 목록 페이지 크기 정규화 정책의 단위 테스트이다.
 * 정규화 결과가 캐시 키와 캐시 조건에 그대로 쓰이므로, 결과가 같아야 하는 입력이 같은 값으로 모이는지 고정한다.
 * </p>
 */
class ProductPageSizePolicyTest {

    @Test
    @DisplayName("페이지 크기를 생략하면 기본값 20 을 쓴다")
    void returnsDefault_whenNull() {
        assertThat(ProductPageSizePolicy.resolve(null)).isEqualTo(ProductPageSizePolicy.DEFAULT_PAGE_SIZE);
    }

    @ParameterizedTest(name = "[{index}] 요청 {0} → {1}")
    @DisplayName("1 미만은 기본값, 1~100 은 그대로, 100 초과는 최대값 100 으로 맞춘다")
    @CsvSource({
            "-5, 20",
            "0, 20",
            "1, 1",
            "20, 20",
            "100, 100",
            "101, 100",
            "999, 100",
            "2147483647, 100"
    })
    void clampsToAllowedRange(int requested, int expected) {
        assertThat(ProductPageSizePolicy.resolve(requested)).isEqualTo(expected);
    }
}
