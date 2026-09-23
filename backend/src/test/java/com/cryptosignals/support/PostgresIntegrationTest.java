package com.cryptosignals.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that need a real database.
 *
 * <p>A real PostgreSQL rather than an in-memory substitute: the schema uses {@code NUMERIC(38,18)},
 * {@code JSONB}, {@code ON CONFLICT} upserts, a partial index and a window function, none of which
 * an H2 compatibility mode would exercise faithfully. The container is static, so one instance is
 * shared by every integration test in the run.
 *
 * <p>Hibernate runs with {@code ddl-auto: validate}, so merely starting the context proves that the
 * Flyway migrations and the JPA entity mappings agree.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cryptosignals")
                    .withUsername("cryptosignals")
                    .withPassword("cryptosignals");
}
