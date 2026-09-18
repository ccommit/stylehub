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
import java.util.UUID;

/**
 * @author WonJin Bae
 * @created 2026/09/04
 * @modified 2026/09/17 by WonJin - test: 입점 승인된 스토어 상품 픽스처 추가 (배송 상태 전이·다중 스토어 주문 검증용)
 *
 * <p>
 * 주문 관련 @SpringBootTest 통합 테스트가 매번 존재를 가정해온 User/Address/Product/ProductOption을
 * 실제로 생성해 재현 가능한 상태로 만드는 테스트 전용 픽스처 팩토리이다.
 * Address는 전용 Repository가 없어(주소 CRUD API 미구현) EntityManager로 직접 저장한다.
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

    public record Fixture(Long userId, Long addressId, Long optionId) {
    }

    public record StoreOption(Long storeId, Long optionId) {
    }

    @Transactional
    public Fixture create(int initialStock) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

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

        Product product = productRepository.save(Product.create(
                user, "테스트상품-" + suffix, MainCategory.TOP, SubCategory.T_SHIRT,
                "테스트 설명", 10000, "https://img/test"
        ));

        ProductOption option = productOptionRepository.save(ProductOption.builder()
                .product(product)
                .color("black")
                .size("M")
                .stockQuantity(initialStock)
                .build());

        entityManager.flush();

        return new Fixture(user.getUserId(), address.getAddressId(), option.getProductOptionId());
    }

    // 기본 픽스처(create)는 구매자가 상품 소유자를 겸해 스토어 권한 검증이 필요한 배송 흐름을 재현할 수 없다
    @Transactional
    public StoreOption createApprovedStoreOption(int initialStock) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User store = userRepository.save(User.create(
                "s" + suffix, "s" + suffix + "@test.com", "password", null, UserRole.STORE
        ));
        store.registerStore("스토어" + suffix, "테스트 스토어");
        store.approveStore();

        Product product = productRepository.save(Product.create(
                store, "스토어상품-" + suffix, MainCategory.TOP, SubCategory.T_SHIRT,
                "테스트 설명", 10000, "https://img/test"
        ));
        ProductOption option = productOptionRepository.save(ProductOption.builder()
                .product(product)
                .color("white")
                .size("L")
                .stockQuantity(initialStock)
                .build());

        entityManager.flush();
        return new StoreOption(store.getUserId(), option.getProductOptionId());
    }
}
