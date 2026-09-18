package ccommit.stylehub.user.service;

import ccommit.stylehub.common.constants.ValidationPatterns;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 소셜 제공자의 표시 이름을 일반 가입과 같은 닉네임 규칙(한글·영문·숫자, 2~10자)에 맞추고, 이미 쓰이는 이름이면 랜덤 접미사를 붙여 고유한 닉네임을 만든다.
 * 표시 이름은 사용자가 고른 값이 아니므로 규칙 위반이나 중복을 가입 실패로 돌려주지 않고 서버가 보정한다.
 * </p>
 */
@Component
public class SocialNicknameGenerator {

    // 정규화 결과가 최소 길이에 못 미칠 때(한자·이모지만 있는 이름 등) 쓰는 기본 이름이다. 그대로 쓰면 곧바로 겹치므로 항상 접미사를 붙인다.
    static final String DEFAULT_PREFIX = "user";
    static final int SUFFIX_LENGTH = 4;
    // 4자리 접미사(36^4 가지)가 연속으로 겹칠 가능성은 낮다. 횟수를 제한해 이름 조회 쿼리가 무한정 늘지 않게 한다.
    static final int MAX_SUFFIX_ATTEMPTS = 5;

    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^" + ValidationPatterns.NAME_ALLOWED_CHARS + "]");
    // NAME_ALLOWED_CHARS 에 속하는 문자만 써야 접미사를 붙인 결과도 규칙을 만족한다.
    private static final String SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    public String generate(String displayName, Predicate<String> nameTaken) {
        String base = normalize(displayName);
        if (base != null && !nameTaken.test(base)) {
            return base;
        }
        return withRandomSuffix(base, nameTaken);
    }

    // 이름 조회와 저장 사이에 다른 가입이 같은 이름을 선점한 경우처럼, 접미사 없는 이름이 이미 실패했을 때 쓴다
    public String generateWithSuffix(String displayName, Predicate<String> nameTaken) {
        return withRandomSuffix(normalize(displayName), nameTaken);
    }

    // 허용 문자만 남기고 최대 길이로 자른다. 최소 길이에 못 미치면 null 을 돌려 기본 이름을 쓰게 한다.
    private String normalize(String displayName) {
        if (displayName == null) {
            return null;
        }
        String cleaned = DISALLOWED_CHARS.matcher(displayName).replaceAll("");
        if (cleaned.length() < ValidationPatterns.NAME_MIN_LENGTH) {
            return null;
        }
        return truncate(cleaned, ValidationPatterns.NAME_MAX_LENGTH);
    }

    private String withRandomSuffix(String base, Predicate<String> nameTaken) {
        String head = truncate(base == null ? DEFAULT_PREFIX : base, ValidationPatterns.NAME_MAX_LENGTH - SUFFIX_LENGTH);
        for (int attempt = 0; attempt < MAX_SUFFIX_ATTEMPTS; attempt++) {
            String candidate = head + randomSuffix();
            if (!nameTaken.test(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.OAUTH_NICKNAME_CONFLICT);
    }

    // 보안 값이 아니라 충돌 회피용이므로 SecureRandom 대신 경합 없는 ThreadLocalRandom 을 쓴다.
    private String randomSuffix() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX_CHARS.charAt(random.nextInt(SUFFIX_CHARS.length())));
        }
        return suffix.toString();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
