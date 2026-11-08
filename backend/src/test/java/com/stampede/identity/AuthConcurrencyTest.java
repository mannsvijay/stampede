package com.stampede.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.RepeatedTest;

import com.stampede.testsupport.ApiTestSupport;

/**
 * Many requests racing on the same refresh token or the same email. All threads wait on a latch
 * and are released together, to maximise overlap.
 */
class AuthConcurrencyTest extends ApiTestSupport {

    private static final int THREADS = 20;

    /**
     * Exactly one request may consume a token. The others arrive after it was used, which is reuse,
     * so the family ends up revoked: under strict reuse detection (D-008) even the winner's new
     * token is dead. This is the "two tabs refreshing at once" case; M0.7 avoids it on the client.
     *
     * <p>Repeated 25 times: an early version, which revoked by updating every token row of the
     * family, deadlocked here (a 500) in roughly one run out of five.
     */
    @RepeatedTest(25)
    void concurrentRefreshesOfOneTokenHaveExactlyOneWinnerAndRevokeTheFamily() throws Exception {
        Response login = registerAndLogin(uniqueEmail());
        String token = login.refreshCookie();

        List<Response> responses = race(THREADS, i -> refresh(token));

        List<Response> winners = responses.stream().filter(r -> r.status() == 200).toList();
        assertThat(winners).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() != 200)
                .hasSize(THREADS - 1)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("INVALID_REFRESH_TOKEN"));

        // Exactly one child was created, and the family is revoked for REUSE.
        UUID userId = UUID.fromString(get("/me/_probe", login.accessToken()).body().path("userId").asText());
        Map<String, Object> family = jdbc.sql("""
                        SELECT f.revoke_reason, count(t.id) AS tokens
                        FROM refresh_token_families f JOIN refresh_tokens t ON t.family_id = f.id
                        WHERE f.user_id = ?
                        GROUP BY f.id
                        """)
                .param(userId).query().singleRow();
        assertThat(family).containsEntry("revoke_reason", "REUSE").containsEntry("tokens", 2L);
        assertThat(refresh(winners.get(0).refreshCookie()).status()).isEqualTo(401);
    }

    /**
     * One refresh against one logout of the same token. Whichever runs first, the logout must end
     * the session: the family is revoked for LOGOUT (a single refresh can never trigger reuse
     * detection, so nothing else can revoke it), and if the refresh won, the token it handed out
     * is already dead. This is the race the per-family lock was added for (D-008).
     */
    @RepeatedTest(30)
    void logoutRacingARefreshAlwaysEndsTheSession() throws Exception {
        Response login = registerAndLogin(uniqueEmail());
        String token = login.refreshCookie();
        List<Response> responses = race(2, i -> i == 0 ? refresh(token) : logout(token));

        Response refresh = responses.get(0);
        Response logout = responses.get(1);
        assertThat(logout.status()).isEqualTo(204);
        assertThat(refresh.status()).isIn(200, 401);
        UUID userId = UUID.fromString(get("/me/_probe", login.accessToken()).body().path("userId").asText());
        assertThat(jdbc.sql("SELECT revoke_reason FROM refresh_token_families WHERE user_id = ?")
                .param(userId).query(String.class).optional()).contains("LOGOUT");
        if (refresh.status() == 200) {
            assertThat(refresh(refresh.refreshCookie()).status())
                    .as("a token minted by a refresh that raced the logout must not survive it")
                    .isEqualTo(401);
        }
    }

    /**
     * Many refreshes and logouts of the same token at once: a load test for the lock, checking for
     * 500s (deadlocks) and that nothing survives. Reuse detection alone would revoke the family
     * here, so this test doesn't prove logout works; the test above does.
     */
    @RepeatedTest(10)
    void concurrentRefreshAndLogoutOfOneTokenNeverFail() throws Exception {
        Response login = registerAndLogin(uniqueEmail());
        String token = login.refreshCookie();
        List<Response> responses = race(THREADS, i -> i % 2 == 0 ? refresh(token) : logout(token));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isIn(200, 204, 401));
        assertThat(responses).filteredOn(r -> r.status() == 200).hasSizeLessThanOrEqualTo(1);
        UUID userId = UUID.fromString(get("/me/_probe", login.accessToken()).body().path("userId").asText());
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM refresh_token_families WHERE user_id = ?")
                .param(userId).query(Boolean.class).single()).isTrue();
        responses.stream().filter(r -> r.status() == 200).forEach(winner ->
                assertThat(refresh(winner.refreshCookie()).status()).isEqualTo(401));
    }

    @RepeatedTest(3)
    void concurrentRegistrationsOfOneEmailCreateExactlyOneUser() throws Exception {
        String email = uniqueEmail();

        List<Response> responses = race(THREADS, i -> register(email, PASSWORD));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409)
                .hasSize(THREADS - 1)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("EMAIL_TAKEN"));
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = ?")
                .param(email).query(Integer.class).single()).isEqualTo(1);
    }

    /** Runs {@code threads} requests at once; request(i) is thread i, and results come back in order of i. */
    private List<Response> race(int threads, IntFunction<Response> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.apply(index);
                }));
            }
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
