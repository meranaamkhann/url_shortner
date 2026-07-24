package com.urlshortener.integration;

import com.urlshortener.kafka.AuditEventProducer;
import com.urlshortener.kafka.ClickEventProducer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for "*IT" integration tests, run by the Failsafe plugin during
 * `mvn verify` (separately from the fast unit-test suite run by Surefire during
 * `mvn test` — see pom.xml). Spins up real Postgres + Redis containers so these
 * tests exercise actual SQL execution, Flyway migrations, and Redis cache
 * behavior rather than H2/mocks — catching the class of bug that only shows up
 * against the real database engine (e.g. a partial index or JSONB column type
 * that H2 would silently accept differently).
 *
 * Kafka is intentionally mocked (@MockBean) rather than containerized here:
 * click-event publishing is fire-and-forget from the API's perspective, so
 * these tests verify the publish *call* happens, without paying the cost
 * (and CI flakiness risk) of a full Kafka+Zookeeper container for every test run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(SpringExtension.class)
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("urlshortener_it")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    protected MockMvc mockMvc;

    @MockBean
    protected ClickEventProducer clickEventProducer;

    @MockBean
    protected AuditEventProducer auditEventProducer;
}
