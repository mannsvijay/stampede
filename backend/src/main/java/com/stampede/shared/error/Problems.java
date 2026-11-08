package com.stampede.shared.error;

import org.springframework.http.ProblemDetail;

/** Builds RFC 7807 problem details that always carry the stable {@code code} property. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        return problem;
    }
}
