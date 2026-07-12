package io.casehub.devtown.app.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PagedResultTest {

    private static final List<String> ITEMS = IntStream.rangeClosed(1, 10)
        .mapToObj(i -> "item-" + i).toList();

    @Test
    void firstPage_defaultLimit() {
        var result = PagedResult.paginate(ITEMS, null);
        assertThat(result.items()).hasSize(10);
        assertThat(result.nextCursor()).isNull();
        assertThat(result.total()).isEqualTo(10);
    }

    @Test
    void firstPage_smallLimit() {
        var result = PagedResult.paginate(ITEMS, null, 3);
        assertThat(result.items()).containsExactly("item-1", "item-2", "item-3");
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.total()).isEqualTo(10);
    }

    @Test
    void secondPage_fromCursor() {
        var first = PagedResult.paginate(ITEMS, null, 3);
        var second = PagedResult.paginate(ITEMS, first.nextCursor(), 3);
        assertThat(second.items()).containsExactly("item-4", "item-5", "item-6");
        assertThat(second.nextCursor()).isNotNull();
    }

    @Test
    void lastPage_nextCursorNull() {
        var first = PagedResult.paginate(ITEMS, null, 8);
        var second = PagedResult.paginate(ITEMS, first.nextCursor(), 8);
        assertThat(second.items()).containsExactly("item-9", "item-10");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void cursorPastEnd_returnsEmpty() {
        var first = PagedResult.paginate(ITEMS, null, 10);
        assertThat(first.nextCursor()).isNull();

        var pastEnd = PagedResult.paginate(ITEMS, null, 100);
        assertThat(pastEnd.items()).hasSize(10);
        assertThat(pastEnd.nextCursor()).isNull();
    }

    @Test
    void invalidCursor_startsFromBeginning() {
        var result = PagedResult.paginate(ITEMS, "not-valid-base64", 3);
        assertThat(result.items()).containsExactly("item-1", "item-2", "item-3");
    }

    @Test
    void emptyList() {
        var result = PagedResult.paginate(List.of(), null, 10);
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.total()).isEqualTo(0);
    }

    @Test
    void limitCappedAtMax() {
        var large = IntStream.rangeClosed(1, 300).mapToObj(i -> "item-" + i).toList();
        var result = PagedResult.paginate(large, null, 999);
        assertThat(result.items()).hasSize(200);
        assertThat(result.nextCursor()).isNotNull();
    }

    @Test
    void limitFlooredAtOne() {
        var result = PagedResult.paginate(ITEMS, null, 0);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void fullTraversal() {
        var all = new java.util.ArrayList<String>();
        String cursor = null;
        do {
            var page = PagedResult.paginate(ITEMS, cursor, 3);
            all.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(all).isEqualTo(ITEMS);
    }
}
