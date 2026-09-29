package com.cryptosignals.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * <p>A real PostgreSQL rather than an in-memory substitute: the schema uses {@code NUMERIC(38,18)},
 * {@code JSONB}, {@code ON CONFLICT} upserts, a partial index and a window function, none of which
 * an H2 compatibility mode would exercise faithfully.
 *
 * <p><b>One container for the whole test run</b>, started once in a static initialiser and never
 * stopped by a test class. Spring caches application contexts across test classes that share a
 * configuration; if each class started and stopped its own container, a later class would inherit
 * a cached context still pointing at the port of a container that no longer exists. Testcontainers
 * removes the container when the JVM exits.
 *
 * <p>Hibernate runs with {@code ddl-auto: validate}, so merely starting the context proves that the
 * Flyway migrations and the JPA entity mappings agree.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cryptosignals")
                    .withUsername("cryptosignals")
                    .withPassword("cryptosignals");

    static {
        POSTGRES.start();
    }
}
