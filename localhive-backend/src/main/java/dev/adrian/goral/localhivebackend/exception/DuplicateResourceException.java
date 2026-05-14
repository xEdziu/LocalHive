package dev.adrian.goral.localhivebackend.exception;

public class DuplicateResourceException extends RuntimeException {

    private final String field;
    private final String value;

    public DuplicateResourceException(String field, String value) {
        super(field + " already exists: " + value);
        this.field = field;
        this.value = value;
    }

    public String getField() {
        return field;
    }

    public String getValue() {
        return value;
    }
}