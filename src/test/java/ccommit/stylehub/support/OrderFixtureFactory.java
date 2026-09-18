package ccommit.stylehub.support;

import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.enums.MainCategory;
import ccommit.stylehub.product.enums.SubCategory;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.product.repository.ProductRepository;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * @author WonJin Bae
 * @created 2026/09/04
 * @modified 2026/09/17 by WonJin - test: 상품 소유자를 구매자와 분리된 승인 스토어로 생성 (재고 차감이 스토어 승인 상태를 요구하게 됨), 스토어·상품 단위 생성과 ID 기반 정리 메서드 추가
 *
 * <p>
 * 주문 통합 테스트가 존재를 가정해온 User/Address/Product/ProductOption을 실제로 만드는 테스트 전용 픽스처 팩토리이다.
 * 상품은 입점 승인된 스토어 소유로 만든다. 승인되지 않은 스토어의 상품은 조회·주문이 막히기 때문이다.
 * </p>
 */
@Component
public class OrderFixtureFactory {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final EntityManager entityManager;

    public OrderFixtureFactory(UserRepository userRepository,
                                ProductRepository productRepository,
                                ProductOptionRepository productOptionRepository,
                                EntityManager entityManager) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.productOptionRepository = productOptionRepository;
        this.entityManager = entityManager;
    }

    public record Fixture(Long userId, Long addressId, Long storeId, Long productId, Long optionId) {
    }

    public record Buyer(Long userId, Long addressId) {
    }

    public record StoreProduct(Long storeId, Long productId, Long optionId) {
    }

    // 구매자(배송지 포함)와 승인 스토어의 상품 옵션 1개를 만든다.
    @Transactional
    public Fixture create(int initialStock) {
        Buyer buyer = createBuyer();
        StoreProduct storeProduct = createStoreProduct(initialStock);
        return new Fixture(buyer.userId(), buyer.addressId(),
                storeProduct.storeId(), storeProduct.productId(), storeProduct.optionId());
    }

    @Transactional
    public Buyer createBuyer() {
        String suffix = newSuffix();

        User user = userRepository.save(User.create(
                "u" + suffix,
                "u" + suffix + "@test.com",
                "password",
                LocalDate.of(2000, 1, 1),
                UserRole.USER
        ));

        Address address = Address.builder()
                .user(user)
                .label("집")
                .recipientName("테스터")
                .phone("010-0000-0000")
                .zipCode("12345")
                .streetAddress("테스트로 1")
                .build();
        entityManager.persist(address);
        entityManager.flush();

        return new Buyer(user.getUserId(), address.getAddressId());
    }

    // 입점 승인된 스토어를 새로 만들고, 그 스토어의 상품과 옵션 1개를 만든다.
    @Transactional
    public StoreProduct createStoreProduct(int initialStock) {
        User store = createApprovedStore();
        return addProduct(store, initialStock);
    }

    // 기존 스토어에 상품과 옵션 1개를 추가한다.
    @Transactional
    public StoreProduct addProduct(Long storeId, int initialStock) {
        User store = userRepository.findById(storeId).orElseThrow();
        return addProduct(store, initialStock);
    }

    // deleteAll()은 같은 컨텍스트를 쓰는 다른 테스트 데이터까지 지우므로 ID로 범위를 한정해 외래키 의존 순서대로 지운다.
    @Transactional
    public void deleteByIds(Collection<Long> orderIds, Collection<Long> productIds, Collection<Long> userIds) {
        if (!orderIds.isEmpty()) {
            deleteIn("DELETE FROM Payment p WHERE p.order.orderId IN :ids", orderIds);
            deleteIn("DELETE FROM OrderDetail od WHERE od.order.orderId IN :ids", orderIds);
            deleteIn("DELETE FROM Order o WHERE o.orderId IN :ids", orderIds);
        }
        if (!productIds.isEmpty()) {
            deleteIn("DELETE FROM ProductOption po WHERE po.product.productId IN :ids", productIds);
            deleteIn("DELETE FROM Product p WHERE p.productId IN :ids", productIds);
        }
        if (!userIds.isEmpty()) {
            deleteIn("DELETE FROM Address a WHERE a.user.userId IN :ids", userIds);
            deleteIn("DELETE FROM User u WHERE u.userId IN :ids", userIds);
        }
    }

    private User createApprovedStore() {
        String suffix = newSuffix();

        User store = User.create(
                "s" + suffix,
                "s" + suffix + "@test.com",
                "password",
                LocalDate.of(2000, 1, 1),
                UserRole.STORE
        );
        store.registerStore("스토어" + suffix, "테스트 스토어");
        store.approveStore();
        return userRepository.save(store);
    }

    private StoreProduct addProduct(User store, int initialStock) {
        Product product = productRepository.save(Product.create(
                store, "테스트상품-" + newSuffix(), MainCategory.TOP, SubCategory.T_SHIRT,
                "테스트 설명", 10000, "https://img/test"
        ));

        ProductOption option = productOptionRepository.save(ProductOption.builder()
                .product(product)
                .color("black")
                .size("M")
                .stockQuantity(initialStock)
                .build());

        entityManager.flush();

        return new StoreProduct(store.getUserId(), product.getProductId(), option.getProductOptionId());
    }

    private void deleteIn(String jpql, Collection<Long> ids) {
        entityManager.createQuery(jpql).setParameter("ids", ids).executeUpdate();
    }

    private static String newSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
