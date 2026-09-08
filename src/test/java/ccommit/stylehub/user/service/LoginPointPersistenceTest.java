package ccommit.stylehub.user.service;

import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/05
 *
 * <p>
 * 로그인 포인트 적립이 DB에 실제로 반영되는지 검증하는 회귀 테스트이다.
 *
 * <b>@SpringBootTest 를 쓰는 이유</b>
 * 이 결함은 목 객체로는 재현되지 않는다. 리포지토리를 대체하면 변경 감지 자체가 일어나지 않아
 * 트랜잭션이 열렸는지 아닌지를 구분할 수 없기 때문이다.
 * 실제 영속성 컨텍스트와 트랜잭션 커밋 타이밍이 살아 있어야 "어노테이션은 붙어 있는데 트랜잭션이
 * 걸리지 않아 UPDATE 가 나가지 않는" 상태를 노출할 수 있다.
 *
 * <b>무엇을 막는가</b>
 * 적립 메서드가 같은 클래스 안에서 호출되면(self-invocation) 프록시를 거치지 않아
 * @Transactional 이 무시되고, 포인트가 예외 없이 조용히 유실된다.
 * 소셜 로그인에서 한 번 겪은 결함이라 일반 로그인 경로에도 같은 기준을 적용해 고정한다.
 * 검증은 반드시 DB 에서 다시 읽어서 한다. 반환된 응답이나 메모리상의 엔티티를 보면
 * 실제로 UPDATE 가 나갔는지 알 수 없다.
 * </p>
 */
@SpringBootTest
class LoginPointPersistenceTest {

    private static final int FIRST_LOGIN_POINT = 1000;
    private static final int DAILY_LOGIN_POINT = 10;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    /** 테스트가 만든 회원을 끝나고 지운다. 지우지 않으면 실행할 때마다 DB 에 행이 쌓인다. */
    private final List<Long> createdUserIds = new ArrayList<>();

    /**
     * 날짜를 테스트마다 한 번만 구해 재사용한다.
     * LocalDate.now() 를 여러 번 부르면 자정을 걸칠 때 값이 달라져 테스트가 흔들린다.
     */
    private final LocalDate today = LocalDate.now();

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("일반 로그인(같은 클래스 내부 호출)으로 적립한 포인트가 DB에 반영된다")
    void persistsPoint_whenRewardedThroughLogin() {
        // given — 최초 로그인 상태의 회원
        User signedUp = signUpUser();
        Long userId = signedUp.getUserId();

        assertThat(reload(userId).getPointBalance()).isZero();
        assertThat(reload(userId).getLastLoginDate()).isNull();

        // when — login() 이 내부적으로 rewardLoginPoint() 를 호출한다
        userService.login(new UserLoginRequest(signedUp.getEmail(), rawPassword()));

        // then — 응답이 아니라 DB 를 다시 읽어 확인한다
        User reloaded = reload(userId);
        assertThat(reloaded.getPointBalance()).isEqualTo(FIRST_LOGIN_POINT);
        assertThat(reloaded.getLastLoginDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("외부에서 적립 메서드를 직접 호출해도 DB에 반영된다 (소셜 로그인과 동일한 경로)")
    void persistsPoint_whenRewardedFromOutside() {
        // given
        User signedUp = signUpUser();
        Long userId = signedUp.getUserId();

        // when — OAuthService 가 호출하는 것과 같은 형태의 외부 호출
        userService.rewardLoginPoint(userId, today);

        // then
        User reloaded = reload(userId);
        assertThat(reloaded.getPointBalance()).isEqualTo(FIRST_LOGIN_POINT);
        assertThat(reloaded.getLastLoginDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("같은 날 두 번째 로그인은 중복 적립하지 않는다")
    void doesNotRewardTwice_onSameDay() {
        // given — 오늘 이미 한 번 적립된 상태
        User signedUp = signUpUser();
        Long userId = signedUp.getUserId();
        userService.rewardLoginPoint(userId, today);
        int afterFirst = reload(userId).getPointBalance();

        // when — 같은 날 다시 로그인
        userService.login(new UserLoginRequest(signedUp.getEmail(), rawPassword()));

        // then — 잔액이 그대로여야 한다
        assertThat(reload(userId).getPointBalance()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("날짜가 바뀌면 일일 로그인 포인트가 추가로 적립된다")
    void rewardsDailyPoint_whenDateChanged() {
        // given — 어제 적립된 상태
        User signedUp = signUpUser();
        Long userId = signedUp.getUserId();
        userService.rewardLoginPoint(userId, today.minusDays(1));
        int afterYesterday = reload(userId).getPointBalance();

        // when — 오늘 다시 적립
        userService.rewardLoginPoint(userId, today);

        // then
        assertThat(reload(userId).getPointBalance()).isEqualTo(afterYesterday + DAILY_LOGIN_POINT);
        assertThat(reload(userId).getLastLoginDate()).isEqualTo(today);
    }

    // ===== Helper =====

    /** 매 실행마다 중복되지 않는 회원을 만든다. */
    private User signUpUser() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userService.signUp(
                "포인트테스트-" + unique,
                "point-" + unique + "@test.com",
                rawPassword(),
                LocalDate.of(1996, 1, 1),
                UserRole.USER
        );
        createdUserIds.add(user.getUserId());
        return user;
    }

    private String rawPassword() {
        return "Test1234!";
    }

    /** 영속성 컨텍스트가 아니라 DB 에서 다시 읽는다. */
    private User reload(Long userId) {
        return userRepository.findById(userId).orElseThrow();
    }
}
