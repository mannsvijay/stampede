package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.testsupport.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M2.2a (PLAN note on M2.2): a full application context gets its own Redis key prefix from
 * {@code TestcontainersConfiguration} ({@code app_keys_N}), not the production default, so contexts
 * sharing the one test Redis never see each other's inventory keys. Same configuration as
 * ApiTestSupport, so it reuses that cached context.
 */
class RedisKeyPrefixInAppContextTest extends ApiTestSupport {

    @Autowired
    RedisHoldStore store;

    @Test
    void theApplicationsHoldStoreUsesThisContextsPrefix() {
        assertThat(store.keys().all()).matches("app_keys_\\d+:");
    }
}
