package ccommit.stylehub.common.idempotency;

import ccommit.stylehub.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 처리를 마친 Idempotency-Key 와 그 결과로 만들어진 리소스 ID 를 남긴다.
 * 업무 처리와 같은 트랜잭션에서 먼저 INSERT 해, 같은 키의 동시 요청은 유니크 인덱스에서 기다렸다가 중복으로 끝난다.
 * </p>
 */
@Entity
@Table(name = "idempotency_records", uniqueConstraints = {
        @UniqueConstraint(name = IdempotencyRecord.UNIQUE_KEY_NAME, columnNames = {"user_id", "operation", "idempotency_key"})
})
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord extends BaseEntity {

    public static final String UNIQUE_KEY_NAME = "uk_idempotency_records_user_operation_key";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "idempotency_record_id")
    private Long idempotencyRecordId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotentOperation operation;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    // 업무 처리 전에 키를 먼저 잡으므로, 결과가 나오기 전까지는 비어 있다.
    @Column(name = "resource_id")
    private Long resourceId;

    public static IdempotencyRecord claim(IdempotencyRequest request) {
        return IdempotencyRecord.builder()
                .userId(request.userId())
                .operation(request.operation())
                .idempotencyKey(request.key())
                .requestHash(request.requestHash())
                .build();
    }

    public void complete(Long resourceId) {
        this.resourceId = resourceId;
    }

    public boolean isSameRequest(String requestHash) {
        return this.requestHash.equals(requestHash);
    }
}
