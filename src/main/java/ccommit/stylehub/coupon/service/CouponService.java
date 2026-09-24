package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.CouponUsageResult;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.port.CouponPort;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.CouponIssueCounter;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.coupon.validator.CouponValidator;
import ccommit.stylehub.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/04/16 by WonJin - refactor: 검증 로직을 CouponValidator로 분리
 * @modified 2026/04/22 by WonJin - refactor: UserPort 의존 제거, 권한 검증·User 조회는 CouponApplicationService로 이관 (도메인 서비스는 자기 도메인만 알도록 분리)
 * @modified 2026/05/06 by WonJin - perf: 선착순 발급 동시성 메커니즘 진화 — 비관적 락 → @DistributedLock (SETNX 폴링, 측정 결과 더 나쁨) → Redis DECR + Lua atomic 채택 (락 자체 제거, 정합성 + 처리량 모두 우월). UserCoupon INSERT 는 CouponIssu
 * @modified 2026/09/15 by WonJin - fix: 비동기 저장을 동기 저장 + Redis 보상으로 전환, DB 조건부 UPDATE 로 최종 한도 보장, 매진·중복 요청은 DB 조회 전에 거절
 * @modified 2026/09/17 by WonJin - fix: 쿠폰 사용 시 할인 기준 금액을 쿠폰 유형별로 선택 (스토어 쿠폰은 발행 스토어 상품 금액만)
 * @modified 2026/09/17 by WonJin - fix: 수정·비활성화를 행 락으로 발급과 직렬화, 카운터 초기화를 행 락 안에서 DB 기준으로, 한도 도달 시 카운터 무효화, 보상 실패 시 원래 예외 보존, 관리자 재동기화 추가
 *
 * <p>
 * 쿠폰 이벤트 생성과 선착순 쿠폰 발급을 담당하는 순수 도메인 서비스이다. 소유권 검증·User 조회는 CouponApplicationService가 맡는다.
 * 선착순 발급에서 Redis 카운터는 요청을 DB 앞에서 걸러내고, 최종 발급 한도와 발급 기록은 DB가 책임진다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class CouponService implements CouponPort {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    // 초기화와 재예약 사이에 수정·재동기화·한도 도달의 무효화가 끼면 다시 비어 있을 수 있어 한 번 더 허용한다.
    // 초기화마다 이벤트 행 락 조회가 나가므로 무한히 반복하지 않고, 넘기면 503으로 응답한다.
    private static final int MAX_COUNTER_INITIALIZE_ATTEMPTS = 2;

    private final CouponEventRepository couponEventRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponValidator couponValidator;
    private final CouponIssueCounter couponIssueCounter;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    // 스토어 소유권 검증은 상위 계층에서 끝난 상태라고 가정한다.
    @Transactional
    public CouponEventResponse createStoreCouponEvent(User storeOwner, CouponEventCreateRequest request) {
        couponValidator.validateCreate(request);

        CouponEvent event = couponEventRepository.save(CouponEvent.create(
                storeOwner, request.name(), request.discountType(), request.discountValue(),
                request.minOrderAmount(), request.issueCount(), request.startedAt(), request.expiredAt()
        ));
        resetIssueCounterAfterCommit(event);

        return CouponEventResponse.from(event);
    }

    @Transactional
    public CouponEventResponse createPlatformCouponEvent(CouponEventCreateRequest request) {
        couponValidator.validateCreate(request);

        CouponEvent event = couponEventRepository.save(CouponEvent.createPlatform(
                request.name(), request.discountType(), request.discountValue(),
                request.minOrderAmount(), request.issueCount(), request.startedAt(), request.expiredAt()
        ));
        resetIssueCounterAfterCommit(event);

        return CouponEventResponse.from(event);
    }

    // 같은 쿠폰의 동시 사용을 막기 위해 UserCoupon을 비관적 락으로 읽는다.
    @Override
    @Transactional
    public CouponUsageResult useUserCoupon(Long userId, Long userCouponId, Map<Long, Integer> amountByStore) {
        UserCoupon userCoupon = userCouponRepository.findByIdWithLock(userCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_COUPON_NOT_FOUND));

        if (!userCoupon.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_USER_COUPON);
        }

        couponValidator.validateUsable(userCoupon);
        CouponEvent event = userCoupon.getCouponEvent();
        int discount = event.calculateDiscount(event.discountBaseAmount(amountByStore));
        userCoupon.markUsed();

        return new CouponUsageResult(userCoupon, discount);
    }

    // 결제 실패·주문 취소 시 보상 트랜잭션에서 호출한다. 이미 UNUSED면 그대로 둔다(멱등).
    @Override
    @Transactional
    public void restoreUserCoupon(Long userCouponId) {
        UserCoupon userCoupon = userCouponRepository.findByIdWithLock(userCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_COUPON_NOT_FOUND));
        userCoupon.markUnused();
    }

    // 비동기 저장은 처리량 이득이 크지 않았고 실패하면 성공 응답을 받고도 쿠폰이 없을 수 있어, 응답 전에 저장을 끝낸다.
    // 초과·중복 발급은 DB 조건부 UPDATE와 유니크 제약이 막고, 커밋 전 종료·보상 실패로 샌 자리는 resyncIssueCounter로 복구한다.
    public void issueCoupon(User user, Long couponEventId) {
        Long userId = user.getUserId();
        rejectIfUnavailable(couponIssueCounter.precheck(couponEventId, userId));

        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        couponValidator.validateIssuable(event);

        reserveSlot(couponEventId, event, userId);
        saveIssuance(couponEventId, event, user);
    }

    // 행 락 없이 읽으면 전체 컬럼 UPDATE가 동시 발급으로 늘어난 issued_count를 되돌려 DB 한도가 무너진다.
    // 커밋 뒤 카운터를 무효화해 새 수량으로 다시 만들게 하고, 무효화가 실패하면 같은 값으로 재수정하거나 재동기화 API로 복구한다.
    @Transactional
    public CouponEventResponse updateCouponEvent(Long couponEventId, CouponEventUpdateRequest request) {
        CouponEvent event = findEventWithLock(couponEventId);

        couponValidator.validateUpdate(event, request);

        event.update(request.issueCount(), request.minOrderAmount(),
                request.startedAt(), request.expiredAt());
        runAfterCommit(() -> invalidateCounterAfterUpdate(couponEventId));

        return CouponEventResponse.from(event);
    }

    // 행 락 없이 읽으면 전체 컬럼 UPDATE가 동시 발급으로 늘어난 issued_count를 되돌린다.
    @Transactional
    public void deactivateCouponEvent(Long couponEventId) {
        CouponEvent event = findEventWithLock(couponEventId);

        event.deactivate();
    }

    // 커밋 전 종료·보상 실패로 샌 자리를 되돌리는 관리자 복구 수단이다. 진행 중 발급과 겹쳐도 초과·중복 발급은 DB가 막는다.
    // 직후에는 이미 받은 사용자의 재요청이 DB까지 오므로, 요청이 몰리는 중에 호출하면 DB 부하가 늘어난다.
    public void resyncIssueCounter(Long couponEventId) {
        if (!couponEventRepository.existsById(couponEventId)) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        couponIssueCounter.clear(couponEventId);
    }

    // 내가 보유한 쿠폰 목록을 조회한다.
    @Transactional(readOnly = true)
    public List<UserCouponResponse> getMyCoupons(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return userCouponRepository.findByUserIdWithCouponEvent(userId)
                .stream()
                .map(uc -> UserCouponResponse.from(uc, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CouponEventResponse> getActiveCouponEvents() {
        return couponEventRepository
                .findActiveCouponEvents(LocalDateTime.now())
                .stream()
                .map(CouponEventResponse::from)
                .toList();
    }

    // 예약이 결과 없이 예외로 끝나면 실행 여부를 알 수 없어 해제를 시도한다. 해제는 멱등이고, 드물게 카운터가 커져도 DB가 초과 발급을 막는다.
    // SOLD_OUT·ALREADY_ISSUED처럼 결과를 받은 거절은 확보한 자리가 없으므로 해제하지 않는다.
    private void reserveSlot(Long couponEventId, CouponEvent event, Long userId) {
        CouponIssueCounter.Result result;
        try {
            result = reserveInitializingIfAbsent(couponEventId, event, userId);
        } catch (RuntimeException e) {
            compensate(e, couponEventId, userId, () -> couponIssueCounter.release(couponEventId, userId));
            throw e;
        }
        if (result == CouponIssueCounter.Result.NOT_INITIALIZED) {
            throw new BusinessException(ErrorCode.COUPON_ISSUE_TEMPORARILY_UNAVAILABLE);
        }
        rejectIfUnavailable(result);
    }

    private CouponIssueCounter.Result reserveInitializingIfAbsent(Long couponEventId, CouponEvent event, Long userId) {
        CouponIssueCounter.Result result = couponIssueCounter.reserve(couponEventId, userId, event.getExpiredAt());
        for (int attempt = 0;
             result == CouponIssueCounter.Result.NOT_INITIALIZED && attempt < MAX_COUNTER_INITIALIZE_ATTEMPTS;
             attempt++) {
            initializeCounterFromDatabase(couponEventId);
            result = couponIssueCounter.reserve(couponEventId, userId, event.getExpiredAt());
        }
        return result;
    }

    // 초반에 락 없이 읽은 값을 쓰면 그 사이 늘린 수량이 발급되지 않을 수 있어, 이벤트 행 락을 쥔 채 다시 읽어 SETNX 한다.
    // 수정도 같은 락을 잡고 커밋 뒤 무효화하므로 어느 순서든 오래된 값이 남지 않고, 락 점유 시간은 Redis 명령 타임아웃이 제한한다.
    private void initializeCounterFromDatabase(Long couponEventId) {
        transactionTemplate.executeWithoutResult(status -> {
            CouponEvent locked = findEventWithLock(couponEventId);
            couponIssueCounter.initializeIfAbsent(couponEventId, locked.remainingIssueCount(), locked.getExpiredAt());
        });
    }

    // 영속성 컨텍스트에 이미 있는 엔티티는 락 조회 결과로 갱신되지 않아, OSIV에서는 오래된 수량으로 카운터를 만들 수 있다.
    // 락 모드 refresh로 락 시점 값을 보게 하며, 늘어난 조회는 카운터 초기화와 관리자 수정·비활성화에서만 나간다.
    private CouponEvent findEventWithLock(Long couponEventId) {
        CouponEvent event = couponEventRepository.findByIdWithLock(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);
        return event;
    }

    // 조건부 증가와 발급 기록 INSERT를 한 트랜잭션으로 묶어 issued_count와 user_coupons 행 수를 맞춘다.
    // INSERT를 먼저 하면 외래 키 검사로 잡은 부모 행 공유 락을 배타 락으로 올리려다 교착되므로 UPDATE를 먼저 한다.
    private void saveIssuance(Long couponEventId, CouponEvent event, User user) {
        Long userId = user.getUserId();
        Boolean issued;
        try {
            issued = transactionTemplate.execute(status -> {
                if (couponEventRepository.increaseIssuedCount(couponEventId) == 0) {
                    return false;
                }
                userCouponRepository.save(UserCoupon.create(user, event));
                return true;
            });
        } catch (DataIntegrityViolationException e) {
            throw resolveIntegrityViolation(couponEventId, event, userId, e);
        } catch (RuntimeException e) {
            compensate(e, couponEventId, userId, () -> couponIssueCounter.release(couponEventId, userId));
            throw e;
        }

        if (!Boolean.TRUE.equals(issued)) {
            BusinessException soldOut = new BusinessException(ErrorCode.COUPON_SOLD_OUT);
            compensate(soldOut, couponEventId, userId,
                    () -> couponIssueCounter.invalidateOnLimitReached(couponEventId, userId));
            throw soldOut;
        }
    }

    // 외래 키 위반 등과 구분하려고 발급 이력을 확인하고, 이미 받은 사용자면 Redis 발급자 기록을 다시 채운다.
    // 확인 쿼리마저 실패하면 판단할 수 없으므로 일반 실패처럼 자리를 돌려주고 원래 예외를 던진다.
    private RuntimeException resolveIntegrityViolation(Long couponEventId, CouponEvent event, Long userId,
                                                       DataIntegrityViolationException violation) {
        boolean alreadyIssued;
        try {
            alreadyIssued = userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(userId, couponEventId);
        } catch (RuntimeException lookupFailure) {
            violation.addSuppressed(lookupFailure);
            compensate(violation, couponEventId, userId, () -> couponIssueCounter.release(couponEventId, userId));
            return violation;
        }

        if (alreadyIssued) {
            BusinessException duplicate = new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
            compensate(duplicate, couponEventId, userId,
                    () -> couponIssueCounter.releaseKeepingIssued(couponEventId, userId, event.getExpiredAt()));
            return duplicate;
        }
        compensate(violation, couponEventId, userId, () -> couponIssueCounter.release(couponEventId, userId));
        return violation;
    }

    // 보상이 실패해도 원래 실패 원인을 돌려주고, 자리 누수를 찾아 복구할 수 있게 couponEventId, userId를 ERROR로 남긴다.
    private void compensate(RuntimeException original, Long couponEventId, Long userId, Runnable compensation) {
        try {
            compensation.run();
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
            log.error("선착순 쿠폰 Redis 보상 실패 — 자리 누수 가능, 관리자 재동기화 API 로 복구 필요. couponEventId={}, userId={}",
                    couponEventId, userId, compensationFailure);
        }
    }

    private void rejectIfUnavailable(CouponIssueCounter.Result result) {
        if (result == CouponIssueCounter.Result.SOLD_OUT) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
        if (result == CouponIssueCounter.Result.ALREADY_ISSUED) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }
    }

    private void invalidateCounterAfterUpdate(Long couponEventId) {
        try {
            couponIssueCounter.invalidate(couponEventId);
        } catch (RuntimeException e) {
            log.error("쿠폰 이벤트 수정은 커밋됐지만 발급 카운터 무효화 실패 — 같은 값으로 다시 수정하거나 재동기화 API 로 복구 필요. couponEventId={}",
                    couponEventId, e);
            throw e;
        }
    }

    // 카운터 초기화가 실패해도 생성은 성공으로 둔다. 첫 발급 요청이 DB 기준으로 만들고, 실패로 응답하면 재시도로 이벤트가 중복 생성된다.
    private void resetIssueCounterAfterCommit(CouponEvent event) {
        Long couponEventId = event.getCouponEventId();
        int issueCount = event.getIssueCount();
        LocalDateTime expiredAt = event.getExpiredAt();
        runAfterCommit(() -> {
            try {
                couponIssueCounter.reset(couponEventId, issueCount, expiredAt);
            } catch (RuntimeException e) {
                log.error("쿠폰 이벤트는 생성됐지만 발급 카운터 초기화 실패 — 첫 발급 요청이 DB 기준으로 다시 만든다. couponEventId={}",
                        couponEventId, e);
            }
        });
    }

    // 롤백된 변경이 Redis 에 반영되지 않도록, 카운터 변경은 DB 커밋이 끝난 뒤에 한다.
    private void runAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
