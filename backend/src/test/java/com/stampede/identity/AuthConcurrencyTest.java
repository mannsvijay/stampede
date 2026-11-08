package com.stampede.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        List<Response> responses = race(() -> refresh(token));

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
     * Refreshes and logouts of the same token racing: no 500s, and the family ends up revoked, so
     * no token a racing refresh handed out survives the logout.
     */
    @RepeatedTest(10)
    void concurrentRefreshAndLogoutOfOneTokenNeverFail() throws Exception {
        Response login = registerAndLogin(uniqueEmail());
        String token = login.refreshCookie();
        AtomicInteger next = new AtomicInteger();

        List<Response> responses = race(() -> next.getAndIncrement() % 2 == 0 ? refresh(token) : logout(token));

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

        List<Response> responses = race(() -> register(email, PASSWORD));

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 409)
                .hasSize(THREADS - 1)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("EMAIL_TAKEN"));
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = ?")
                .param(email).query(Integer.class).single()).isEqualTo(1);
    }

    private List<Response> race(Callable<Response> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
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
