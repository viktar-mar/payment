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

/** Bounds and marks secret-bearing Customer identity-link request bodies before MVC parsing. */
public final class CustomerIdentityLinkRequestPolicyFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES=16*1024;
    private static final String CUSTOMER_PATH="/api/v1/customer/identities";
    private static final String PARTNER_FAMILY_PATTERN="/api/v1/partner/customers/[^/]+/identities(?:/.*)?";
    private static final String PARTNER_PATTERN="/api/v1/partner/customers/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/identities";
    private final ObjectMapper mapper;
    public CustomerIdentityLinkRequestPolicyFilter(ObjectMapper mapper){this.mapper=mapper;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !routeFamily(applicationPath(request));}

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        request.setAttribute(CustomerAuthenticationRequestPolicyFilter.BODY_TRACING_DISABLED,Boolean.TRUE);
        String path=applicationPath(request);
        if(!canonical(path)) { ApiProblem.write(request,response,mapper,400,"invalid_request","Invalid request"); return; }
        if(request.getContentLengthLong()>MAX_BODY_BYTES) { tooLarge(request,response); return; }
        byte[] body=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
        if(body.length>MAX_BODY_BYTES) { tooLarge(request,response); return; }
        chain.doFilter(new BodyRequest(request,body),response);
    }

    private void tooLarge(HttpServletRequest request,HttpServletResponse response)throws IOException {
        ApiProblem.write(request,response,mapper,413,"IDENTITY_LINK_REQUEST_TOO_LARGE","Identity link request too large");
    }
    private static boolean canonical(String path){return CUSTOMER_PATH.equals(path)||path.matches(PARTNER_PATTERN);}
    private static boolean routeFamily(String rawPath) {
        String candidate=rawPath;
        for(int remaining=rawPath.length()+1;remaining>0;remaining--) {
            String comparable=routingComparable(candidate);
            if(comparable.equals(CUSTOMER_PATH)||comparable.startsWith(CUSTOMER_PATH+"/")
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
