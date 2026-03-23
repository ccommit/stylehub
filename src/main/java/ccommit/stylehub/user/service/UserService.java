package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.request.UserSignUpRequest;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.dto.response.UserSignUpResponse;
import ccommit.stylehub.user.entity.User;
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


    //  TODO: 글로벌 예외 처리 PR에서 커스텀 예외 도입 예정
    public UserSignUpResponse signUp(UserSignUpRequest request) {

        // BCrypt: 트랜잭션 밖에서 실행 → DB 커넥션 점유 안 함
        String hashedPassword = passwordHasher.hash(request.password());

        User savedUser;
        try {
            savedUser = Objects.requireNonNull(
                    transactionTemplate.execute(status -> {
                        userValidator.validateSignUp(request.email(), request.name());

                        User user = User.create(
                                request.name(),
                                request.email(),
                                hashedPassword,
                                request.birthDate()
                        );

                        return userRepository.save(user);
                    })
            );
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 사용 중인 이메일 또는 닉네임입니다");
        }

        return UserSignUpResponse.from(savedUser);
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
            eventPublisher.publishEvent(new LoginEvent(user.getUserId(), LocalDate.now()))
        );

        return UserLoginResponse.from(user);
    }
}
