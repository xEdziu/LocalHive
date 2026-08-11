package dev.adrian.goral.localhivebackend.service.research;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

final class ResearchCsvWriter {

    private ResearchCsvWriter() {
    }

    static String write(List<String> headers, List<? extends List<?>> rows) {
        StringBuilder csv = new StringBuilder();
        appendRow(csv, headers);
        rows.forEach(row -> appendRow(csv, row));
        return csv.toString();
    }

    private static void appendRow(StringBuilder csv, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(cells.get(i)));
        }
        csv.append('\n');
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }

        String cell = sanitizeFormula(format(value));
        boolean quoted = cell.contains(",")
                || cell.contains("\"")
                || cell.contains("\n")
                || cell.contains("\r");
        if (!quoted) {
            return cell;
        }

        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    private static String format(Object value) {
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String sanitizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }

        return switch (value.charAt(0)) {
            case '=', '+', '-', '@' -> "'" + value;
            default -> value;
        };
    }
}
