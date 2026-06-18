package io.casehub.devtown.app.mcp;

public enum CaseTrackingStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAULTED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAULTED || this == CANCELLED;
    }

    public static CaseTrackingStatus fromCaseStatus(String caseStatus) {
        return switch (caseStatus) {
            case "COMPLETED" -> COMPLETED;
            case "FAULTED" -> FAULTED;
            case "CANCELLED" -> CANCELLED;
            case "WAITING" -> WAITING;
            default -> RUNNING;
        };
    }
}
