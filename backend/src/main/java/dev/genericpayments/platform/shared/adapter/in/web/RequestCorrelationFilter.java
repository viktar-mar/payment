package dev.genericpayments.platform.shared.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The sole registered correlation filter. MDC belongs to the current dispatch only. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends RequestIdFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming=request.getHeader("X-Request-Id");
        String requestId=incoming!=null&&incoming.matches("[A-Za-z0-9._:-]{1,64}")?incoming:UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE,requestId);
        response.setHeader("X-Request-Id",requestId);
        String applicationPath=applicationPath(request);
        if(applicationPath.startsWith("/api/v1/admin/")||applicationPath.startsWith("/api/admin/")
                ||applicationPath.startsWith("/api/v1/public/"))
            response.setHeader("Cache-Control","no-store");
        String previous=MDC.get("request_id");
        try {MDC.put("request_id",requestId);chain.doFilter(request,response);}
        finally {if(previous==null)MDC.remove("request_id");else MDC.put("request_id",previous);}
    }
    private static String applicationPath(HttpServletRequest request) {
        String requestUri=request.getRequestURI(),contextPath=request.getContextPath();
        if(contextPath==null||contextPath.isEmpty()||!requestUri.startsWith(contextPath)) return requestUri;
        int boundary=contextPath.length();
        if(requestUri.length()>boundary&&requestUri.charAt(boundary)!='/') return requestUri;
        return requestUri.substring(boundary);
    }
}
