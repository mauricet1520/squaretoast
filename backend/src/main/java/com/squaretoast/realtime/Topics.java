package com.squaretoast.realtime;

/** SSE event names. Only {@link #AVAILABILITY} is public; the others name tables and dishes. */
public final class Topics {

    public static final String AVAILABILITY = "availability";
    public static final String COURSE = "course";
    public static final String ORDER = "order";

    private Topics() {}
}
