package ccommit.stylehub.common.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * QueryDSL의 JPAQueryFactory를 빈으로 등록한다.
 * </p>
 */
@Configuration
public class QueryDslConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
