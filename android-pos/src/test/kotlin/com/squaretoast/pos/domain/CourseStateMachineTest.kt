package com.squaretoast.pos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CourseStateMachineTest {

    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("course-transitions.json")).readText(),
    ).jsonObject["transitions"]!!.jsonArray

    @Test
    fun vectorsCoverEveryStateActionPair() {
        assertEquals(CourseState.entries.size * CourseAction.entries.size, vectors.size)
    }

    @Test
    fun followsSharedVectors() {
        for (v in vectors) {
            val o = v.jsonObject
            val from = CourseState.valueOf(o["from"]!!.jsonPrimitive.content)
            val action = CourseAction.valueOf(o["action"]!!.jsonPrimitive.content)
            val to = o["to"]!!.jsonPrimitive.contentOrNull
            if (to == null) {
                val e = assertThrows("$from + $action", IllegalCourseTransitionException::class.java) {
                    CourseStateMachine.next(from, action)
                }
                assertEquals(from, e.from)
                assertEquals(action, e.action)
            } else {
                assertEquals("$from + $action", CourseState.valueOf(to), CourseStateMachine.next(from, action))
            }
        }
    }
}
