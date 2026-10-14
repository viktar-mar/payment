package dev.genericpayments.platform.customers.adapter.out.security;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Local hostname-verified TLS with exact response bytes, including intentionally malformed HTTP. */
final class JwksWireFixture implements AutoCloseable {
    @FunctionalInterface interface Response { void write(OutputStream output) throws IOException,InterruptedException; }
    private final SSLServerSocket listener;
    private final Future<?> worker;
    private volatile SSLSocket accepted;
    final CountDownLatch requested=new CountDownLatch(1);

    JwksWireFixture(JwksHttpsFixture tls,Response response) throws IOException {
        listener=(SSLServerSocket)tls.serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress());
        listener.setSoTimeout(5000);
        worker=tls.executor.submit(()->{
            try(var socket=(SSLSocket)listener.accept()) {
                accepted=socket; socket.setSoTimeout(5000);
                InputStream input=socket.getInputStream(); int matched=0;
                byte[] end={'\r','\n','\r','\n'};
                for(int count=0;count<16384 && matched<end.length;count++) {
                    int value=input.read(); if(value<0) return;
                    matched=value==end[matched]?matched+1:value=='\r'?1:0;
                }
                if(matched!=end.length) return;
                requested.countDown();
                response.write(socket.getOutputStream()); socket.getOutputStream().flush();
            } catch(IOException expectedRejection) {
                // Rejected framing/deadline clients may close while the fixture is writing. Never log wire bytes.
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
    }
    URI uri() { return URI.create("https://keys.test:"+listener.getLocalPort()+"/keys"); }
    @Override public void close() throws IOException {
        listener.close(); SSLSocket socket=accepted; if(socket!=null) socket.close(); worker.cancel(true);
    }
}
