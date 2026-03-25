package ccommit.stylehub.store.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.store.dto.response.StoreResponse;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.store.repository.StoreRepository;
import ccommit.stylehub.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * STORE 역할 사용자의 스토어 생성, 조회, 소유권 검증 비즈니스 로직을 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public Store saveStore(User user, String storeName, String storeDescription) {
        if (storeRepository.existsByUserUserId(user.getUserId())) {
            throw new BusinessException(ErrorCode.STORE_ALREADY_EXISTS);
        }
        Store store = Store.create(user, storeName, storeDescription);
        return storeRepository.save(store);
    }

    /**
     * @throws BusinessException STORE_NOT_FOUND, UNAUTHORIZED_STORE_ACCESS, STORE_NOT_APPROVED
     */
    public Store findApprovedStoreByOwner(Long userId, Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        if (!store.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_STORE_ACCESS);
        }

        if (store.getStatus() != StoreStatus.APPROVED) {
            throw new BusinessException(ErrorCode.STORE_NOT_APPROVED);
        }

        return store;
    }

    @Transactional(readOnly = true)
    public StoreResponse getMyStore(Long userId) {
        Store store = storeRepository.findByUserUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        return StoreResponse.from(store);
    }
}
