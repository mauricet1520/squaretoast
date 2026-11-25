package com.squaretoast.realtime;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Public SSE stream of 86 availability for guest menus. Course and order events, which name tables and dishes,
 * go only to the authenticated KDS stream ({@link RealtimeStreams}).
 */
@RestController
class SseController {

    private final SseBroadcaster broadcaster;

    SseController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", version = "1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestParam UUID locationId,
                      @RequestHeader(name = "Last-Event-ID", required = false) @Nullable Long lastEventId) {
        return broadcaster.subscribe(locationId, lastEventId, Topics.AVAILABILITY::equals);
    }
}
