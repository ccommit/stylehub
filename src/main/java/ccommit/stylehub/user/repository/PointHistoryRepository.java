package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @author WonJin Bae
 * @created 2026/04/23
 *
 * <p>
 * 포인트 적립/사용 이력(PointHistory)의 데이터 접근을 담당한다.
 * 이력은 감사(audit) 목적이므로 수정/삭제 없이 append-only로 사용한다.
 * </p>
 */
public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {
}
