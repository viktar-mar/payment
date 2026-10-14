package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bounds authenticated message redaction writes before MVC can buffer or parse JSON. */
public final class DonationMessageAdminRequestPolicyFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 1024;
    private static final RequestMatcher REDACT = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/donations/{donationId}/message-redactions");
    private final ObjectMapper mapper;

    public DonationMessageAdminRequestPolicyFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // Match decoded path segments exactly as Spring MVC does, including context paths.
        return !REDACT.matches(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            tooLarge(request, response);
            return;
        }
        // Servlet-reported lengths may be absent or misleading: never pass the original stream on.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            tooLarge(request, response);
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiProblem.write(request, response, mapper, 413, "DONATION_MESSAGE_REQUEST_TOO_LARGE", "Donation message request too large");
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BoundedBodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body.clone(); }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
