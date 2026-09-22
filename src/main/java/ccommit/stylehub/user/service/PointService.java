package ccommit.stylehub.user.service;

import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.user.dto.response.MyPointResponse;
import ccommit.stylehub.user.dto.response.PointHistoryResponse;
import ccommit.stylehub.user.entity.PointHistory;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.PointType;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.LoginPointState;
import ccommit.stylehub.user.repository.PointHistoryQueryRepository;
import ccommit.stylehub.user.repository.PointHistoryRepository;
import ccommit.stylehub.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 포인트 잔액 변경(로그인 적립, 주문 사용, 주문 취소 복구)과 이력 기록, 보유 포인트 조회를 담당하는 user 도메인 서비스이다.
 * 잔액은 조건부 원자 UPDATE 로만 바꾸고, 이력의 잔여 포인트는 UPDATE 직후 DB 에서 스칼라로 다시 읽어 동시 변경에서도 틀어지지 않게 한다.
 * </p>
 *
 * <p>외부 도메인(order)은 이 서비스를 직접 호출하지 않고 UserPort(UserService)를 거친다.
 * 잔액 변경 메서드는 호출자 트랜잭션에 참여해, 주문 생성·취소가 롤백되면 차감·복구와 이력도 함께 롤백된다.
 *
 * <p><b>락 순서</b> 포인트를 쓰는 주문 생성과 취소는 모두 사용자 행 → 재고 옵션 행 → 보유 쿠폰 행 순서로 잠근다.
 * 주문 생성에서 차감을 주문 INSERT 보다 앞에 두는 이유는 OrderService.placeOrder 설명을 참고한다.
 */
@Service
@RequiredArgsConstructor
public class PointService {

    static final int FIRST_LOGIN_POINT = 1000;
    static final int DAILY_LOGIN_POINT = 10;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final PointHistoryQueryRepository pointHistoryQueryRepository;
    private final EntityManager entityManager;

    // 첫 로그인 WELCOME 1000P, 이후 하루 1회 DAILY_LOGIN 10P.
    // 적립 여부는 조건부 UPDATE 한 문장이 확정한다. 앞의 조회는 어느 UPDATE 를 실행할지 고르는 것이라 동시 로그인이 N 건이어도 적립은 한 번이다.
    // 엔티티를 읽어 더한 뒤 저장하던 이전 구현은 그 사이 커밋된 주문 포인트 차감을 전체 컬럼 UPDATE 로 덮어썼다.
    @Transactional
    public void rewardLoginPoint(Long userId, LocalDate today) {
        LoginPointState state = userRepository.findLoginPointState(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (state.role() == UserRole.ADMIN) {
            return;
        }

        if (state.lastLoginDate() == null) {
            if (userRepository.rewardFirstLoginPoint(userId, FIRST_LOGIN_POINT, today) == 1) {
                recordHistory(PointHistory.create(userReference(userId), PointType.WELCOME,
                        FIRST_LOGIN_POINT, currentBalance(userId)));
            }
            return;
        }

        if (state.lastLoginDate().isBefore(today)
                && userRepository.rewardDailyLoginPoint(userId, DAILY_LOGIN_POINT, today) == 1) {
            recordHistory(PointHistory.create(userReference(userId), PointType.DAILY_LOGIN,
                    DAILY_LOGIN_POINT, currentBalance(userId)));
        }
    }

    // 포인트 사용 1단계 — 조건부 UPDATE 한 문장으로 확인과 차감을 함께 한다. 0 건이면 잔액 부족으로 거절한다.
    // 이 UPDATE 가 잡은 사용자 행 배타 락은 커밋까지 유지되므로 같은 트랜잭션의 recordPointUse 는 차감 직후 잔액을 읽는다.
    @Transactional
    public void deductPoint(Long userId, int amount) {
        validatePositive(amount);
        if (userRepository.deductPointIfEnough(userId, amount) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }
    }

    // 포인트 사용 2단계 — 사용 이력(USE, 음수)을 남긴다. 같은 트랜잭션에서 deductPoint 를 먼저 호출해야 한다.
    @Transactional
    public void recordPointUse(Long userId, Long orderId, int amount) {
        validatePositive(amount);
        recordHistory(PointHistory.ofUse(userReference(userId), orderReference(orderId), amount, currentBalance(userId)));
    }

    // 중복 복구 방지는 호출자의 주문 상태 전이가 맡는다. 주문 행을 잠그고 취소 가능 상태일 때만 복구를 호출하므로 두 번 들어오지 않는다.
    @Transactional
    public void restoreUsedPoint(Long userId, Long orderId, int amount) {
        validatePositive(amount);
        if (userRepository.addPoint(userId, amount) == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        recordHistory(PointHistory.ofUseCancel(userReference(userId), orderReference(orderId), amount, currentBalance(userId)));
    }

    // 보유 포인트와 이력을 최신순 커서 페이징으로 조회한다. 기본 20건, 최대 100건.
    @Transactional(readOnly = true)
    public MyPointResponse getMyPoints(Long userId, Long cursor, Integer size) {
        int pageSize = resolvePageSize(size);

        List<PointHistoryResponse> histories =
                pointHistoryQueryRepository.findMyHistoriesWithCursor(userId, cursor, pageSize + 1);

        return new MyPointResponse(currentBalance(userId),
                CursorResponse.of(histories, pageSize, PointHistoryResponse::pointId));
    }

    private int resolvePageSize(Integer size) {
        return (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    }

    // 0 이하 금액은 잔액을 바꾸지 않거나 부호를 뒤집는다. 호출자 검증을 통과했더라도 여기서 한 번 더 막는다.
    private void validatePositive(int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    // 잔액을 스칼라로 읽는다. 변경 경로에서는 원자 UPDATE 뒤 같은 트랜잭션에서 읽으므로, UPDATE 가 잡은 행 배타 락이 커밋까지 유지되는 동안
    // 다른 트랜잭션이 끼어들지 못해 이 값이 이번 변경 직후의 잔액이다. 엔티티(User.pointBalance)로 읽으면 먼저 로딩된 오래된 값일 수 있다.
    private int currentBalance(Long userId) {
        Integer balance = userRepository.findPointBalance(userId);
        if (balance == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return balance;
    }

    private void recordHistory(PointHistory history) {
        pointHistoryRepository.save(history);
    }

    // 이력의 외래키 설정용 참조다. 조회 쿼리를 만들지 않고, 이미 영속성 컨텍스트에 있으면 그 엔티티를 쓴다(잔액 값은 읽지 않는다).
    private User userReference(Long userId) {
        return userRepository.getReferenceById(userId);
    }

    // PaymentService.createReady 와 같이 order 엔티티를 넘겨받지 않고 ID 로 FK 참조만 얻는다.
    private Order orderReference(Long orderId) {
        return entityManager.getReference(Order.class, orderId);
    }
}
