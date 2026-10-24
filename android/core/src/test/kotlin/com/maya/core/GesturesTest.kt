package com.maya.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GesturesTest {
    @Test fun gesture_classifier_tap_drag_fling() {
        assertEquals(Gesture.TAP, classifyGesture(3f, 120, 0f, 8f))
        assertEquals(Gesture.DRAG, classifyGesture(200f, 900, 300f, 8f))
        assertEquals(Gesture.FLING, classifyGesture(200f, 200, 4000f, 8f))
        assertEquals(Gesture.DRAG, classifyGesture(3f, 800, 0f, 8f)) // long press without moving is not a tap
    }

    @Test fun boundaries() {
        assertEquals(Gesture.DRAG, classifyGesture(8f, 100, 0f, 8f)) // distance == slop is not a tap
        assertEquals(Gesture.DRAG, classifyGesture(3f, 300, 0f, 8f)) // duration == 300 is not a tap
        assertEquals(Gesture.DRAG, classifyGesture(200f, 200, 2500f, 8f)) // speed == 2500 is not a fling
    }
}
