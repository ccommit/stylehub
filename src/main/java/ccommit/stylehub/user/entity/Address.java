package ccommit.stylehub.user.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 사용자의 배송지 주소를 관리한다.
 * 한 사용자가 여러 배송지를 등록할 수 있다.
 * </p>
 */

@Entity
@Table(name = "addresses")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "address_id")
    private Long addressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 20)
    private String recipientName;

    @Column(nullable = false, length = 40)
    private String phone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(name = "street_address", nullable = false, length = 40)
    private String streetAddress;

    @Column(name = "detail_address", length = 40)
    private String detailAddress;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean defaultAddress = false;


    public static Address create(User user, String label, String recipientName, String phone,
                                 String zipCode, String streetAddress, String detailAddress) {
        return Address.builder()
                .user(user)
                .label(label)
                .recipientName(recipientName)
                .phone(phone)
                .zipCode(zipCode)
                .streetAddress(streetAddress)
                .detailAddress(detailAddress)
                .build();
    }
}
