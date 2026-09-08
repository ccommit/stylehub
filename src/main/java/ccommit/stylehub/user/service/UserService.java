package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.request.StoreSignUpRequest;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.response.StoreResponse;
import ccommit.stylehub.user.dto.response.StoreSignUpResponse;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.port.UserPort;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/25 by WonJin - feat: STORE 역할 회원 생성 메서드 추가
 * @modified 2026/03/26 by WonJin - refactor: 해싱과 저장 분리로 외부 트랜잭션 참여 지원
 * @modified 2026/03/27 by WonJin - feat: findUserById, findAddressByOwner 추가
 * @modified 2026/04/19 by WonJin - refactor: StoreService, StoreAdminService, PointRewardService를 UserService로 통합
 * @modified 2026/09/08 by WonJin - fix: rewardLoginPoint 를 TransactionTemplate 으로 전환 — login() 의 self-invocation 으로 @Transactional 이 적용되지 않아 포인트 적립이 DB 에 반영되지 않던 문제 해결
 *
 * <p>
 * 회원, 스토어, 포인트의 비즈니스 로직을 처리한다.
 * BCrypt 해싱을 트랜잭션 밖에서 실행하여 커넥션 점유를 최소화한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserPort {

    private static final int FIRST_LOGIN_POINT = 1000;
    private static final int DAILY_LOGIN_POINT = 10;

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TransactionTemplate transactionTemplate;

    // ========================
    // 회원가입 / 로그인
    // ========================

    public User signUp(String name, String email, String password, LocalDate birthDate, UserRole role) {
        String hashedPassword = hashPassword(password);

        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status ->
                            saveUser(name, email, hashedPassword, birthDate, role)
                    )
            );
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL_OR_NAME);
        }
    }

    public String hashPassword(String rawPassword) {
        return passwordHasher.hash(rawPassword);
    }

    public User saveUser(String name, String email, String hashedPassword, LocalDate birthDate, UserRole role) {
        validateSignUp(email, name);
        User user = User.create(name, email, hashedPassword, birthDate, role);
        return userRepository.save(user);
    }

    private void validateSignUp(String email, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByName(name)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NAME);
        }
    }

    public StoreSignUpResponse signUpWithStore(StoreSignUpRequest request) {
        String hashedPassword = hashPassword(request.password());

        User user;
        try {
            user = Objects.requireNonNull(
                    transactionTemplate.execute(status -> {
                        User savedUser = saveUser(
                                request.name(), request.email(), hashedPassword, null, UserRole.STORE
                        );
                        registerStore(savedUser, request.storeName(), request.storeDescription());
                        return savedUser;
                    })
            );
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL_OR_NAME);
        }

        return StoreSignUpResponse.from(user);
    }

    public UserLoginResponse login(UserLoginRequest request) {
        User user = Objects.requireNonNull(
                transactionTemplate.execute(status ->
                        userRepository.findByEmail(request.email())
                                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PASSWORD))
                )
        );

        if (!passwordHasher.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        if (user.getRole() == UserRole.USER) {
            rewardLoginPoint(user.getUserId(), LocalDate.now());
        }

        return UserLoginResponse.from(user);
    }

    // ========================
    // 조회
    // ========================

    @Override
    public User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    @Override
    public Address findAddressByOwner(Long userId, Long addressId) {
        Address address = userRepository.findAddressByIdWithUser(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));

        if (!address.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_ORDER_ACCESS);
        }

        return address;
    }

    // ========================
    // 스토어 (STORE 역할)
    // ========================

    @Transactional
    public void registerStore(User user, String storeName, String storeDescription) {
        if (user.getStoreName() != null) {
            throw new BusinessException(ErrorCode.STORE_ALREADY_EXISTS);
        }
        user.registerStore(storeName, storeDescription);
    }

    @Override
    public void validateApprovedStoreOwner(Long userId, Long storeId) {
        findApprovedStoreByOwner(userId, storeId);
    }

    @Override
    public User findApprovedStoreByOwner(Long userId, Long storeId) {
        User user = userRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        if (!user.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_STORE_ACCESS);
        }

        if (user.getStoreStatus() != StoreStatus.APPROVED) {
            throw new BusinessException(ErrorCode.STORE_NOT_APPROVED);
        }

        return user;
    }

    @Transactional(readOnly = true)
    public StoreResponse getMyStore(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        if (user.getStoreName() == null) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }

        return StoreResponse.from(user);
    }

    // ========================
    // 스토어 관리 (ADMIN 역할)
    // ========================

    @Transactional(readOnly = true)
    public List<StoreResponse> getStoresByStatus(StoreStatus status) {
        List<User> users = (status != null)
                ? userRepository.findByStoreStatus(status)
                : userRepository.findByStoreStatusNotNull();

        return users.stream()
                .map(StoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse getStoreByUserId(Long userId) {
        User user = findStoreUser(userId);
        return StoreResponse.from(user);
    }

    public StoreResponse approveStore(Long userId) {
        return changeStoreStatus(userId, User::approveStore);
    }

    public StoreResponse rejectStore(Long userId) {
        return changeStoreStatus(userId, User::rejectStore);
    }

    public StoreResponse suspendStore(Long userId) {
        return changeStoreStatus(userId, User::suspendStore);
    }

    private StoreResponse changeStoreStatus(Long userId, Consumer<User> action) {
        User user = Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    User target = findStoreUser(userId);
                    action.accept(target);
                    return target;
                })
        );
        return StoreResponse.from(user);
    }

    private User findStoreUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        if (user.getStoreName() == null) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }

        return user;
    }

    // ========================
    // 포인트
    // ========================

    /**
     * 로그인 포인트를 적립한다.
     *
     * <p>@Transactional 대신 TransactionTemplate 을 쓴다. login() 이 같은 클래스에서 이 메서드를
     * 호출하는데(self-invocation), 그 경우 프록시를 거치지 않아 @Transactional 이 적용되지 않는다.
     * 트랜잭션이 열리지 않으면 findById 로 얻은 엔티티가 영속 상태로 유지되지 않아 변경 감지가
     * 동작하지 않고, 포인트가 예외나 로그 없이 조용히 유실된다.
     *
     * <p>TransactionTemplate 은 프록시가 아니라 블록 자체가 트랜잭션 경계이므로 호출 경로와 무관하게
     * 동작한다. login() 과 OAuthService 양쪽에서 동일하게 적립된다.
     */
    public void rewardLoginPoint(Long userId, LocalDate today) {
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

            if (user.getRole() == UserRole.ADMIN) {
                return;
            }

            if (user.getLastLoginDate() == null) {
                user.addPoint(FIRST_LOGIN_POINT);
            } else if (!user.getLastLoginDate().equals(today)) {
                user.addPoint(DAILY_LOGIN_POINT);
            }
            user.updateLastLoginDate(today);
        });
    }
}
