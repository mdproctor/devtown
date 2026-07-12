package io.casehub.devtown.app.governance;

import java.util.Base64;
import java.util.List;

public record PagedResult<T>(
    List<T> items,
    String nextCursor,
    int total
) {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    public static <T> PagedResult<T> paginate(List<T> allItems, String cursor, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        int offset = decodeCursor(cursor);
        int total = allItems.size();

        if (offset >= total) {
            return new PagedResult<>(List.of(), null, total);
        }

        int end = Math.min(offset + limit, total);
        List<T> page = allItems.subList(offset, end);
        String next = end < total ? encodeCursor(end) : null;
        return new PagedResult<>(page, next, total);
    }

    public static <T> PagedResult<T> paginate(List<T> allItems, String cursor) {
        return paginate(allItems, cursor, DEFAULT_LIMIT);
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) return 0;
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(cursor)));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String encodeCursor(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes());
    }
}
