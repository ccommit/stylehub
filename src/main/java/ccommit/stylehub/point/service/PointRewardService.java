package ccommit.stylehub.point.service;

import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * @author WonJin Bae
 * @created 2026/03/21
 *
 * <p>
 * 로그인 포인트 지급 정책을 판단하고 지급한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PointRewardService {

    private static final int FIRST_LOGIN_POINT = 1000;
    private static final int DAILY_LOGIN_POINT = 10;

    private final UserRepository userRepository;

    @Transactional
    public void rewardLoginPoint(Long userId, LocalDate today) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다"));

        if (user.getRole() == UserRole.ADMIN) {
            return;
        }

        if (user.getLastLoginDate() == null) {
            user.addPoint(FIRST_LOGIN_POINT);
        } else if (!user.getLastLoginDate().equals(today)) {
            user.addPoint(DAILY_LOGIN_POINT);
        }
        user.updateLastLoginDate(today);
    }
}
