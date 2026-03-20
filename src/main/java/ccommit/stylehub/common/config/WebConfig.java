package ccommit.stylehub.common.config;

import ccommit.stylehub.user.enums.Provider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
