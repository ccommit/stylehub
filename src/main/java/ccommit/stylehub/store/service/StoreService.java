package ccommit.stylehub.store.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.store.dto.response.StoreResponse;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.store.repository.StoreRepository;
import ccommit.stylehub.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/26 by WonJin - refactor: saveStore에 @Transactional 적용, 동시 요청 중복 생성 방어 추가
 * @modified 2026/04/02 by WonJin - refactor: StoreAdminService를 StoreService로 통합
 *
 * <p>
 * 스토어 생성, 조회, 소유권 검증, 입점 관리(승인/거절/정지), 찜하기를 담당한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public Store saveStore(User user, String storeName, String storeDescription) {
        if (storeRepository.existsByUserUserId(user.getUserId())) {
            throw new BusinessException(ErrorCode.STORE_ALREADY_EXISTS);
        }
        try {
            Store store = Store.create(user, storeName, storeDescription);
            return storeRepository.save(store);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.STORE_ALREADY_EXISTS);
        }
    }

    /**
     * 승인된 스토어의 소유권을 검증한다. 검증 실패 시 예외를 던진다.
     */
    public void validateApprovedStoreOwner(Long userId, Long storeId) {
        findApprovedStoreByOwner(userId, storeId);
    }

    /**
     * 승인된 스토어를 소유권 검증 후 반환한다. Store 객체가 필요한 경우 사용한다.
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

    // === 입점 관리 (ADMIN) ===
    @Transactional(readOnly = true)
    public List<StoreResponse> getStoresByStatus(StoreStatus status) {
        List<Store> stores = (status != null)
                ? storeRepository.findByStatus(status)
                : storeRepository.findAll();

        return stores.stream()
                .map(StoreResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId) {
        Store store = findStoreById(storeId);
        return StoreResponse.from(store);
    }

    public StoreResponse approve(Long storeId) {
        return changeStatus(storeId, Store::approve);
    }

    public StoreResponse reject(Long storeId) {
        return changeStatus(storeId, Store::reject);
    }

    public StoreResponse suspend(Long storeId) {
        return changeStatus(storeId, Store::suspend);
    }

    private StoreResponse changeStatus(Long storeId, Consumer<Store> action) {
        Store store = Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    Store target = findStoreById(storeId);
                    action.accept(target);
                    return target;
                })
        );
        return StoreResponse.from(store);
    }

    private Store findStoreById(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

}
