package com.squaretoast.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/** Minimal text/event-stream reader: one virtual thread parses frames into a queue, stamped on arrival. */
public final class SseClient implements AutoCloseable {

    public record Event(long id, String name, JsonNode data, long receivedNanos) {}

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Stream<String> lines;
    private final Thread reader;
    private final int status;

    private SseClient(Stream<String> lines, int status) {
        this.lines = lines;
        this.status = status;
        this.reader = Thread.ofVirtual().start(this::read);
    }

    /** Returns once response headers arrive, i.e. after the server registered the subscription. */
    public static SseClient connect(String baseUrl, java.util.UUID location, Long lastEventId) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/stream?locationId=" + location))
                .header("Accept", "text/event-stream").GET();
        if (lastEventId != null) {
            req.header("Last-Event-ID", Long.toString(lastEventId));
        }
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<Stream<String>> r = http.send(req.build(), HttpResponse.BodyHandlers.ofLines());
        return new SseClient(r.body(), r.statusCode());
    }

    public int status() {
        return status;
    }

    public Event next(Duration timeout) throws InterruptedException {
        Event e = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (e == null) {
            throw new AssertionError("no SSE event within " + timeout);
        }
        return e;
    }

    public Event poll(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void read() {
        String id = null;
        String name = "message";
        StringBuilder data = new StringBuilder();
        try {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line.isEmpty()) {
                    if (id != null && !data.isEmpty()) {
                        events.add(new Event(Long.parseLong(id), name, MenuFixture.JSON.readTree(data.toString()), System.nanoTime()));
                    }
                    id = null;
                    name = "message";
                    data.setLength(0);
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        } catch (RuntimeException closed) {
            // stream closed by close()
        }
    }

    @Override
    public void close() {
        lines.close();
        reader.interrupt();
    }
}
