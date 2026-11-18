package com.squaretoast.domain.order;

/** POS-facing course states. The database also has PREPPING, which only the KDS uses. */
public enum CourseState { HOLD, FIRED, READY, SERVED }
