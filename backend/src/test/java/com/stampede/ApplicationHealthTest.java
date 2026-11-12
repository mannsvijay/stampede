package com.stampede;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.testsupport.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Boots the whole application against the real test Postgres and Redis (TestDatabases) and checks
 * that /actuator/health reports both connections as UP. Doesn't depend on docker-compose or .env.
 * Same configuration as ApiTestSupport, so it shares that cached context instead of starting its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApplicationHealthTest {

    @Autowired
    TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void healthIsUpWithDatabaseAndRedisConnected() {
        ResponseEntity<Map> response = http.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", "UP");

        Map<String, Map<String, Object>> components = (Map<String, Map<String, Object>>) body.get("components");
        assertThat(components.get("db")).containsEntry("status", "UP");
        assertThat(components.get("redis")).containsEntry("status", "UP");
    }
}
