package ccommit.stylehub.common.config;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(10, password.toCharArray());
    }

    public boolean matches(String password, String hashedPassword) {
        return BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified;
    }
}
