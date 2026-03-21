package ccommit.stylehub.common.config;

import ccommit.stylehub.user.enums.Provider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * Spring MVC 커스텀 Converter를 등록한다.
 * provider 문자열을 Provider enum으로 자동 변환한다.
 * </p>
 */

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToProviderConverter());
    }

    private static class StringToProviderConverter implements Converter<String, Provider> {

        @Override
        public Provider convert(String source) {
            return Provider.valueOf(source.toUpperCase());
        }
    }
}
