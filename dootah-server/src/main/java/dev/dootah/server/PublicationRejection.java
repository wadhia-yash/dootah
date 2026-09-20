package dev.dootah.server;

/** An intentional validation failure with a safe, public diagnostic. Never wrap raw exception messages. */
final class PublicationRejection extends IllegalArgumentException {
    private final String code;

    PublicationRejection(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() { return code; }

    static void require(boolean valid, String code, String message) {
        if (!valid) throw new PublicationRejection(code, message);
    }
}
