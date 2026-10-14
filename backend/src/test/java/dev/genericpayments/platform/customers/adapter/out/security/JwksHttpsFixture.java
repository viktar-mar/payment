package dev.genericpayments.platform.customers.adapter.out.security;

import com.sun.net.httpserver.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/** Generated key/certificate stay in memory. No real issuer, disk secret, or trust-all client. */
@SuppressWarnings("deprecation")
final class JwksHttpsFixture implements AutoCloseable {
    final HttpsServer server;
    final SSLContext clientContext;
    final SSLContext serverContext;
    final AtomicInteger requests=new AtomicInteger();
    final AtomicReference<String> requestTarget=new AtomicReference<>();
    volatile int status=200;
    volatile byte[] body=IdentitySecurityFixtures.jwks(IdentitySecurityFixtures.RSA.toPublicJWK()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    volatile long delayMillis;
    volatile boolean chunked;
    final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    JwksHttpsFixture() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var key=generator.generateKeyPair();
        var certGenerator=new X509V3CertificateGenerator();
        certGenerator.setSerialNumber(java.math.BigInteger.ONE);
        certGenerator.setIssuerDN(new X500Principal("CN=keys.test")); certGenerator.setSubjectDN(new X500Principal("CN=keys.test"));
        certGenerator.setNotBefore(Date.from(Instant.now().minusSeconds(60))); certGenerator.setNotAfter(Date.from(Instant.now().plusSeconds(3600)));
        certGenerator.setPublicKey(key.getPublic()); certGenerator.setSignatureAlgorithm("SHA256withRSA");
        certGenerator.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.dNSName,"keys.test")));
        X509Certificate cert=certGenerator.generate(key.getPrivate());
        var keyStore=KeyStore.getInstance("PKCS12"); keyStore.load(null,null); keyStore.setKeyEntry("fixture",key.getPrivate(),new char[0],new java.security.cert.Certificate[]{cert});
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keyStore,new char[0]);
        var trustStore=KeyStore.getInstance("PKCS12"); trustStore.load(null,null); trustStore.setCertificateEntry("fixture",cert);
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trustStore);
        serverContext=SSLContext.getInstance("TLS"); serverContext.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        clientContext=SSLContext.getInstance("TLS"); clientContext.init(null,tm.getTrustManagers(),new SecureRandom());
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext)); server.setExecutor(executor);
        server.createContext("/keys",exchange -> {
            requests.incrementAndGet();
            requestTarget.set(exchange.getRequestURI().toASCIIString());
            try {
                if(delayMillis>0) Thread.sleep(delayMillis);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(status==302) exchange.getResponseHeaders().set("Location",uri().toString());
                exchange.sendResponseHeaders(status,chunked?0:body.length);
                exchange.getResponseBody().write(body);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    URI uri() { return URI.create("https://keys.test:"+server.getAddress().getPort()+"/keys"); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
