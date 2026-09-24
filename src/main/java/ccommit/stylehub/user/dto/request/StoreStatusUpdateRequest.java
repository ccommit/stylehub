package ccommit.stylehub.user.dto.request;

import ccommit.stylehub.user.enums.StoreStatus;
import jakarta.validation.constraints.NotNull;

/**
 * @author WonJin Bae
 * @created 2026/09/24
 *
 * <p>
 * 관리자가 스토어 상태를 바꾸는 요청 데이터를 담는 불변 DTO이다.
 * 승인·거절·정지를 목표 상태 하나로 표현해 상태 변경 엔드포인트를 하나로 모은다.
 * </p>
 */
public record StoreStatusUpdateRequest(

        @NotNull
        StoreStatus status
) {
}
