package dev.matchline.domain;

/**
 * Case-sensitive instrument partition key; no Unicode or implicit normalization.
 * An ID is 1..12 characters, starting with A-Z, followed by A-Z, 0-9, '.', '_' or '-'.
 * The initial demo will use {@code DEMO}.
 */
public record InstrumentId(String value) {
    private static final int MAX_LENGTH = 12;

    public InstrumentId {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || !validAscii(value)) {
            throw new OrderValidationException(RejectionCode.INVALID_INSTRUMENT,
                    "instrument must be 1..12 uppercase ASCII characters: first A-Z, then A-Z, digits, '.', '_' or '-'");
        }
    }

    private static boolean validAscii(String text) {
        char first = text.charAt(0);
        if (first < 'A' || first > 'Z') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9')
                    && ch != '.' && ch != '_' && ch != '-') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return value;
    }
}
