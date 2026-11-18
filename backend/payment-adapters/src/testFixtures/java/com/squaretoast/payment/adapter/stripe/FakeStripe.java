package com.squaretoast.payment.adapter.stripe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** A real HTTP server on loopback standing in for api.stripe.com: records requests, replies from a script. */
public final class FakeStripe implements AutoCloseable {

    /** One recorded request; header names are lower-cased. */
    public record Request(String method, String path, Map<String, List<String>> headers, String body) {

        public @Nullable String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        /** The form body decoded, keys and values; repeated keys keep every value. */
        public Map<String, List<String>> form() {
            Map<String, List<String>> out = new TreeMap<>();
            if (body.isEmpty()) {
                return out;
            }
            for (String pair : body.split("&", -1)) {
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
            return out;
        }

        public @Nullable String formValue(String key) {
            List<String> v = form().get(key);
            return v == null ? null : v.get(0);
        }
    }

    public record Response(int status, String json) {}

    private final HttpServer server;
    private final BlockingQueue<Request> received = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Response> scripted = new ConcurrentLinkedQueue<>();
    private volatile Function<Request, Response> fallback = r -> new Response(500, "{\"error\":{\"type\":\"api_error\"}}");

    public FakeStripe() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Replies to the next request, in order, before the fallback is used. */
    public void enqueue(int status, String json) {
        scripted.add(new Response(status, json));
    }

    /** Replies to every request not covered by {@link #enqueue}. */
    public void fallback(Function<Request, Response> responder) {
        this.fallback = responder;
    }

    public Request takeRequest() {
        try {
            Request r = received.poll(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            if (r == null) {
                throw new AssertionError("no request reached the fake Stripe server");
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public List<Request> drain() {
        List<Request> out = new ArrayList<>();
        received.drainTo(out);
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, List<String>> headers = new TreeMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        String path = ex.getRequestURI().getRawPath();
        Request req = new Request(ex.getRequestMethod(), path, headers, body);
        received.add(req);
        Response next = scripted.poll();
        Response res = next != null ? next : fallback.apply(req);
        byte[] bytes = res.json().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(res.status(), bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
