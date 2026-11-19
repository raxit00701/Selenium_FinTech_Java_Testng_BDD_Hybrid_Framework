package utils.Videocapture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation to mark tests that require video recording.
 * The listener will read this and save the video ONLY on failure or skip.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Video {
    
    boolean captureOnFailure() default true;
    boolean captureOnSkip() default true;
    
}