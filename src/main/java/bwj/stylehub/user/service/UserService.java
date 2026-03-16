package bwj.stylehub.user.service;

import bwj.stylehub.common.config.PasswordEncoder;
import bwj.stylehub.user.dto.request.UserLoginRequest;
import bwj.stylehub.user.dto.request.UserSignUpRequest;
import bwj.stylehub.user.dto.response.UserLoginResponse;
import bwj.stylehub.user.dto.response.UserSignUpResponse;
import bwj.stylehub.user.entity.User;
import bwj.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserValidator userValidator;
    private final TransactionTemplate transactionTemplate;


    //  TODO: 글로벌 예외 처리 PR에서 커스텀 예외 도입 예정
    public UserSignUpResponse signUp(UserSignUpRequest request) {

        // BCrypt: 트랜잭션 밖에서 실행 → DB 커넥션 점유 안 함
        String encodedPassword = passwordEncoder.encode(request.password());

        User savedUser;
        try {
            savedUser = Objects.requireNonNull(
                    transactionTemplate.execute(status -> {
                        userValidator.validateSignUp(request.email(), request.name());

                        User user = User.create(
                                request.name(),
                                request.email(),
                                encodedPassword,
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

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
        }

        // 트랜잭션 1에서 조회한 user는 준영속 상태이므로 다시 조회하여 영속 상태로 만든다
        transactionTemplate.executeWithoutResult(status -> {
            User managedUser = userRepository.findById(user.getUserId()).orElseThrow();
            managedUser.rewardLoginPoint(LocalDate.now());
        });

        return UserLoginResponse.from(user);
    }
}
