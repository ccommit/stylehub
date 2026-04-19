package ccommit.stylehub.store.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.store.dto.request.StoreSignUpRequest;
import ccommit.stylehub.store.dto.response.StoreSignUpResponse;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * @author WonJin Bae
 * @created 2026/03/26
 *
 * <p>
 * 스토어 회원가입 + 입점 신청을 하나의 트랜잭션으로 묶는 Facade이다.
 * BCrypt 해싱은 트랜잭션 밖에서, User + Store 저장은 트랜잭션 안에서 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class StoreSignUpFacade {

    private final UserService userService;
    private final StoreService storeService;
    private final TransactionTemplate transactionTemplate;

    public StoreSignUpResponse signUpWithStore(StoreSignUpRequest request) {
        // BCrypt: 트랜잭션 밖에서 실행 → DB 커넥션 점유 안 함
        String hashedPassword = userService.hashPassword(request.password());

        record SignUpResult(User user, Store store) {}

        SignUpResult result;
        try {
            result = Objects.requireNonNull(
                    transactionTemplate.execute(status -> {
                        User user = userService.saveUser(
                                request.name(), request.email(), hashedPassword, null, UserRole.STORE
                        );
                        Store store = storeService.saveStore(
                                user, request.storeName(), request.storeDescription()
                        );
                        return new SignUpResult(user, store);
                    })
            );
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL_OR_NAME);
        }

        return StoreSignUpResponse.from(result.user(), result.store());
    }
}
