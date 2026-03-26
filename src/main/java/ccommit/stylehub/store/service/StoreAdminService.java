package ccommit.stylehub.store.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.store.dto.response.StoreResponse;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * ADMIN 역할의 입점 신청 목록 조회, 승인, 거절, 정지 비즈니스 로직을 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class StoreAdminService {

    private final StoreRepository storeRepository;
    private final TransactionTemplate transactionTemplate;

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
