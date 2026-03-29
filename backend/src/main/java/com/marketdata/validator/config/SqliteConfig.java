package com.marketdata.validator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * SQLite database initialization.
 * Reads schema.sql from classpath and executes it on startup.
 *
 * Why a custom initializer instead of spring.sql.init.mode=always?
 *   - Spring's auto-init runs BEFORE JdbcTemplate is ready in some configurations
 *   - We want explicit error handling and logging
 *   - CommandLineRunner runs after full context is loaded — guaranteed safe
 */
@Configuration
public class SqliteConfig {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

    @Bean
    @Order(1)
    CommandLineRunner initDatabase(JdbcTemplate jdbcTemplate) {
        return args -> {
            // Ensure the data directory exists for SQLite
            Files.createDirectories(Path.of("data"));

            log.info("Initializing SQLite database schema...");

            ClassPathResource resource = new ClassPathResource("schema.sql");
            String sql;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                sql = reader.lines().collect(Collectors.joining("\n"));
            }

            // Strip SQL comments (lines starting with --)
            String cleaned = sql.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));

            // Split on semicolons and execute each statement
            String[] statements = cleaned.split(";");
            int count = 0;
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    log.debug("Executing SQL: {}", trimmed.substring(0, Math.min(60, trimmed.length())));
                    jdbcTemplate.execute(trimmed);
                    count++;
                }
            }

            log.info("SQLite schema initialized: {} statements executed", count);

            // Production safety PRAGMAs
            jdbcTemplate.execute("PRAGMA journal_mode = WAL");
            jdbcTemplate.execute("PRAGMA busy_timeout = 5000");
            log.info("SQLite PRAGMAs set: journal_mode=WAL, busy_timeout=5000");
        };
    }
}
