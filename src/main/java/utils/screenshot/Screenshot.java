package utils.screenshot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation to mark tests that require screenshots.
 * The listener will read this and capture the image on failure or skip.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Screenshot {
    
    // Allows the listener to know when to trigger the screenshot
    boolean captureOnFailure() default true;
    boolean captureOnSkip() default true;
}