package ccommit.stylehub.product.port;

import ccommit.stylehub.product.entity.ProductOption;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 *
 * <p>
 * Product 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 재고 차감/복구를 제공한다.
 * </p>
 */
public interface ProductPort {

    ProductOption decreaseStockWithLock(Long optionId, int quantity);

    void increaseStock(Long optionId, int quantity);
}
