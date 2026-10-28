package dev.genericpayments.platform.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Memory-only SDK fixture. Never print requests, headers, forms, or response bodies. */
public final class StripeHttpFixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public StripeHttpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public void enqueue(String method, String path, int status, String json) {
        enqueue(method, path, status, json, Map.of(), 0);
    }

    public void enqueue(String method, String path, int status, String json,
                        Map<String, String> headers, long delayMillis) {
        responses.add(new Response(method, path, status, ignored -> json, headers, delayMillis, 0, false));
    }

    public void enqueue(String method, String path, int status, Function<Call,String> json) {
        responses.add(new Response(method, path, status, json, Map.of(), 0, 0, false));
    }

    public void enqueueDelayedBody(String method, String path, int status, String json, long bodyDelayMillis) {
        responses.add(new Response(method, path, status, ignored -> json, Map.of(), 0, bodyDelayMillis, false));
    }

    public void disconnectNext(String method, String path) {
        responses.add(new Response(method, path, 0, ignored -> "", Map.of(), 0, 0, true));
    }

    public List<Call> calls() { return List.copyOf(calls); }
    public URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = new LinkedHashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var form = new LinkedHashMap<String, String>();
            if (!body.isEmpty()) {
                for (String pair : body.split("&")) {
                    var parts = pair.split("=", 2);
                    form.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
                }
            }
            var call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(headers), Map.copyOf(form), query(exchange.getRequestURI().getRawQuery()));
            calls.add(call);
            Response response = responses.poll();
            if (response == null || !response.method.equals(exchange.getRequestMethod())
                    || !response.path.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            if (response.disconnect) return;
            if (response.delayMillis > 0) {
                try { Thread.sleep(response.delayMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            response.headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            byte[] bytes = response.json.apply(call).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status, bytes.length);
            if (response.bodyDelayMillis > 0) {
                // Flush headers and a partial body so the client has definitely entered body consumption.
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                try { Thread.sleep(response.bodyDelayMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                exchange.getResponseBody().write(bytes, 1, bytes.length-1);
            } else exchange.getResponseBody().write(bytes);
        }
    }

    private static Map<String,String> query(String raw) {
        var result = new LinkedHashMap<String,String>();
        if (raw != null && !raw.isEmpty()) for (String pair : raw.split("&")) {
            var parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return Map.copyOf(result);
    }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    public record Call(String method, String path, Map<String, List<String>> headers,
                       Map<String, String> form, Map<String,String> query) {
        public Call(String method, String path, Map<String,List<String>> headers, Map<String,String> form) {
            this(method,path,headers,form,Map.of());
        }
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
        @Override public String toString() { return "StripeHttpFixture.Call[redacted]"; }
    }

    private record Response(String method, String path, int status, Function<Call,String> json,
                            Map<String, String> headers, long delayMillis, long bodyDelayMillis, boolean disconnect) {
        @Override public String toString() { return "StripeHttpFixture.Response[redacted]"; }
    }
}
