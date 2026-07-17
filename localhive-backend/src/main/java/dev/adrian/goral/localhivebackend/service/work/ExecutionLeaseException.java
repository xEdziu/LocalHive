package dev.adrian.goral.localhivebackend.service.work;

public class ExecutionLeaseException extends RuntimeException {

    public enum Reason {
        INVALID,
        EXPIRED,
        INVALID_STATUS
    }

    private final Reason reason;

    public ExecutionLeaseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public static ExecutionLeaseException invalid(String message) {
        return new ExecutionLeaseException(Reason.INVALID, message);
    }

    public static ExecutionLeaseException expired(String message) {
        return new ExecutionLeaseException(Reason.EXPIRED, message);
    }

    public static ExecutionLeaseException invalidStatus(String message) {
        return new ExecutionLeaseException(Reason.INVALID_STATUS, message);
    }
}
