package ccommit.stylehub.point.event;

import ccommit.stylehub.point.service.PointRewardService;
import ccommit.stylehub.user.event.LoginEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author WonJin Bae
 * @created 2026/03/21
 *
 * <p>
 * 로그인 이벤트를 수신하여 포인트를 지급한다.
 * user 도메인과 point 도메인 간의 결합을 이벤트로 분리한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class LoginEventListener {

    private final PointRewardService pointRewardService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleLoginEvent(LoginEvent event) {
        pointRewardService.rewardLoginPoint(event.userId(), event.loginDate());
    }
}
