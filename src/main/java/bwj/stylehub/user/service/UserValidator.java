package bwj.stylehub.user.service;

import bwj.stylehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// TODO: 글로벌 예외 처리 PR에서 커스텀 예외(BusinessException 등) 도입 예정
@Component
@RequiredArgsConstructor
public class UserValidator {

    private final UserRepository userRepository;

    public void validateSignUp(String email, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다");
        }

        if (userRepository.existsByName(name)) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다");
        }
    }
}
