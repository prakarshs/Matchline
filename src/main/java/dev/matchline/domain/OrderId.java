package dev.matchline.domain;

/**
 * Case-sensitive, opaque identifier. The future book will enforce uniqueness
 * within one journal epoch; constructing this value cannot check duplicates.
 * ASCII only: one to 64 characters, beginning with an alphanumeric character.
 * No trimming or case conversion is performed.
 */
public record OrderId(String value) {
    private static final int MAX_LENGTH = 64;

    public OrderId {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || !validAscii(value)) {
            throw new OrderValidationException(RejectionCode.INVALID_ORDER_ID,
                    "order ID must be 1..64 ASCII characters: first alphanumeric, then letters, digits, '.', '_' or '-'");
        }
    }

    private static boolean validAscii(String text) {
        if (!asciiLetterOrDigit(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!asciiLetterOrDigit(ch) && ch != '.' && ch != '_' && ch != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiLetterOrDigit(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9');
    }

    @Override
    public String toString() {
        return value;
    }
}
