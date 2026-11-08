package com.stampede.testsupport;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampede.shared.security.CurrentUser;

/**
 * Test-only endpoints (never shipped): one route that needs any valid token and one under /admin,
 * since no real admin endpoint exists until M2.3. Both echo the resolved CurrentUser.
 */
@RestController
class ProbeController {

    @GetMapping("/me/_probe")
    Map<String, String> me(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("userId", user.userId().toString(), "role", user.role().name());
    }

    @GetMapping("/admin/_probe")
    Map<String, String> admin(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("userId", user.userId().toString(), "role", user.role().name());
    }
}
