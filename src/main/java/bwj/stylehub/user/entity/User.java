package bwj.stylehub.user.entity;

import bwj.stylehub.user.enums.Grade;
import bwj.stylehub.user.enums.Provider;
import bwj.stylehub.user.enums.Role;
import bwj.stylehub.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

@Entity
@Table(name = "users")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 20, unique = true)
    private String name;

    @Column(name = "provider_user_id", length = 100)
    private String providerUserId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(length = 400)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Grade grade = Grade.BRONZE;

    @Column(name = "total_spent", nullable = false)
    @Builder.Default
    private Long totalSpent = 0L;

    @Column(name = "point_balance", nullable = false)
    @Builder.Default
    private Integer pointBalance = 0;

    @Column(name = "last_login_date")
    private LocalDate lastLoginDate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    public static User create(String name, String email, String password, LocalDate birthDate) {
        return User.builder()
                .name(name)
                .email(email)
                .password(password)
                .birthDate(birthDate)
                .build();
    }

    public static User createOAuth(String name, String email, Provider provider, String providerUserId) {
        return User.builder()
                .name(name)
                .email(email)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();
    }
}
