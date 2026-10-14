package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

/** Bounds and validates the two exact anonymization mutations before MVC parsing. */
public final class CustomerAnonymizationRequestPolicyFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES=1024;
    private static final String UUID_PATTERN="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String ADMIN_FAMILY_PATTERN="/api/v1/admin/sites/[^/]+/customers/[^/]+/anonymization(?:/.*)?";
    private static final String ADMIN_PATTERN="/api/v1/admin/sites/"+UUID_PATTERN+"/customers/"+UUID_PATTERN+"/anonymization";
    private static final String PARTNER_FAMILY_PATTERN="/api/v1/partner/customers/[^/]+/anonymization(?:/.*)?";
    private static final String PARTNER_PATTERN="/api/v1/partner/customers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/anonymization";
    private static final ObjectMapper STRICT_BODY_MAPPER=tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ObjectMapper mapper;
    public CustomerAnonymizationRequestPolicyFilter(ObjectMapper mapper){this.mapper=mapper;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !"POST".equals(request.getMethod())||!routeFamily(applicationPath(request));}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        request.setAttribute(CustomerAuthenticationRequestPolicyFilter.BODY_TRACING_DISABLED,Boolean.TRUE);
        String path=applicationPath(request);
        if(!canonical(path)) { ApiProblem.write(request,response,mapper,400,"invalid_request","Invalid request"); return; }
        if(request.getContentLengthLong()>MAX_BODY_BYTES) { tooLarge(request,response); return; }
        byte[] body=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
        if(body.length>MAX_BODY_BYTES) { tooLarge(request,response); return; }
        try {
            CustomerAnonymizationHttp.key(request);
            var json=STRICT_BODY_MAPPER.readTree(body);
            if(json==null||!json.isObject()||json.size()!=1||!json.has("confirmation")
                ||!json.get("confirmation").isString()||!json.get("confirmation").asString().matches(UUID_PATTERN))
                throw new IllegalArgumentException();
            UUID confirmed=UUID.fromString(json.get("confirmation").asString());
            String target=path.substring(0,path.lastIndexOf('/'));
            if(!confirmed.equals(UUID.fromString(target.substring(target.lastIndexOf('/')+1))))throw new IllegalArgumentException();
        } catch(RuntimeException invalid) {
            ApiProblem.write(request,response,mapper,400,"invalid_request","Invalid request"); return;
        }
        chain.doFilter(new BodyRequest(request,body),response);
    }

    private void tooLarge(HttpServletRequest request,HttpServletResponse response)throws IOException {
        ApiProblem.write(request,response,mapper,413,"CUSTOMER_ANONYMIZATION_REQUEST_TOO_LARGE","Customer anonymization request too large");
    }
    private static boolean canonical(String path){return path.matches(ADMIN_PATTERN)||path.matches(PARTNER_PATTERN);}
    private static boolean routeFamily(String rawPath) {
        String candidate=rawPath;
        for(int remaining=rawPath.length()+1;remaining>0;remaining--) {
            String comparable=routingComparable(candidate);
            if(comparable.matches(ADMIN_FAMILY_PATTERN)
                ||comparable.matches(PARTNER_FAMILY_PATTERN))return true;
            String decoded;
            try { decoded=UriUtils.decode(candidate,StandardCharsets.UTF_8); }
            catch(IllegalArgumentException invalid){return false;}
            if(decoded.equals(candidate))return false;
            candidate=decoded;
        }
        return false;
    }
    private static String routingComparable(String path) {
        Deque<String> segments=new ArrayDeque<>();
        for(String raw:path.split("/",-1)) {
            String segment=raw;
            int matrix=segment.indexOf(';'); if(matrix>=0)segment=segment.substring(0,matrix);
            if(segment.isEmpty()||".".equals(segment))continue;
            if("..".equals(segment)){if(!segments.isEmpty())segments.removeLast();continue;}
            segments.addLast(segment.toLowerCase(Locale.ROOT));
        }
        return "/"+String.join("/",segments);
    }
    private static String applicationPath(HttpServletRequest request) {
        String uri=request.getRequestURI(),context=request.getContextPath();
        if(context==null||context.isEmpty()||!uri.startsWith(context))return uri;
        int boundary=context.length();
        return uri.length()>boundary&&uri.charAt(boundary)!='/'?uri:uri.substring(boundary);
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body.clone();}
        @Override public ServletInputStream getInputStream(){
            ByteArrayInputStream input=new ByteArrayInputStream(body);
            return new ServletInputStream(){
                @Override public int read(){return input.read();}
                @Override public boolean isFinished(){return input.available()==0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        @Override public int getContentLength(){return body.length;}
        @Override public long getContentLengthLong(){return body.length;}
    }
}
