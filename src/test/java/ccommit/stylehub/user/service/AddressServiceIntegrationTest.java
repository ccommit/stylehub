package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.user.dto.request.AddressCreateRequest;
import ccommit.stylehub.user.dto.response.AddressResponse;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.AddressRepository;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 배송지 규칙을 실제 H2 트랜잭션·락·FK 위에서 검증한다. 동시 등록 직렬화와 FK 위반 변환은 목으로 재현되지 않기 때문이다.
 * 커밋된 데이터를 만들므로 @Transactional 롤백 대신 @AfterEach에서 직접 지운다.
 * </p>
 */
@SpringBootTest
class AddressServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AddressServiceIntegrationTest.class);

    @Autowired
    private AddressService addressService;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();

    // FK 의존 순서대로 지운다: orders → addresses → users
    @AfterEach
    void cleanUp() {
        createdOrderIds.forEach(orderRepository::deleteById);
        createdOrderIds.clear();
        createdUserIds.forEach(userId ->
                addressRepository.deleteAll(addressRepository.findAllByUserIdOrderByDefaultFirst(userId)));
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("같은 사용자가 10개 스레드로 동시에 등록해도 정확히 5개만 저장되고 기본 배송지는 1개다")
    void concurrentRegistration_keepsLimitAndSingleDefault() throws InterruptedException {
        // given
        Long userId = createUser();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger limitExceededCount = new AtomicInteger();
        AtomicInteger unexpectedErrorCount = new AtomicInteger();

        // when — 출발 신호를 맞춰 10개 요청이 한꺼번에 개수 확인 구간에 들어가게 한다
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    addressService.registerAddress(userId, createRequest("배송지" + index));
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.ADDRESS_LIMIT_EXCEEDED) {
                        limitExceededCount.incrementAndGet();
                    } else {
                        unexpectedErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("동시 등록 중 예상하지 못한 예외", e);
                    unexpectedErrorCount.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        boolean finished = finish.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then — 응답 결과가 아니라 DB 에 남은 행으로 확인한다
        List<Address> saved = addressRepository.findAllByUserIdOrderByDefaultFirst(userId);
        long defaultCount = saved.stream().filter(Address::isDefault).count();
        log.info("동시 등록 결과 - 성공: {}, 개수 초과: {}, 기타 오류: {}, 저장: {}, 기본: {}",
                successCount.get(), limitExceededCount.get(), unexpectedErrorCount.get(), saved.size(), defaultCount);

        assertThat(finished).isTrue();
        assertThat(unexpectedErrorCount.get()).isZero();
        assertThat(successCount.get()).isEqualTo(AddressService.MAX_ADDRESS_COUNT);
        assertThat(limitExceededCount.get()).isEqualTo(threadCount - AddressService.MAX_ADDRESS_COUNT);
        assertThat(saved).hasSize(AddressService.MAX_ADDRESS_COUNT);
        assertThat(defaultCount).isEqualTo(1);
    }

    @Test
    @DisplayName("주문이 참조하는 배송지를 삭제하면 실제 FK 위반이 ADDRESS_IN_USE 로 바뀌고 배송지와 기본 지정은 그대로 남는다")
    void deletingAddressUsedByOrder_throwsInUseAndRollsBack() {
        // given
        Long userId = createUser();
        AddressResponse used = addressService.registerAddress(userId, createRequest("집"));
        AddressResponse other = addressService.registerAddress(userId, createRequest("회사"));
        createOrder(userId, used.addressId());

        // when / then
        assertThatThrownBy(() -> addressService.deleteAddress(userId, used.addressId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADDRESS_IN_USE);

        // 롤백으로 삭제도 승계도 반영되지 않아야 한다
        Address reloadedUsed = addressRepository.findById(used.addressId()).orElseThrow();
        Address reloadedOther = addressRepository.findById(other.addressId()).orElseThrow();
        assertThat(reloadedUsed.isDefault()).isTrue();
        assertThat(reloadedOther.isDefault()).isFalse();
    }

    @Test
    @DisplayName("기본 배송지를 삭제하면 DB 에서도 가장 최근 등록 배송지가 기본으로 승계된다")
    void deletingDefault_promotesLatestInDatabase() {
        // given
        Long userId = createUser();
        AddressResponse first = addressService.registerAddress(userId, createRequest("첫째"));
        AddressResponse second = addressService.registerAddress(userId, createRequest("둘째"));
        AddressResponse third = addressService.registerAddress(userId, createRequest("셋째"));
        assertThat(first.isDefault()).isTrue();

        // when
        addressService.deleteAddress(userId, first.addressId());

        // then — 기본 먼저, 나머지 최근 등록순 조회 결과로 승계와 정렬을 함께 확인한다
        List<AddressResponse> remaining = addressService.getAddresses(userId);
        assertThat(remaining).extracting(AddressResponse::addressId)
                .containsExactly(third.addressId(), second.addressId());
        assertThat(remaining).extracting(AddressResponse::isDefault)
                .containsExactly(true, false);
    }

    // ===== Helper =====

    private Long createUser() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.create(
                "addr" + unique,
                "addr-" + unique + "@test.com",
                "password",
                LocalDate.of(2000, 1, 1),
                UserRole.USER
        ));
        createdUserIds.add(user.getUserId());
        return user.getUserId();
    }

    private void createOrder(Long userId, Long addressId) {
        Long orderId = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Order order = Order.create(
                    userRepository.getReferenceById(userId),
                    addressRepository.getReferenceById(addressId)
            );
            return orderRepository.save(order).getOrderId();
        }));
        createdOrderIds.add(orderId);
    }

    private AddressCreateRequest createRequest(String label) {
        return new AddressCreateRequest(label, "홍길동", "01012345678", "06236", "서울시 강남구 테헤란로 1", "101호");
    }
}
