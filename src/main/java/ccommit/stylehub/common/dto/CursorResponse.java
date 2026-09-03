package ccommit.stylehub.common.dto;

import lombok.Builder;

import java.util.List;
import java.util.function.Function;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 커서 기반 페이징 공통 응답 DTO이다.
 * 도메인에 관계없이 커서 페이징이 필요한 곳에서 재사용한다.
 * offset 페이징 대비 대용량 데이터에서 일정한 성능을 보장한다.
 * </p>
 */
@Builder
public record CursorResponse<T>(
        List<T> items,
        Long nextCursor,
        boolean hasNext
) {
    /**
     * 커서 페이징 응답을 생성한다.
     * pageSize + 1건 조회된 리스트에서 hasNext를 판단하고, 마지막 항목의 ID를 nextCursor로 설정한다.
     *
     * @param items 조회된 리스트 (pageSize + 1건)
     * @param pageSize 요청한 페이지 크기
     * @param cursorExtractor 항목에서 커서 값(ID)을 추출하는 함수
     */
    public static <T> CursorResponse<T> of(List<T> items, int pageSize, Function<T, Long> cursorExtractor) {
        boolean hasNext = items.size() > pageSize;

        List<T> content = hasNext
                ? items.subList(0, pageSize)
                : items;

        Long nextCursor = hasNext
                ? cursorExtractor.apply(content.get(content.size() - 1))
                : null;

        return CursorResponse.<T>builder()
                .items(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
}
