package io.casehub.devtown.app.ledger;

final class LedgerContentUtils {

    private LedgerContentUtils() {}

    static String escapePipe(final String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }
}
