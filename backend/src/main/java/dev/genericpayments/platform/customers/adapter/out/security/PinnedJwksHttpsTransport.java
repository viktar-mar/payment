package dev.genericpayments.platform.customers.adapter.out.security;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** Single-hop HTTP/1.1 over hostname-verified TLS, connected by a validated literal IP (never default DNS). */
final class PinnedJwksHttpsTransport {
    private final JwksAddressPolicy policy;
    private final SSLContext tls;
    PinnedJwksHttpsTransport(JwksAddressPolicy policy,SSLContext tls) { this.policy=policy; this.tls=tls; }

    byte[] fetch(URI uri,long deadline) throws IOException {
        policy.resolve(uri); // Validate the entire first DNS answer, including addresses we will not select.
        InetAddress selected=policy.resolve(uri)[0]; // Re-resolve/validate all addresses at the actual connect boundary.
        policy.validateAddress(uri,selected);
        int port=uri.getPort()<0?443:uri.getPort();
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress(selected,port),Math.min(2000,remainingMillis(deadline)));
            // Pinning prevents rebinding between validation and connect. Check the actual peer as a separate invariant.
            policy.validateAddress(uri,socket.getInetAddress());
            if(!selected.equals(socket.getInetAddress())) throw CustomerAuthenticationFailure.failed();
            try(var secure=(SSLSocket)tls.getSocketFactory().createSocket(socket,uri.getHost(),port,true)) {
                var parameters=secure.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if(!uri.getHost().contains(":") && !uri.getHost().matches("[0-9.]+")) parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                parameters.setProtocols(new String[]{"TLSv1.3","TLSv1.2"}); secure.setSSLParameters(parameters);
                secure.setSoTimeout(remainingMillis(deadline)); secure.startHandshake();
                policy.validateAddress(uri,secure.getInetAddress());
                if(!selected.equals(secure.getInetAddress())) throw CustomerAuthenticationFailure.failed();
                URI ascii=URI.create(uri.toASCIIString());
                String target=ascii.getRawPath()==null||ascii.getRawPath().isEmpty()?"/":ascii.getRawPath();
                if(ascii.getRawQuery()!=null) target+="?"+ascii.getRawQuery();
                String request="GET "+target+" HTTP/1.1\r\nHost: "+uri.getRawAuthority()+"\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                secure.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); secure.getOutputStream().flush();
                return new ResponseReader(secure,deadline).read();
            }
        }
    }
    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long nanos=deadline-System.nanoTime();
        if(nanos<=0) throw new SocketTimeoutException("JWKS deadline exceeded");
        return (int)Math.max(1,Math.min(3000,(nanos+999999)/1000000));
    }
    private static final class ResponseReader {
        private final SSLSocket socket;
        private final InputStream input;
        private final long deadline;
        private int headerBytes;
        ResponseReader(SSLSocket socket,long deadline) throws IOException { this.socket=socket; this.input=socket.getInputStream(); this.deadline=deadline; }
        private int readByte() throws IOException { socket.setSoTimeout(remainingMillis(deadline)); return input.read(); }
        private String line() throws IOException {
            var output=new ByteArrayOutputStream();
            for(int i=0;i<8192;i++) {
                int b=readByte(); if(b<0) throw new EOFException("Invalid JWKS response");
                if(++headerBytes>16384) throw new IOException("JWKS header limit exceeded");
                if(b=='\r') { if(readByte()!='\n') throw new IOException("Invalid JWKS response"); return output.toString(StandardCharsets.US_ASCII); }
                if(b<' ' || b>126) throw new IOException("Invalid JWKS response"); output.write(b);
            }
            throw new IOException("JWKS header limit exceeded");
        }
        byte[] read() throws IOException {
            String status=line();
            if(!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("JWKS HTTP status rejected");
            var headers=new HashMap<String,String>();
            for(int i=0;;i++) {
                String line=line(); if(line.isEmpty()) break;
                int colon=line.indexOf(':');
                if(i>=64 || colon<1 || line.charAt(0)==' ' || !line.substring(0,colon).matches("[A-Za-z0-9-]+")) throw new IOException("Invalid JWKS headers");
                String name=line.substring(0,colon).toLowerCase(Locale.ROOT); String value=line.substring(colon+1).trim();
                if(headers.putIfAbsent(name,value)!=null) throw new IOException("Ambiguous JWKS headers");
            }
            if(headers.containsKey("content-encoding")&&!"identity".equalsIgnoreCase(headers.get("content-encoding"))) throw new IOException("JWKS encoding rejected");
            if(headers.containsKey("trailer")) throw new IOException("JWKS trailers rejected");
            var body=new ByteArrayOutputStream();
            if(headers.containsKey("transfer-encoding")) {
                if(headers.containsKey("content-length") || !"chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) throw new IOException("JWKS framing rejected");
                for(int chunks=0;chunks<65536;chunks++) {
                    headerBytes=0; String size=line();
                    if(!size.matches("[0-9A-Fa-f]{1,8}")) throw new IOException("JWKS framing rejected");
                    long length=Long.parseLong(size,16);
                    if(length==0) {
                        if(!line().isEmpty()) throw new IOException("JWKS trailers rejected");
                        return body.toByteArray();
                    }
                    copy(body,length);
                    if(readByte()!='\r'||readByte()!='\n') throw new IOException("JWKS framing rejected");
                }
                throw new IOException("JWKS framing rejected");
            }
            if(headers.containsKey("content-length")) {
                String length=headers.get("content-length");
                if(!length.matches("[0-9]{1,10}")) throw new IOException("JWKS framing rejected");
                copy(body,Long.parseLong(length));
            } else {
                for(int value;(value=readByte())>=0;) {
                    if(body.size()>=262144) throw new IOException("JWKS body limit exceeded"); body.write(value);
                }
            }
            return body.toByteArray();
        }
        private void copy(ByteArrayOutputStream body,long length) throws IOException {
            if(length>262144-body.size()) throw new IOException("JWKS body limit exceeded");
            byte[] buffer=new byte[8192];
            while(length>0) {
                socket.setSoTimeout(remainingMillis(deadline));
                int count=input.read(buffer,0,(int)Math.min(buffer.length,length));
                if(count<0) throw new EOFException("Invalid JWKS response");
                body.write(buffer,0,count); length-=count;
            }
        }
    }
}
