package ccommit.stylehub.common.util;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 메서드 실행 시간을 측정하는 유틸리티 클래스이다.
 * AOP에서 시간 측정 코드 중복을 제거하기 위해 사용한다.
 * </p>
 */
public class StopWatch {

    private final long startTime;

    private StopWatch() {
        this.startTime = System.currentTimeMillis();
    }

    public static StopWatch start() {
        return new StopWatch();
    }

    public long elapsed() {
        return System.currentTimeMillis() - startTime;
    }
}
