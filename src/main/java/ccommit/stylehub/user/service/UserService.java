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
import java.util.Optional;
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
 * @modified 2026/09/15 by WonJin - feat: getUserReference 추가 (선착순 쿠폰 발급에서 사용자 조회 쿼리 제거)
 * @modified 2026/09/17 by WonJin - fix: 로그인 시 미존재 이메일·비밀번호 없는 소셜 계정·비활성 계정을 모두 INVALID_PASSWORD 로 응답하고 더미 해시 검증으로 응답 시간을 맞춤(소셜 계정 NPE 500, 가입 여부 노출 해결)
 * @modified 2026/09/17 by WonJin - refactor: "BCrypt 를 트랜잭션 밖에서 실행해 커넥션 점유를 최소화한다" 주석을 실제 동작(회원가입/로그인 차이, OSIV 전제)에 맞게 정정
 * @modified 2026/09/17 by WonJin - feat: 포인트 변경을 PointService(원자 UPDATE + 이력)에 위임 — 로그인 적립이 엔티티 값에 더해 동시 차감을 덮어쓰던 구조 제거, UserPort 포인트 차감·이력·복구 구현
 * @modified 2026/09/24 by WonJin - refactor: 승인 스토어 검증에서 storeId 비교 제거, 관리자 스토어 상태 변경을 단일 진입점(updateStoreStatus)으로 통합
 *
 * <p>
 * 회원, 스토어의 비즈니스 로직을 처리하고, 포인트 변경은 PointService 에 위임해 UserPort 로 노출한다.
 * 회원가입의 BCrypt 해싱은 DB 접근 전이라 OSIV와 무관하게 커넥션을 쥐지 않는다.
 * 로그인 BCrypt 검증 중 커넥션이 풀로 돌아가는 것은 OSIV가 꺼져 있을 때뿐이다(운영 프로파일). 켜져 있으면 요청이 끝날 때까지 쥔다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserPort {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TransactionTemplate transactionTemplate;
    private final PointService pointService;

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

    // 미존재 이메일·비밀번호 없는 소셜 계정·비활성 계정도 INVALID_PASSWORD로 응답해 가입 여부와 계정 유형을 숨긴다.
    // 이때도 더미 해시로 BCrypt 검증을 수행해 응답 시간으로 가입 여부를 추정하지 못하게 한다.
    public UserLoginResponse login(UserLoginRequest request) {
        Optional<User> found = Objects.requireNonNull(
                transactionTemplate.execute(status -> userRepository.findByEmail(request.email()))
        );

        User user = found.filter(this::canLoginWithPassword).orElse(null);
        if (user == null) {
            passwordHasher.verifyDummy(request.password());
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        if (!passwordHasher.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        if (user.getRole() == UserRole.USER) {
            rewardLoginPoint(user.getUserId(), LocalDate.now());
        }

        return UserLoginResponse.from(user);
    }

    // 소셜 가입 계정은 비밀번호가 없고, 비활성 계정은 로그인 대상이 아니다.
    private boolean canLoginWithPassword(User user) {
        return user.getPassword() != null && Boolean.TRUE.equals(user.getActive());
    }

    @Override
    public User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    @Override
    public User getUserReference(Long userId) {
        return userRepository.getReferenceById(userId);
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

    @Transactional
    public void registerStore(User user, String storeName, String storeDescription) {
        if (user.getStoreName() != null) {
            throw new BusinessException(ErrorCode.STORE_ALREADY_EXISTS);
        }
        user.registerStore(storeName, storeDescription);
    }

    @Override
    public void validateApprovedStore(Long storeUserId) {
        findApprovedStore(storeUserId);
    }

    @Override
    public User findApprovedStore(Long storeUserId) {
        User user = userRepository.findById(storeUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

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

    // PENDING 은 입점 신청으로만 들어가는 초기 상태라 관리자가 되돌릴 수 없다
    public StoreResponse updateStoreStatus(Long userId, StoreStatus status) {
        return switch (status) {
            case APPROVED -> approveStore(userId);
            case REJECTED -> rejectStore(userId);
            case SUSPENDED -> suspendStore(userId);
            case PENDING -> throw new BusinessException(ErrorCode.INVALID_STORE_STATUS);
        };
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

    // login()이 같은 클래스에서 호출해도(self-invocation) 적립 트랜잭션은 다른 빈인 PointService 프록시에서 열려 적용된다.
    // 이 메서드에 @Transactional 을 붙였을 때는 무시돼 포인트가 조용히 유실됐다(LoginPointPersistenceTest).
    public void rewardLoginPoint(Long userId, LocalDate today) {
        pointService.rewardLoginPoint(userId, today);
    }

    @Override
    public void deductPoint(Long userId, int amount) {
        pointService.deductPoint(userId, amount);
    }

    @Override
    public void recordPointUse(Long userId, Long orderId, int amount) {
        pointService.recordPointUse(userId, orderId, amount);
    }

    @Override
    public void restoreUsedPoint(Long userId, Long orderId, int amount) {
        pointService.restoreUsedPoint(userId, orderId, amount);
    }
}
