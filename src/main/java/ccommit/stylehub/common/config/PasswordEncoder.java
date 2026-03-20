package ccommit.stylehub.common.config;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

@Component
public class PasswordEncoder {

    public String encode(String password) {
        return BCrypt.withDefaults().hashToString(10, password.toCharArray());
    }

    public boolean matches(String password, String hashedPassword) {
        return BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified;
    }
}
