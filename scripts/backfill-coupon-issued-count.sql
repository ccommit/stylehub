-- =========================================================================
-- 선착순 쿠폰 발급 정합성 보완(이슈 #91) 배포 시 1회 실행하는 보정 스크립트
--
-- 왜 필요한가
--   이전 구현은 발급 기록(user_coupons)만 비동기로 저장하고 coupon_events.issued_count 는 올리지 않아 값이 0 으로 남아 있다.
--   새 구현은 issued_count < issue_count 조건부 UPDATE 로 최종 발급 한도를 지키고,
--   Redis 카운터가 없으면 issue_count - issued_count 로 남은 수량을 다시 만든다.
--   issued_count 를 실제 발급 건수로 맞추지 않으면 이미 발급된 수량만큼 초과 발급된다.
--
-- 배포 절차 (순서를 바꾸면 안 된다)
--   1) 구버전 인스턴스를 모두 중지한다.
--      - 먼저 발급 요청 유입을 막고(진행 중인 선착순 이벤트가 없는 시간대 권장),
--        비동기 저장 큐(couponInsertExecutor)가 비었는지 확인한 뒤 중지한다.
--        확인 방법: /actuator/metrics/executor.queued?tag=name:couponInsertExecutor 와
--        /actuator/metrics/executor.active?tag=name:couponInsertExecutor 가 0 인지 본다.
--      - AsyncConfig 는 종료 시 큐의 남은 작업을 기다리도록 설정되어 있지 않아, 큐가 남은 채 중지하면 그 INSERT 는 실행되지 않는다.
--      - 구버전이 하나라도 살아 있으면 보정 이후의 발급이 issued_count 에 반영되지 않아, 신버전이 그만큼 초과 발급한다.
--   2) 이 파일의 [보정] UPDATE 를 실행한다.
--   3) Redis 의 선착순 쿠폰 키를 모두 삭제한다. 구버전 카운터는 "발행 수량 기준"으로 만들어져 새 규칙과 맞지 않는다.
--        docker exec stylehub-redis sh -c "redis-cli --scan --pattern 'coupon:counter:*' | xargs -r redis-cli del"
--        docker exec stylehub-redis sh -c "redis-cli --scan --pattern 'coupon:issued_users:*' | xargs -r redis-cli del"
--      삭제 후 신버전은 첫 발급 요청에서 DB 기준으로 카운터를 만들고,
--      이미 발급받은 사용자의 재요청은 DB 유니크 제약 경로에서 발급자 기록을 다시 채운다.
--   4) 이 파일의 [검증] 쿼리 두 개를 실행해 결과를 확인한다.
--   5) 신버전을 기동한다.
-- =========================================================================

-- [보정] issued_count 를 실제 발급 행 수로 맞춘다.
UPDATE coupon_events ce
SET ce.issued_count = (
    SELECT COUNT(*)
    FROM user_coupons uc
    WHERE uc.coupon_event_id = ce.coupon_event_id
);

-- [검증 1] issued_count 와 실제 발급 행 수가 다른 이벤트. 0건이어야 한다.
--          0건이 아니면 보정 이후에도 발급이 일어난 것이므로(구버전이 살아 있음) 1) 부터 다시 확인한다.
SELECT ce.coupon_event_id,
       ce.issued_count,
       COUNT(uc.user_coupon_id) AS actual_issued_rows
FROM coupon_events ce
LEFT JOIN user_coupons uc ON uc.coupon_event_id = ce.coupon_event_id
GROUP BY ce.coupon_event_id, ce.issued_count
HAVING ce.issued_count <> COUNT(uc.user_coupon_id);

-- [검증 2] 발행 수량보다 많이 발급된 이벤트. 이전 구현에서 Redis 유실로 이미 초과 발급된 건이다.
--          신버전은 이 이벤트에 더 발급하지 않고(조건부 UPDATE 불통과) 수량 축소도 막지만, 이미 나간 쿠폰은 되돌리지 않으므로
--          0건이 아니면 운영 판단(보상·회수 여부)이 필요하다.
SELECT coupon_event_id,
       issue_count,
       issued_count
FROM coupon_events
WHERE issued_count > issue_count;
