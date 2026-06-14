package com.lobsterai.skillgateway.http;

import com.lobsterai.skillgateway.util.StringUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes outbound HTTP URLs so query/path components are not left double- (or triple-)
 * percent-encoded. Some clients or proxies encode UTF-8 twice ({@code %25E4...}); servers then
 * see literal {@code %E4...} instead of the intended characters. This helper peels extra
 * encoding layers (bounded), then lets Spring re-encode once for the wire.
 */
public final class OutboundUrlNormalizer {

    private static final int MAX_PEEL_ITERATIONS = 8;

    private OutboundUrlNormalizer() {
    }

    /**
     * @param url raw URL from skill / agent (may be over-encoded)
     * @return normalized URL for {@link org.springframework.web.client.RestTemplate}, or original on failure
     */
    public static String normalizeForOutboundHttp(String url) {
        if (url == null || StringUtils.isBlank(url)) {
            return url;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return trimmed;
        }
        try {
            UriComponents in = UriComponentsBuilder.fromUriString(trimmed).build(true);
            UriComponentsBuilder b = UriComponentsBuilder.newInstance();
            b.scheme(in.getScheme());
            if (in.getUserInfo() != null) {
                b.userInfo(peelEncoded(in.getUserInfo()));
            }
            if (in.getHost() != null) {
                b.host(in.getHost());
            }
            if (in.getPort() != -1) {
                b.port(in.getPort());
            }
            appendPath(b, in);
            for (Map.Entry<String, List<String>> e : in.getQueryParams().entrySet()) {
                String key = peelEncoded(e.getKey());
                for (String rawVal : e.getValue()) {
                    b.queryParam(key, peelEncoded(rawVal));
                }
            }
            if (in.getFragment() != null) {
                b.fragment(peelEncoded(in.getFragment()));
            }
            return b.encode(StandardCharsets.UTF_8).build().toUriString();
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private static void appendPath(UriComponentsBuilder b, UriComponents in) {
        // Build the path string manually instead of using b.pathSegment(...) per segment.
        // UriComponents.getPathSegments() drops a trailing empty segment, so a URL like
        // http://host/foo/  would lose its trailing slash when rebuilt segment-by-segment,
        // which breaks mock servers / APIs that route on the trailing slash.
        // We rebuild the path from peeled segments, then re-append the trailing slash
        // (if the original had one) before handing it to b.path(String).
        String originalPath = in.getPath();
        if (originalPath == null || originalPath.isEmpty()) {
            return;
        }
        if ("/".equals(originalPath)) {
            b.path("/");
            return;
        }
        boolean trailingSlash = originalPath.endsWith("/");
        List<String> segments = in.getPathSegments();
        StringBuilder path = new StringBuilder();
        boolean first = true;
        for (String seg : segments) {
            if (!first) {
                path.append('/');
            }
            path.append(peelEncoded(seg));
            first = false;
        }
        if (trailingSlash && !segments.isEmpty()) {
            path.append('/');
        }
        b.path("/" + path.toString());
    }

    /**
     * Peel repeated application/x-www-form-urlencoded-style decoding (percent and '+') until stable.
     * Matches {@link UriUtils#decode(String, java.nio.charset.Charset)} semantics used by Spring for components.
     */
    private static String peelEncoded(String value) {
        if (value == null) {
            return null;
        }
        String cur = value;
        for (int i = 0; i < MAX_PEEL_ITERATIONS; i++) {
            try {
                String next = UriUtils.decode(cur, StandardCharsets.UTF_8);
                if (next.equals(cur)) {
                    break;
                }
                cur = next;
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        return cur;
    }
}
