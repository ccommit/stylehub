package bwj.stylehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing // @CreatedDate, @LastModifiedDate 자동 설정 활성화
@SpringBootApplication
public class StylehubApplication {

    public static void main(String[] args) {
        SpringApplication.run(StylehubApplication.class, args);
    }

}
