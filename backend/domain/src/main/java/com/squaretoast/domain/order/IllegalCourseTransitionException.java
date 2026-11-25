package com.squaretoast.domain.order;

public final class IllegalCourseTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final CourseState from;
    private final CourseAction action;

    public IllegalCourseTransitionException(CourseState from, CourseAction action) {
        super("cannot " + action + " a " + from + " course");
        this.from = from;
        this.action = action;
    }

    public CourseState from() {
        return from;
    }

    public CourseAction action() {
        return action;
    }
}
