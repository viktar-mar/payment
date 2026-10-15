package dev.genericpayments.platform.provider.adapter.in.web;

import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Reads a bounded stream once; the controller authenticates its original bytes before decoding. */
public final class StripeWebhookAdmissionFilter extends OncePerRequestFilter {
    public static final String RAW_BODY=StripeWebhookAdmissionFilter.class.getName()+".raw";
    private final ObjectMapper mapper;
    public StripeWebhookAdmissionFilter(ObjectMapper mapper) {this.mapper=mapper;}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getServletPath();if(path.isEmpty())path=request.getRequestURI().substring(request.getContextPath().length());
        if(!path.matches("/api/v1/provider-webhooks/stripe/[^/]+")) {chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        if(!"POST".equals(request.getMethod())) {response.setHeader("Allow","POST");reject(request,response,405);return;}
        try {
            var contentTypes=Collections.list(request.getHeaders("Content-Type"));
            if(contentTypes.size()!=1) {reject(request,response,415);return;}
            var media=MediaType.parseMediaType(contentTypes.getFirst());
            if(!media.isConcrete() || !media.getType().equals("application") || !(media.getSubtype().equals("json") || media.getSubtype().endsWith("+json"))
                    || media.getCharset()!=null && !media.getCharset().equals(StandardCharsets.UTF_8)) {reject(request,response,415);return;}
        } catch(IllegalArgumentException invalid) {reject(request,response,415);return;}
        var headers=Collections.list(request.getHeaders("Stripe-Signature"));
        if(headers.size()!=1 || headers.getFirst().length()>4096 || headers.getFirst().getBytes(StandardCharsets.UTF_8).length>4096
                || request.getHeader("Stripe-Account")!=null || request.getHeader("Stripe-Context")!=null) {reject(request,response,400);return;}
        // readNBytes caps allocation and consumption even without Content-Length/chunked transport.
        if(request.getContentLengthLong()>262144) {reject(request,response,413);return;}
        byte[] raw=request.getInputStream().readNBytes(262145);
        if(raw.length>262144) {reject(request,response,413);return;}
        request.setAttribute(RAW_BODY,raw);
        chain.doFilter(request,response);
    }
    private void reject(HttpServletRequest request,HttpServletResponse response,int status)throws IOException {
        ApiProblem.write(request,response,mapper,status,"invalid_provider_webhook","Provider webhook rejected");
    }
}
