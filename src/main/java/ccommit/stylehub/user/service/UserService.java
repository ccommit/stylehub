package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.event.LoginEvent;
import ccommit.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Objects;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/25 by WonJin - feat: STORE 역할 회원 생성 메서드 추가
 * @modified 2026/03/26 by WonJin - refactor: 해싱과 저장 분리로 외부 트랜잭션 참여 지원
 *
 * <p>
 * 일반 회원가입과 로그인의 비즈니스 로직을 처리한다.
 * BCrypt 해싱을 트랜잭션 밖에서 실행하여 커넥션 점유를 최소화한다.
 * </p>
 */

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final UserValidator userValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;


    public User signUp(String name, String email, String password, LocalDate birthDate, UserRole role) {
        String hashedPassword = hashPassword(password);

        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status ->
                            saveUser(name, email, hashedPassword, birthDate, role)
                    )
            );
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL_OR_NAME);
        }
    }

    /**
     * BCrypt 해싱. 트랜잭션 밖에서 호출하여 DB 커넥션 점유를 방지한다.
     */
    public String hashPassword(String rawPassword) {
        return passwordHasher.hash(rawPassword);
    }

    /**
     * 검증 + User 저장. 트랜잭션 내에서 호출되어야 한다.
     */
    public User saveUser(String name, String email, String hashedPassword, LocalDate birthDate, UserRole role) {
        userValidator.validateSignUp(email, name);
        User user = User.create(name, email, hashedPassword, birthDate, role);
        return userRepository.save(user);
    }

    public UserLoginResponse login(UserLoginRequest request) {

        // DB 커넥션을 최소한으로 점유하기 위해 트랜잭션을 의도적으로 분리
        // BCrypt 검증(~100ms)은 트랜잭션 밖에서 처리하여 커넥션 풀 고갈 방지
        User user = Objects.requireNonNull(
                transactionTemplate.execute(status ->
                        userRepository.findByEmail(request.email())
                                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다"))
                )
        );

        if (!passwordHasher.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
        }

        transactionTemplate.executeWithoutResult(status ->
            eventPublisher.publishEvent(new LoginEvent(user.getUserId(), LocalDate.now(), user.getRole()))
        );

        return UserLoginResponse.from(user);
    }
}
