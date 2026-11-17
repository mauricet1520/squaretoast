package com.squaretoast.realtime;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE stream for POS/QR/KDS clients; EventSource resends Last-Event-ID on reconnect. */
@RestController
class SseController {

    private final SseBroadcaster broadcaster;

    SseController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", version = "1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestParam UUID locationId,
                      @RequestHeader(name = "Last-Event-ID", required = false) @Nullable Long lastEventId) {
        return broadcaster.subscribe(locationId, lastEventId);
    }
}
