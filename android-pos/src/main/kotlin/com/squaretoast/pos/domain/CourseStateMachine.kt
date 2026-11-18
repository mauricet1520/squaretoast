package com.squaretoast.pos.domain

enum class CourseState { HOLD, FIRED, READY, SERVED }

enum class CourseAction { FIRE, UNFIRE, BUMP, RECALL, SERVE }

class IllegalCourseTransitionException(val from: CourseState, val action: CourseAction) :
    IllegalStateException("cannot $action a $from course")

/** Kitchen coursing. RECALL steps back one state so every KDS bump can be undone. Vectors: shared-test-vectors. */
object CourseStateMachine {
    fun next(from: CourseState, action: CourseAction): CourseState = when (from to action) {
        CourseState.HOLD to CourseAction.FIRE -> CourseState.FIRED
        CourseState.FIRED to CourseAction.UNFIRE -> CourseState.HOLD
        CourseState.FIRED to CourseAction.BUMP -> CourseState.READY
        CourseState.READY to CourseAction.RECALL -> CourseState.FIRED
        CourseState.READY to CourseAction.SERVE -> CourseState.SERVED
        CourseState.SERVED to CourseAction.RECALL -> CourseState.READY
        else -> throw IllegalCourseTransitionException(from, action)
    }
}
