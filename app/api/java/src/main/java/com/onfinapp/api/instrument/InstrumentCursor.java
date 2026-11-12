package com.onfinapp.api.instrument;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.onfinapp.api.common.ApiProblemException;

/**
 * Position in a search result: the rank and ticker of the last item already returned.
 * Clients see it as an opaque base64url string.
 */
public record InstrumentCursor(int rank, String symbol) {

    /** Before the first item. */
    public static final InstrumentCursor START = new InstrumentCursor(-1, "");

    public String encode() {
        byte[] raw = (rank + ":" + symbol).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @throws ApiProblemException.InvalidCursor for anything that {@link #encode} did not produce */
    public static InstrumentCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            if (colon > 0 && colon < raw.length() - 1) {
                String digits = raw.substring(0, colon);
                if (digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    int rank = Integer.parseInt(digits);
                    if (rank <= 2) {
                        return new InstrumentCursor(rank, raw.substring(colon + 1));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // not base64url, or a rank too long to be a number: falls through to the error below
        }
        throw new ApiProblemException.InvalidCursor();
    }
}
