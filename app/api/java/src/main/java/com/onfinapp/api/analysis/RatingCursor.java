package com.onfinapp.api.analysis;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.onfinapp.api.common.ApiProblemException;

/**
 * Position in a rating history: the sequence number of the last rating already returned.
 * Clients see it as an opaque base64url string.
 */
public final class RatingCursor {

    /** Before the newest rating. */
    public static final long START = Long.MAX_VALUE;

    private RatingCursor() {
    }

    public static String encode(long seq) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(seq).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws ApiProblemException.InvalidCursor for anything that {@link #encode} did not produce */
    public static long decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.isEmpty() && raw.length() <= 18 && raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
                long seq = Long.parseLong(raw);
                if (seq > 0) {
                    return seq;
                }
            }
        } catch (IllegalArgumentException e) {
            // not base64url: falls through to the error below
        }
        throw new ApiProblemException.InvalidCursor();
    }
}
