package com.github.tatercertified.nalim.rust;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a native function's FFM downcall as critical: no thread-state transition, so the
 * JVM cannot reach a safepoint until it returns (Nalim calls always behave like this).
 * Only for short, bounded functions that never wait on a lock another call can hold long;
 * everything else keeps the normal transition, which costs a few nanoseconds per call.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Critical {
}
