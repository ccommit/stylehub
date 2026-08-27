package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.request.StoreSignUpRequest;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.response.StoreResponse;
import ccommit.stylehub.user.dto.response.StoreSignUpResponse;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * UserService의 회원가입/로그인/스토어 관리/포인트 적립 로직을 검증하는 단위테스트이다.
 * TransactionTemplate은 실제 트랜잭션 없이 콜백을 즉시 실행하도록 Stub 처리한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private UserService userService;

    /**
     * transactionTemplate.execute(callback)이 실제 트랜잭션 없이 콜백을 즉시 실행하도록 만든다.
     * 테스트에서 사용하는 콜백은 TransactionStatus를 사용하지 않으므로 null을 전달해도 안전하다.
     */
    private void stubTransactionTemplateToRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private User userWithId(long userId, String name, String email, UserRole role) {
        User user = User.create(name, email, "HASHED", LocalDate.of(2000, 1, 1), role);
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    @Nested
    @DisplayName("signUp")
    class SignUp {

        @Test
        @DisplayName("이메일/닉네임이 중복되지 않으면 비밀번호를 해싱해 회원을 저장한다")
        void 정상적으로_회원을_저장한다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(passwordHasher.hash("pw123!")).thenReturn("HASHED");
            when(userRepository.existsByEmail("a@test.com")).thenReturn(false);
            when(userRepository.existsByName("철수")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "userId", 1L);
                return saved;
            });

            // when
            User result = userService.signUp("철수", "a@test.com", "pw123!", LocalDate.of(2000, 1, 1), UserRole.USER);

            // then
            assertThat(result.getUserId()).isEqualTo(1L);
            assertThat(result.getPassword()).isEqualTo("HASHED");
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("이메일이 이미 존재하면 저장 없이 예외가 발생한다")
        void 이메일이_중복이면_예외가_발생한다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(passwordHasher.hash(any())).thenReturn("HASHED");
            when(userRepository.existsByEmail("a@test.com")).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> userService.signUp("철수", "a@test.com", "pw123!", null, UserRole.USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("이미 사용 중인 이메일입니다");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("닉네임이 이미 존재하면 저장 없이 예외가 발생한다")
        void 닉네임이_중복이면_예외가_발생한다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(passwordHasher.hash(any())).thenReturn("HASHED");
            when(userRepository.existsByEmail("a@test.com")).thenReturn(false);
            when(userRepository.existsByName("철수")).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> userService.signUp("철수", "a@test.com", "pw123!", null, UserRole.USER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("이미 사용 중인 닉네임입니다");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("동시 가입 경합으로 DB 유니크 제약이 깨지면 DUPLICATE_EMAIL_OR_NAME 예외로 변환한다")
        void 동시가입_유니크제약위반은_비즈니스예외로_변환된다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(passwordHasher.hash(any())).thenReturn("HASHED");
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.existsByName(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

            // when & then
            assertThatThrownBy(() -> userService.signUp("철수", "a@test.com", "pw123!", null, UserRole.USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL_OR_NAME);
        }
    }

    @Nested
    @DisplayName("signUpWithStore")
    class SignUpWithStore {

        private final StoreSignUpRequest request = new StoreSignUpRequest(
                "철수", "a@test.com", "pw123!", "무신사 스토어", "설명");

        @Test
        @DisplayName("회원가입과 스토어 입점 신청을 함께 처리한다")
        void 회원가입과_입점신청을_함께_처리한다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(passwordHasher.hash("pw123!")).thenReturn("HASHED");
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.existsByName(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "userId", 1L);
                return saved;
            });

            // when
            StoreSignUpResponse response = userService.signUpWithStore(request);

            // then
            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.role()).isEqualTo(UserRole.STORE);
            assertThat(response.storeName()).isEqualTo("무신사 스토어");
            assertThat(response.storeStatus()).isEqualTo(StoreStatus.PENDING);
        }

        @Test
        @DisplayName("동시 가입 경합으로 DB 유니크 제약이 깨지면 DUPLICATE_EMAIL_OR_NAME 예외로 변환한다")
        void 동시가입_유니크제약위반은_비즈니스예외로_변환된다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(passwordHasher.hash(any())).thenReturn("HASHED");
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.existsByName(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

            // when & then
            assertThatThrownBy(() -> userService.signUpWithStore(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL_OR_NAME);
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("이메일과 비밀번호가 일치하면 로그인에 성공하고 최초 로그인 포인트를 지급한다")
        void 로그인에_성공하면_최초_포인트를_지급한다() {
            // given
            stubTransactionTemplateToRunCallback();
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            UserLoginRequest request = new UserLoginRequest("a@test.com", "pw123!");
            when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(user));
            when(passwordHasher.matches("pw123!", "HASHED")).thenReturn(true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // when
            UserLoginResponse response = userService.login(request);

            // then
            assertThat(response.userId()).isEqualTo(1L);
            assertThat(user.getPointBalance()).isEqualTo(1000);
            assertThat(user.getLastLoginDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("ADMIN 역할은 로그인 포인트 지급 로직을 실행하지 않는다")
        void ADMIN은_포인트_지급_로직을_실행하지_않는다() {
            // given
            stubTransactionTemplateToRunCallback();
            User admin = userWithId(1L, "관리자", "admin@test.com", UserRole.ADMIN);
            UserLoginRequest request = new UserLoginRequest("admin@test.com", "pw123!");
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));
            when(passwordHasher.matches("pw123!", "HASHED")).thenReturn(true);

            // when
            userService.login(request);

            // then
            verify(userRepository, never()).findById(anyLong());
            assertThat(admin.getPointBalance()).isZero();
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 예외가 발생한다")
        void 존재하지_않는_이메일이면_예외가_발생한다() {
            // given
            stubTransactionTemplateToRunCallback();
            when(userRepository.findByEmail("none@test.com")).thenReturn(Optional.empty());
            UserLoginRequest request = new UserLoginRequest("none@test.com", "pw123!");

            // when & then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("존재하지 않는 이메일입니다");
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 예외가 발생한다")
        void 비밀번호가_일치하지_않으면_예외가_발생한다() {
            // given
            stubTransactionTemplateToRunCallback();
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(user));
            when(passwordHasher.matches("wrong", "HASHED")).thenReturn(false);
            UserLoginRequest request = new UserLoginRequest("a@test.com", "wrong");

            // when & then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("비밀번호가 일치하지 않습니다");
            verify(userRepository, never()).findById(anyLong());
        }
    }

    @Nested
    @DisplayName("findUserById / findAddressByOwner (UserPort)")
    class Queries {

        @Test
        @DisplayName("존재하는 사용자 ID면 User를 반환한다")
        void findUserById_성공() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // when & then
            assertThat(userService.findUserById(1L)).isEqualTo(user);
        }

        @Test
        @DisplayName("존재하지 않는 사용자 ID면 USER_NOT_FOUND 예외가 발생한다")
        void findUserById_없으면_예외() {
            // given
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.findUserById(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 소유 배송지면 정상 반환한다")
        void findAddressByOwner_성공() {
            // given
            User owner = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            Address address = Address.create(owner, "집", "철수", "010-0000-0000", "12345", "도로명", "상세");
            ReflectionTestUtils.setField(address, "addressId", 100L);
            when(userRepository.findAddressByIdWithUser(100L)).thenReturn(Optional.of(address));

            // when & then
            assertThat(userService.findAddressByOwner(1L, 100L)).isEqualTo(address);
        }

        @Test
        @DisplayName("존재하지 않는 배송지면 ADDRESS_NOT_FOUND 예외가 발생한다")
        void findAddressByOwner_없으면_예외() {
            // given
            when(userRepository.findAddressByIdWithUser(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.findAddressByOwner(1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 사용자의 배송지면 UNAUTHORIZED_ORDER_ACCESS 예외가 발생한다")
        void findAddressByOwner_소유자가_다르면_예외() {
            // given
            User owner = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            Address address = Address.create(owner, "집", "철수", "010-0000-0000", "12345", "도로명", "상세");
            ReflectionTestUtils.setField(address, "addressId", 100L);
            when(userRepository.findAddressByIdWithUser(100L)).thenReturn(Optional.of(address));

            // when & then
            assertThatThrownBy(() -> userService.findAddressByOwner(2L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_ORDER_ACCESS);
        }
    }

    @Nested
    @DisplayName("스토어 입점/소유권 검증")
    class Store {

        @Test
        @DisplayName("스토어가 없는 유저는 정상적으로 입점 신청된다")
        void registerStore_성공() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.STORE);

            // when
            userService.registerStore(user, "무신사 스토어", "설명");

            // then
            assertThat(user.getStoreName()).isEqualTo("무신사 스토어");
            assertThat(user.getStoreStatus()).isEqualTo(StoreStatus.PENDING);
        }

        @Test
        @DisplayName("이미 스토어가 있는 유저는 STORE_ALREADY_EXISTS 예외가 발생한다")
        void registerStore_이미존재하면_예외() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.STORE);
            user.registerStore("기존 스토어", "설명");

            // when & then
            assertThatThrownBy(() -> userService.registerStore(user, "새 스토어", "설명"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("승인된 본인 스토어면 findApprovedStoreByOwner가 User를 반환한다")
        void findApprovedStoreByOwner_성공() {
            // given
            User store = userWithId(10L, "스토어주인", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            store.approveStore();
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when & then
            assertThat(userService.findApprovedStoreByOwner(10L, 10L)).isEqualTo(store);
        }

        @Test
        @DisplayName("존재하지 않는 스토어면 STORE_NOT_FOUND 예외가 발생한다")
        void findApprovedStoreByOwner_스토어없음_예외() {
            // given
            when(userRepository.findById(10L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.findApprovedStoreByOwner(10L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 소유가 아니면 UNAUTHORIZED_STORE_ACCESS 예외가 발생한다")
        void findApprovedStoreByOwner_소유자다르면_예외() {
            // given
            User store = userWithId(10L, "스토어주인", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            store.approveStore();
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when & then
            assertThatThrownBy(() -> userService.findApprovedStoreByOwner(99L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_STORE_ACCESS);
        }

        @Test
        @DisplayName("승인 대기/거절/정지 상태면 STORE_NOT_APPROVED 예외가 발생한다")
        void findApprovedStoreByOwner_미승인상태면_예외() {
            // given
            User store = userWithId(10L, "스토어주인", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명"); // PENDING 상태
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when & then
            assertThatThrownBy(() -> userService.findApprovedStoreByOwner(10L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_APPROVED);
        }

        @Test
        @DisplayName("validateApprovedStoreOwner는 검증만 하고 예외가 없으면 정상 반환한다")
        void validateApprovedStoreOwner_검증만_수행한다() {
            // given
            User store = userWithId(10L, "스토어주인", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            store.approveStore();
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when & then (예외가 발생하지 않아야 한다)
            userService.validateApprovedStoreOwner(10L, 10L);
        }

        @Test
        @DisplayName("본인 스토어 정보를 정상 조회한다")
        void getMyStore_성공() {
            // given
            User store = userWithId(10L, "스토어주인", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when
            StoreResponse response = userService.getMyStore(10L);

            // then
            assertThat(response.name()).isEqualTo("스토어");
            assertThat(response.status()).isEqualTo(StoreStatus.PENDING);
        }

        @Test
        @DisplayName("스토어가 없는 유저면 STORE_NOT_FOUND 예외가 발생한다")
        void getMyStore_스토어없으면_예외() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // when & then
            assertThatThrownBy(() -> userService.getMyStore(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("스토어 관리 (ADMIN)")
    class StoreAdmin {

        @Test
        @DisplayName("상태를 지정하면 해당 상태의 스토어만 조회한다")
        void getStoresByStatus_상태지정시_필터조회() {
            // given
            User store = userWithId(10L, "스토어", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            when(userRepository.findByStoreStatus(StoreStatus.PENDING)).thenReturn(List.of(store));

            // when
            List<StoreResponse> result = userService.getStoresByStatus(StoreStatus.PENDING);

            // then
            assertThat(result).hasSize(1);
            verify(userRepository, never()).findByStoreStatusNotNull();
        }

        @Test
        @DisplayName("상태를 지정하지 않으면 스토어를 가진 전체 유저를 조회한다")
        void getStoresByStatus_상태null이면_전체조회() {
            // given
            when(userRepository.findByStoreStatusNotNull()).thenReturn(List.of());

            // when
            userService.getStoresByStatus(null);

            // then
            verify(userRepository).findByStoreStatusNotNull();
            verify(userRepository, never()).findByStoreStatus(any());
        }

        @Test
        @DisplayName("PENDING 상태의 스토어를 승인하면 APPROVED로 전이한다")
        void approveStore_성공() {
            // given
            stubTransactionTemplateToRunCallback();
            User store = userWithId(10L, "스토어", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when
            StoreResponse response = userService.approveStore(10L);

            // then
            assertThat(response.status()).isEqualTo(StoreStatus.APPROVED);
        }

        @Test
        @DisplayName("PENDING이 아닌 스토어를 승인하려 하면 INVALID_STORE_STATUS 예외가 발생한다")
        void approveStore_잘못된상태전이면_예외() {
            // given
            stubTransactionTemplateToRunCallback();
            User store = userWithId(10L, "스토어", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            store.approveStore(); // 이미 APPROVED
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when & then
            assertThatThrownBy(() -> userService.approveStore(10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STORE_STATUS);
        }

        @Test
        @DisplayName("PENDING 상태의 스토어를 거절하면 REJECTED로 전이한다")
        void rejectStore_성공() {
            // given
            stubTransactionTemplateToRunCallback();
            User store = userWithId(10L, "스토어", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when
            StoreResponse response = userService.rejectStore(10L);

            // then
            assertThat(response.status()).isEqualTo(StoreStatus.REJECTED);
        }

        @Test
        @DisplayName("APPROVED 상태의 스토어를 정지하면 SUSPENDED로 전이한다")
        void suspendStore_성공() {
            // given
            stubTransactionTemplateToRunCallback();
            User store = userWithId(10L, "스토어", "s@test.com", UserRole.STORE);
            store.registerStore("스토어", "설명");
            store.approveStore();
            when(userRepository.findById(10L)).thenReturn(Optional.of(store));

            // when
            StoreResponse response = userService.suspendStore(10L);

            // then
            assertThat(response.status()).isEqualTo(StoreStatus.SUSPENDED);
        }

        @Test
        @DisplayName("스토어를 가지지 않은 유저를 대상으로 하면 STORE_NOT_FOUND 예외가 발생한다")
        void 스토어_없는_유저_대상이면_예외() {
            // given
            stubTransactionTemplateToRunCallback();
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // when & then
            assertThatThrownBy(() -> userService.approveStore(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("rewardLoginPoint")
    class RewardLoginPoint {

        @Test
        @DisplayName("최초 로그인이면 1000점을 지급한다")
        void 최초_로그인시_1000점_지급() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            LocalDate today = LocalDate.now();

            // when
            userService.rewardLoginPoint(1L, today);

            // then
            assertThat(user.getPointBalance()).isEqualTo(1000);
            assertThat(user.getLastLoginDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("같은 날 다시 로그인하면 포인트를 지급하지 않는다")
        void 같은날_재로그인시_지급하지_않는다() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            LocalDate today = LocalDate.now();
            user.updateLastLoginDate(today);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            // when
            userService.rewardLoginPoint(1L, today);

            // then
            assertThat(user.getPointBalance()).isZero();
        }

        @Test
        @DisplayName("이전 로그인일과 다른 날 로그인하면 10점을 지급한다")
        void 다른날_로그인시_10점_지급() {
            // given
            User user = userWithId(1L, "철수", "a@test.com", UserRole.USER);
            user.updateLastLoginDate(LocalDate.now().minusDays(3));
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            LocalDate today = LocalDate.now();

            // when
            userService.rewardLoginPoint(1L, today);

            // then
            assertThat(user.getPointBalance()).isEqualTo(10);
            assertThat(user.getLastLoginDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("ADMIN은 포인트를 지급받지 않지만 로그인일은 갱신되지 않는다")
        void ADMIN은_포인트지급_대상이_아니다() {
            // given
            User admin = userWithId(1L, "관리자", "admin@test.com", UserRole.ADMIN);
            when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

            // when
            userService.rewardLoginPoint(1L, LocalDate.now());

            // then
            assertThat(admin.getPointBalance()).isZero();
            assertThat(admin.getLastLoginDate()).isNull();
        }

        @Test
        @DisplayName("존재하지 않는 사용자면 예외가 발생한다")
        void 사용자가_없으면_예외() {
            // given
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.rewardLoginPoint(999L, LocalDate.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("존재하지 않는 사용자입니다");
        }
    }
}
