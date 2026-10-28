package dev.genericpayments.platform.payments.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

/** Context-relative canonical path policy for public checkout and handoff routes. */
final class PublicCheckoutRoutePolicy {
    private static final String CHECKOUT_PREFIX = "/api/v1/public/checkouts";
    private static final String HANDOFF_PREFIX = "/api/v1/public/handoffs";
    private static final Pattern CATALOG_FAMILY =
        Pattern.compile("^/api/v1/public/sites/[^/]+/checkouts(?:/.*)?$");

    private PublicCheckoutRoutePolicy() { }

    static String applicationPath(String requestUri, String contextPath) {
        if (contextPath == null || contextPath.isEmpty()) return requestUri;
        if (!requestUri.startsWith(contextPath)) return requestUri;
        int boundary = contextPath.length();
        if (requestUri.length() > boundary && requestUri.charAt(boundary) != '/') return requestUri;
        return requestUri.substring(boundary);
    }

    static boolean canonicalSyntax(String applicationPath) {
        if (applicationPath.indexOf('%') >= 0 || applicationPath.indexOf(';') >= 0) return false;
        String[] segments = applicationPath.split("/", -1);
        if (segments.length < 5 || !segments[0].isEmpty()) return false;
        for (int index = 1; index < segments.length; index++) {
            if (segments[index].isEmpty() || ".".equals(segments[index]) || "..".equals(segments[index])) {
                return false;
            }
        }
        if (!"api".equals(segments[1]) || !"v1".equals(segments[2]) || !"public".equals(segments[3])) {
            return false;
        }
        if (segments.length == 5) return "handoffs".equals(segments[4]);
        if (segments.length == 6) return "checkouts".equals(segments[4]);
        if (segments.length != 7) return false;
        return "sites".equals(segments[4]) && "checkouts".equals(segments[6])
            || "checkouts".equals(segments[4]) && ("confirm".equals(segments[6]) || "client-session".equals(segments[6]));
    }

    static boolean routeFamily(String rawApplicationPath) {
        // Detection is deliberately broader than the exact route allowlist: aliases activate this
        // policy boundary, but are never normalized into an accepted route.
        String candidate = rawApplicationPath;
        for (int remaining = rawApplicationPath.length() + 1; remaining > 0; remaining--) {
            if (hasRouteFamily(routingComparable(candidate))) return true;
            String decoded;
            try {
                decoded = UriUtils.decode(candidate, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidEncoding) {
                return false;
            }
            if (decoded.equals(candidate)) return false;
            candidate = decoded;
        }
        return false;
    }

    private static boolean hasRouteFamily(String path) {
        return path.equals(CHECKOUT_PREFIX)
            || path.startsWith(CHECKOUT_PREFIX + "/")
            || path.equals(HANDOFF_PREFIX)
            || path.startsWith(HANDOFF_PREFIX + "/")
            || CATALOG_FAMILY.matcher(path).matches();
    }

    private static String routingComparable(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String rawSegment : path.split("/", -1)) {
            String segment = rawSegment;
            int matrixStart = segment.indexOf(';');
            if (matrixStart >= 0) segment = segment.substring(0, matrixStart);
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) segments.removeLast();
                continue;
            }
            segments.addLast(segment.toLowerCase(Locale.ROOT));
        }
        return "/" + String.join("/", segments);
    }
}
