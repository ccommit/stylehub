package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.coupon.validator.CouponValidator;
import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.service.StoreService;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/04/16 by WonJin - refactor: 검증 로직을 CouponValidator로 분리
 *
 * <p>
 * 쿠폰 이벤트 생성과 선착순 쿠폰 발급을 담당한다.
 * 선착순 발급은 비관적 락(SELECT FOR UPDATE)으로 수량 정합성을 보장한다.
 * 검증은 CouponValidator, PG/스토어 조회는 StoreService에 위임한다.
 * </p>
 */
// TODO: 성능 테스트 후 분산락 적용예정
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponEventRepository couponEventRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserRepository userRepository;
    private final StoreService storeService;
    private final CouponValidator couponValidator;

    /**
     * 스토어 쿠폰 이벤트를 생성한다.
     * 소유권 검증 후 이벤트를 DB에 저장한다.
     */
    @Transactional
    public CouponEventResponse createStoreCouponEvent(Long userId, Long storeId,
                                                      CouponEventCreateRequest request) {
        Store store = storeService.findApprovedStoreByOwner(userId, storeId);
        couponValidator.validateCreate(request);

        CouponEvent event = couponEventRepository.save(CouponEvent.create(
                store, request.name(), request.discountType(), request.discountValue(),
                request.minOrderAmount(), request.issueCount(), request.startedAt(), request.expiredAt()
        ));

        return CouponEventResponse.from(event);
    }

    /**
     * 플랫폼(관리자) 쿠폰 이벤트를 생성한다.
     */
    @Transactional
    public CouponEventResponse createPlatformCouponEvent(CouponEventCreateRequest request) {
        couponValidator.validateCreate(request);

        CouponEvent event = couponEventRepository.save(CouponEvent.createPlatform(
                request.name(), request.discountType(), request.discountValue(),
                request.minOrderAmount(), request.issueCount(), request.startedAt(), request.expiredAt()
        ));

        return CouponEventResponse.from(event);
    }

    /**
     * 선착순 쿠폰을 발급한다.
     * 비관적 락(SELECT FOR UPDATE)으로 CouponEvent를 잠근 뒤 수량을 차감한다.
     * DB UNIQUE 제약으로 중복 발급을 방지한다.
     */
    @Transactional
    public void issueCoupon(Long userId, Long couponEventId) {
        CouponEvent event = couponEventRepository.findByIdWithLock(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        couponValidator.validateIssuable(event);
        checkDuplicateIssue(userId, couponEventId);

        event.increaseIssuedCount();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        userCouponRepository.save(UserCoupon.create(user, event));
    }

    /**
     * 쿠폰 이벤트를 수정한다. 이미 발급된 수량보다 적게 변경할 수 없다.
     */
    @Transactional
    public CouponEventResponse updateCouponEvent(Long couponEventId, CouponEventUpdateRequest request) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        couponValidator.validateUpdate(event, request);

        event.update(request.issueCount(), request.minOrderAmount(),
                request.startedAt(), request.expiredAt());

        return CouponEventResponse.from(event);
    }

    // 쿠폰 이벤트를 비활성화하여 발급을 중단한다.
    @Transactional
    public void deactivateCouponEvent(Long couponEventId) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        event.deactivate();
    }

    private void checkDuplicateIssue(Long userId, Long couponEventId) {
        if (userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(userId, couponEventId)) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }
    }

    // 내가 보유한 쿠폰 목록을 조회한다.
    @Transactional(readOnly = true)
    public List<UserCouponResponse> getMyCoupons(Long userId) {
        return userCouponRepository.findByUserIdWithCouponEvent(userId)
                .stream()
                .map(UserCouponResponse::from)
                .toList();
    }

    /**
     * 현재 발급 가능한 쿠폰 이벤트 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<CouponEventResponse> getActiveCouponEvents() {
        return couponEventRepository
                .findActiveCouponEvents(LocalDateTime.now())
                .stream()
                .map(CouponEventResponse::from)
                .toList();
    }

}
