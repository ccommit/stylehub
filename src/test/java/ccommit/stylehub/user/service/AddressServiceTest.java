package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.request.AddressCreateRequest;
import ccommit.stylehub.user.dto.response.AddressResponse;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.repository.AddressRepository;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * AddressService의 기본 배송지 1개 규칙, 최대 개수, 소유권 404, 사용 중인 배송지 삭제 거절을 검증하는 단위 테스트이다.
 * 배송지는 실제 엔티티로 만들어 바뀐 기본 상태를 확인하고, 동시 요청 직렬화와 실제 FK 위반은 AddressServiceIntegrationTest에서 다룬다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AddressService addressService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().userId(USER_ID).build();
        given(userRepository.findByIdWithLock(USER_ID)).willReturn(Optional.of(user));
    }

    @Nested
    @DisplayName("registerAddress")
    class RegisterAddress {

        @Test
        @DisplayName("첫 배송지는 자동으로 기본 배송지가 된다")
        void marksFirstAddressAsDefault() {
            // given
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID)).willReturn(List.of());
            given(addressRepository.save(any(Address.class))).willAnswer(invocation -> invocation.getArgument(0));

            // when
            AddressResponse response = addressService.registerAddress(USER_ID, createRequest());

            // then
            assertThat(response.isDefault()).isTrue();
            assertThat(response.recipientName()).isEqualTo("홍길동");
            then(userRepository).should().findByIdWithLock(USER_ID);   // 개수 확인 전에 사용자 행을 잠근다
        }

        @Test
        @DisplayName("이미 기본 배송지가 있으면 새 배송지는 기본이 아니다")
        void doesNotMarkAsDefault_whenDefaultExists() {
            // given
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID))
                    .willReturn(List.of(address(10L, true)));
            given(addressRepository.save(any(Address.class))).willAnswer(invocation -> invocation.getArgument(0));

            // when
            AddressResponse response = addressService.registerAddress(USER_ID, createRequest());

            // then
            assertThat(response.isDefault()).isFalse();
        }

        @Test
        @DisplayName("이미 5개를 보유하면 6번째 등록은 ADDRESS_LIMIT_EXCEEDED 로 거절하고 저장하지 않는다")
        void throwsLimitExceeded_whenSixthAddress() {
            // given
            List<Address> fiveAddresses = new ArrayList<>();
            fiveAddresses.add(address(1L, true));
            for (long id = 2L; id <= 5L; id++) {
                fiveAddresses.add(address(id, false));
            }
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID)).willReturn(fiveAddresses);

            // when / then
            assertThatThrownBy(() -> addressService.registerAddress(USER_ID, createRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADDRESS_LIMIT_EXCEEDED);
            then(addressRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("세션 사용자가 존재하지 않으면 USER_NOT_FOUND 를 던진다")
        void throwsUserNotFound_whenUserMissing() {
            // given
            given(userRepository.findByIdWithLock(USER_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> addressService.registerAddress(USER_ID, createRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
            then(addressRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("changeDefaultAddress")
    class ChangeDefaultAddress {

        @Test
        @DisplayName("기본 배송지를 바꾸면 기존 기본은 해제되고 대상만 기본이 된다")
        void changesDefault_andUnmarksPrevious() {
            // given
            Address previousDefault = address(1L, true);
            Address other = address(2L, false);
            Address target = address(3L, false);
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID))
                    .willReturn(List.of(previousDefault, target, other));

            // when
            AddressResponse response = addressService.changeDefaultAddress(USER_ID, 3L);

            // then
            assertThat(response.addressId()).isEqualTo(3L);
            assertThat(response.isDefault()).isTrue();
            assertThat(previousDefault.isDefault()).isFalse();
            assertThat(other.isDefault()).isFalse();
            assertThat(target.isDefault()).isTrue();
        }

        @Test
        @DisplayName("본인 목록에 없는 addressId(타인 소유 포함)는 존재 여부를 드러내지 않도록 ADDRESS_NOT_FOUND 를 던진다")
        void throwsNotFound_whenNotOwned() {
            // given — 다른 사용자의 배송지 99번은 본인 목록 조회 결과에 나타나지 않는다
            Address ownDefault = address(1L, true);
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID)).willReturn(List.of(ownDefault));

            // when / then
            assertThatThrownBy(() -> addressService.changeDefaultAddress(USER_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADDRESS_NOT_FOUND);
            assertThat(ownDefault.isDefault()).isTrue();
        }
    }

    @Nested
    @DisplayName("deleteAddress")
    class DeleteAddress {

        @Test
        @DisplayName("기본 배송지를 삭제하면 남은 배송지 중 addressId 가 가장 큰(가장 최근 등록) 배송지가 기본을 승계한다")
        void promotesLatestAddress_whenDefaultDeleted() {
            // given — 조회 순서(기본 먼저, id 내림차순)와 무관하게 id 최대값을 고르는지 보기 위해 순서를 섞는다
            Address defaultAddress = address(5L, true);
            Address older = address(2L, false);
            Address latest = address(7L, false);
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID))
                    .willReturn(List.of(defaultAddress, older, latest));

            // when
            addressService.deleteAddress(USER_ID, 5L);

            // then
            then(addressRepository).should().delete(defaultAddress);
            then(addressRepository).should().flush();
            assertThat(latest.isDefault()).isTrue();
            assertThat(older.isDefault()).isFalse();
        }

        @Test
        @DisplayName("기본이 아닌 배송지를 삭제하면 기존 기본 배송지는 그대로다")
        void keepsDefault_whenNonDefaultDeleted() {
            // given
            Address defaultAddress = address(1L, true);
            Address target = address(2L, false);
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID))
                    .willReturn(List.of(defaultAddress, target));

            // when
            addressService.deleteAddress(USER_ID, 2L);

            // then
            then(addressRepository).should().delete(target);
            assertThat(defaultAddress.isDefault()).isTrue();
        }

        @Test
        @DisplayName("타인 소유이거나 없는 배송지 삭제는 ADDRESS_NOT_FOUND 를 던지고 삭제하지 않는다")
        void throwsNotFound_whenNotOwned() {
            // given
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID)).willReturn(List.of(address(1L, true)));

            // when / then
            assertThatThrownBy(() -> addressService.deleteAddress(USER_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADDRESS_NOT_FOUND);
            then(addressRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("주문에 사용된 배송지는 flush 시 FK 위반이 나고 ADDRESS_IN_USE 로 바뀌며, 기본 승계도 일어나지 않는다")
        void throwsInUse_whenReferencedByOrder() {
            // given
            Address defaultAddress = address(1L, true);
            Address other = address(2L, false);
            given(addressRepository.findAllByUserIdOrderByDefaultFirst(USER_ID))
                    .willReturn(List.of(defaultAddress, other));
            willThrow(new DataIntegrityViolationException("FK_ORDERS_ADDRESS"))
                    .given(addressRepository).flush();

            // when / then
            assertThatThrownBy(() -> addressService.deleteAddress(USER_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ADDRESS_IN_USE);
            assertThat(other.isDefault()).isFalse();
        }
    }

    // ===== Helper =====

    private AddressCreateRequest createRequest() {
        return new AddressCreateRequest("집", "홍길동", "01012345678", "06236", "서울시 강남구 테헤란로 1", "101호");
    }

    private Address address(Long addressId, boolean isDefault) {
        return Address.builder()
                .addressId(addressId)
                .user(user)
                .label("라벨" + addressId)
                .recipientName("홍길동")
                .phone("01012345678")
                .zipCode("06236")
                .streetAddress("서울시 강남구 테헤란로 " + addressId)
                .defaultAddress(isDefault)
                .build();
    }
}
