package ccommit.stylehub.user.service;

import ccommit.stylehub.common.constants.ValidationPatterns;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 소셜 가입 닉네임 생성 규칙의 단위 테스트이다.
 * 어떤 표시 이름이 들어와도 결과가 일반 가입 규칙(한글·영문·숫자, 2~10자)을 만족하는지와 중복 회피 동작을 검증한다.
 * </p>
 */
class SocialNicknameGeneratorTest {

    private static final int SUFFIXED_HEAD_LENGTH =
            ValidationPatterns.NAME_MAX_LENGTH - SocialNicknameGenerator.SUFFIX_LENGTH;

    private final SocialNicknameGenerator generator = new SocialNicknameGenerator();

    @Test
    @DisplayName("규칙에 맞고 쓰이지 않는 이름은 그대로 쓴다")
    void keepsValidAvailableName() {
        assertThat(generator.generate("홍길동", name -> false)).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("허용되지 않는 문자는 제거하고 최대 길이로 자른다")
    void removesDisallowedCharsAndTruncates() {
        String nickname = generator.generate("Kim, Min-Su (Google)", name -> false);

        assertThat(nickname).isEqualTo("KimMinSuGo");
        assertValidNickname(nickname);
    }

    @Test
    @DisplayName("이미 쓰이는 이름이면 앞부분을 줄이고 랜덤 접미사를 붙인다")
    void appendsSuffix_whenNameTaken() {
        String takenName = "가나다라마바사아자차";
        String nickname = generator.generate(takenName, Set.of(takenName)::contains);

        assertThat(nickname).startsWith(takenName.substring(0, SUFFIXED_HEAD_LENGTH));
        assertThat(nickname).hasSize(ValidationPatterns.NAME_MAX_LENGTH);
        assertValidNickname(nickname);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "A", "李小龍", "😀😀", " - "})
    @DisplayName("정규화 결과가 최소 길이에 못 미치면 기본 이름에 접미사를 붙인다")
    void usesDefaultPrefix_whenNormalizedNameTooShort(String displayName) {
        String nickname = generator.generate(displayName, name -> false);

        assertThat(nickname).startsWith(SocialNicknameGenerator.DEFAULT_PREFIX);
        assertThat(nickname).hasSize(SocialNicknameGenerator.DEFAULT_PREFIX.length() + SocialNicknameGenerator.SUFFIX_LENGTH);
        assertValidNickname(nickname);
    }

    @Test
    @DisplayName("접미사 강제 생성은 원래 이름이 쓰이지 않아도 접미사 없는 이름을 돌려주지 않는다")
    void generateWithSuffix_neverReturnsBaseName() {
        String nickname = generator.generateWithSuffix("홍길동", name -> false);

        assertThat(nickname).startsWith("홍길동").isNotEqualTo("홍길동");
        assertThat(nickname).hasSize("홍길동".length() + SocialNicknameGenerator.SUFFIX_LENGTH);
        assertValidNickname(nickname);
    }

    @Test
    @DisplayName("접미사를 붙여도 계속 겹치면 정해진 횟수만 조회하고 OAUTH_NICKNAME_CONFLICT 를 던진다")
    void throws_whenSuffixAttemptsExhausted() {
        AtomicInteger lookups = new AtomicInteger();

        assertThatThrownBy(() -> generator.generate("홍길동", name -> {
            lookups.incrementAndGet();
            return true;
        }))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_NICKNAME_CONFLICT);

        // 원래 이름 1회 + 접미사 시도 횟수
        assertThat(lookups.get()).isEqualTo(1 + SocialNicknameGenerator.MAX_SUFFIX_ATTEMPTS);
    }

    private void assertValidNickname(String nickname) {
        assertThat(nickname)
                .matches(ValidationPatterns.NAME_PATTERN)
                .hasSizeBetween(ValidationPatterns.NAME_MIN_LENGTH, ValidationPatterns.NAME_MAX_LENGTH);
    }
}
