package dev.genericpayments.platform.shared.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import tools.jackson.databind.ObjectMapper;

public record ApiProblem(String type, String title, int status, String code,
                         @JsonProperty("request_id") String requestId) {
    public static ApiProblem of(int status, String code, String title, HttpServletRequest request) {
        return new ApiProblem("urn:payment:problem:" + code, title, status, code, RequestIdFilter.requestId(request));
    }
    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper,
                             int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getWriter(), of(status, code, title, request));
    }
}
