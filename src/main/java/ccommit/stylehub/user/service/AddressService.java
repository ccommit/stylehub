package ccommit.stylehub.user.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.request.AddressCreateRequest;
import ccommit.stylehub.user.dto.response.AddressResponse;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.repository.AddressRepository;
import ccommit.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 배송지 등록·조회·기본 배송지 변경·삭제를 담당하는 user 도메인 서비스이다.
 * 변경 작업은 모두 사용자 행을 잠근 뒤 처리해, 같은 사용자의 동시 요청에서도 최대 개수와 기본 배송지 1개 규칙이 깨지지 않게 한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    // ErrorCode.ADDRESS_LIMIT_EXCEEDED 메시지의 "5개"와 함께 바꿔야 한다
    static final int MAX_ADDRESS_COUNT = 5;

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    // 개수 확인과 저장 사이에 다른 요청이 끼어들면 5개를 넘을 수 있어, 사용자 행을 먼저 잠가 같은 사용자의 요청을 직렬화한다.
    // 기본 배송지가 없으면 새 배송지를 기본으로 지정해, API 도입 전 기본 없이 저장된 데이터도 다음 등록에서 회복된다.
    @Transactional
    public AddressResponse registerAddress(Long userId, AddressCreateRequest request) {
        User user = lockUser(userId);
        List<Address> addresses = addressRepository.findAllByUserIdOrderByDefaultFirst(userId);

        if (addresses.size() >= MAX_ADDRESS_COUNT) {
            throw new BusinessException(ErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }

        Address address = Address.create(user, request.label(), request.recipientName(), request.phone(),
                request.zipCode(), request.streetAddress(), request.detailAddress());

        if (addresses.stream().noneMatch(Address::isDefault)) {
            address.markAsDefault();
        }

        return AddressResponse.from(addressRepository.save(address));
    }

    // 조회는 규칙을 바꾸지 않으므로 잠그지 않는다. 변경 트랜잭션이 커밋한 결과만 읽는다.
    @Transactional(readOnly = true)
    public List<AddressResponse> getAddresses(Long userId) {
        return addressRepository.findAllByUserIdOrderByDefaultFirst(userId).stream()
                .map(AddressResponse::from)
                .toList();
    }

    // 잠그지 않으면 두 요청이 서로 다른 배송지를 기본으로 지정하며 각자 기존 기본만 해제해 기본이 2개로 남을 수 있다.
    @Transactional
    public AddressResponse changeDefaultAddress(Long userId, Long addressId) {
        lockUser(userId);
        List<Address> addresses = addressRepository.findAllByUserIdOrderByDefaultFirst(userId);
        Address target = findOwnedAddress(addresses, addressId);

        addresses.stream()
                .filter(address -> address != target)
                .forEach(Address::unmarkDefault);
        target.markAsDefault();

        return AddressResponse.from(target);
    }

    // 주문 여부를 미리 조회하면 order 도메인과 순환 의존이 생기고 최종 판정도 FK가 하므로, 즉시 flush해 FK 위반을 ADDRESS_IN_USE로 바꾼다.
    // 승계 지정보다 먼저 flush해 삭제가 거절될 때 승계 UPDATE가 섞여 나가지 않게 한다.
    @Transactional
    public void deleteAddress(Long userId, Long addressId) {
        lockUser(userId);
        List<Address> addresses = addressRepository.findAllByUserIdOrderByDefaultFirst(userId);
        Address target = findOwnedAddress(addresses, addressId);

        deleteOrThrowInUse(target);

        if (target.isDefault()) {
            addresses.stream()
                    .filter(address -> address != target)
                    .max(Comparator.comparing(Address::getAddressId))
                    .ifPresent(Address::markAsDefault);
        }
    }

    private User lockUser(Long userId) {
        return userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // 본인 목록 안에서만 찾는다. 남의 배송지와 없는 배송지를 같은 404 로 응답해 다른 사용자의 addressId 존재 여부를 드러내지 않는다.
    private Address findOwnedAddress(List<Address> addresses, Long addressId) {
        return addresses.stream()
                .filter(address -> Objects.equals(address.getAddressId(), addressId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
    }

    private void deleteOrThrowInUse(Address target) {
        try {
            addressRepository.delete(target);
            addressRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ADDRESS_IN_USE);
        }
    }
}
