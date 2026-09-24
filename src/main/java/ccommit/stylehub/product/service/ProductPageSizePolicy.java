package ccommit.stylehub.product.service;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 상품 목록 API 의 페이지 크기 정책(기본 20, 최대 100)을 한 곳에서 정한다.
 * 정규화한 값을 조회 크기와 캐시 키·캐시 조건에 똑같이 써, 결과가 같은 요청이 서로 다른 캐시 키를 만들지 않게 한다.
 * </p>
 */
public final class ProductPageSizePolicy {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private ProductPageSizePolicy() {
    }

    // 없거나 1 미만이면 기본값, 최대값을 넘으면 최대값으로 맞춘다.
    public static int resolve(Integer requestedSize) {
        if (requestedSize == null || requestedSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }
}
