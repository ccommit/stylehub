package ccommit.stylehub.common.config;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * @author WonJin Bae
 * @created 2026/03/21
 * @modified 2026/09/17 by WonJin - fix: 로그인 실패 경로의 응답 시간을 맞추는 더미 해시 검증 추가, 72바이트 초과 비밀번호 검증 시 IllegalArgumentException(500) 대신 불일치 처리
 *
 * <p>
 * BCrypt 로 비밀번호를 해싱하고 검증한다.
 * 계정이 없을 때도 같은 비용의 검증을 한 번 수행할 수 있게 해, 응답 시간으로 가입 여부가 드러나지 않게 한다.
 * </p>
 */
@Component
public class PasswordHasher {

    private static final int COST = 10;

    // BCrypt 는 72바이트까지만 입력으로 쓰고, 이 라이브러리의 기본 전략은 초과 입력에 IllegalArgumentException 을 던진다.
    // 가입 시 비밀번호는 15자 이하로 검증되므로 72바이트를 넘는 입력은 어떤 저장된 해시와도 일치할 수 없다.
    private static final int MAX_PASSWORD_BYTES = 72;

    // 기동 시(빈 생성 시) 한 번만 만든다. 검증 결과를 쓰지 않으므로 평문은 임의 값이면 된다.
    // 재정의 가능한 hash() 를 쓰지 않는 것은, 생성 중에 호출되면 하위 클래스의 필드가 아직 초기화되지 않았기 때문이다.
    private final String dummyHash = BCrypt.withDefaults().hashToString(COST, UUID.randomUUID().toString().toCharArray());

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
    }

    public boolean matches(String password, String hashedPassword) {
        return verify(password, hashedPassword);
    }

    // 미존재 이메일·비밀번호 없는 계정처럼 실제 검증을 건너뛰는 실패 경로에서 호출해 BCrypt 비용을 같게 맞춘다
    public void verifyDummy(String password) {
        verify(password, dummyHash);
    }

    private boolean verify(String password, String hashedPassword) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return false;
        }
        return BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified;
    }
}
