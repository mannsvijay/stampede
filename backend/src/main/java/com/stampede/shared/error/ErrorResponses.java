package com.stampede.shared.error;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents which error codes a controller method can return. The OpenAPI generator turns each code
 * into a problem+json response under the code's own HTTP status, so the documented status can't drift
 * from {@link ErrorCode#status()}. Every operation also gets {@link ErrorCode#INTERNAL_ERROR}
 * automatically. Documentation only: it changes no runtime behaviour.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ErrorResponses {
    ErrorCode[] value();
}
