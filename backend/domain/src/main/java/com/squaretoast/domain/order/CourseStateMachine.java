package com.squaretoast.domain.order;

/** Kitchen coursing. RECALL steps back one state so every bump can be undone. Vectors: shared-test-vectors. */
public final class CourseStateMachine {

    private CourseStateMachine() {}

    public static CourseState next(CourseState from, CourseAction action) {
        return switch (from) {
            case HOLD -> action == CourseAction.FIRE ? CourseState.FIRED : illegal(from, action);
            case FIRED -> switch (action) {
                case UNFIRE -> CourseState.HOLD;
                case BUMP -> CourseState.READY;
                default -> illegal(from, action);
            };
            case READY -> switch (action) {
                case RECALL -> CourseState.FIRED;
                case SERVE -> CourseState.SERVED;
                default -> illegal(from, action);
            };
            case SERVED -> action == CourseAction.RECALL ? CourseState.READY : illegal(from, action);
        };
    }

    private static CourseState illegal(CourseState from, CourseAction action) {
        throw new IllegalCourseTransitionException(from, action);
    }
}
