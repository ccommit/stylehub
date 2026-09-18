package ccommit.stylehub.product.repository;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 옵션의 현재 재고와 소속 상품 ID 를 DB 에서 바로 읽은 값이다.
 * 엔티티가 아닌 DTO 로 조회해, 같은 트랜잭션의 영속성 컨텍스트에 남은 오래된 옵션 엔티티 값과 섞이지 않게 한다.
 * </p>
 */
public record OptionStock(Long productId, int stockQuantity) {
}
