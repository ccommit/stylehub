package bwj.stylehub.user.entity;

import bwj.stylehub.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

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
