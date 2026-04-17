package com.marketdata.validator.store;

import com.marketdata.validator.model.Alert;
import com.marketdata.validator.model.Alert.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertStoreTest {

    @Autowired
    private AlertStore alertStore;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
    }

    // --- Save and retrieve ---

    @Test
    void saveAndFindById() {
        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "p95 exceeded 500ms");
        alertStore.save(alert);

        Optional<Alert> found = alertStore.findById(alert.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getArea()).isEqualTo("LATENCY");
        assertThat(found.get().getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(found.get().getMessage()).isEqualTo("p95 exceeded 500ms");
        assertThat(found.get().isAcknowledged()).isFalse();
    }

    @Test
    void saveAssignsId() {
        Alert alert = new Alert("ACCURACY", Severity.WARN, "10% price move");
        alertStore.save(alert);
        assertThat(alert.getId()).isGreaterThan(0);
    }

    @Test
    void findAll_orderedByCreatedAtDesc() {
        Alert a1 = new Alert("LATENCY", Severity.CRITICAL, "first");
        a1.setCreatedAt(Instant.parse("2024-01-01T10:00:00Z"));
        Alert a2 = new Alert("ACCURACY", Severity.WARN, "second");
        a2.setCreatedAt(Instant.parse("2024-01-01T10:00:01Z"));
        Alert a3 = new Alert("ORDERING", Severity.INFO, "third");
        a3.setCreatedAt(Instant.parse("2024-01-01T10:00:02Z"));
        alertStore.save(a1);
        alertStore.save(a2);
        alertStore.save(a3);

        List<Alert> all = alertStore.findAll();
        assertThat(all).hasSize(3);
        // DESC order: latest timestamp first
        assertThat(all.get(0).getMessage()).isEqualTo("third");
        assertThat(all.get(2).getMessage()).isEqualTo("first");
    }

    @Test
    void findById_notFound_returnsEmpty() {
        assertThat(alertStore.findById(999L)).isEmpty();
    }

    // --- Filtering ---

    @Test
    void findUnacknowledged() {
        Alert a1 = new Alert("LATENCY", Severity.CRITICAL, "unread");
        Alert a2 = new Alert("ACCURACY", Severity.WARN, "also unread");
        alertStore.save(a1);
        alertStore.save(a2);
        alertStore.acknowledge(a1.getId());

        List<Alert> unack = alertStore.findUnacknowledged();
        assertThat(unack).hasSize(1);
        assertThat(unack.get(0).getMessage()).isEqualTo("also unread");
    }

    @Test
    void findByArea() {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "slow"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "drift"));
        alertStore.save(new Alert("LATENCY", Severity.WARN, "spike"));

        List<Alert> latencyAlerts = alertStore.findByArea("LATENCY");
        assertThat(latencyAlerts).hasSize(2);
        assertThat(latencyAlerts).allMatch(a -> a.getArea().equals("LATENCY"));
    }

    // --- Acknowledge ---

    @Test
    void acknowledge_setsAcknowledgedTrue() {
        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "test");
        alertStore.save(alert);

        boolean result = alertStore.acknowledge(alert.getId());
        assertThat(result).isTrue();

        Optional<Alert> found = alertStore.findById(alert.getId());
        assertThat(found.get().isAcknowledged()).isTrue();
    }

    @Test
    void acknowledge_nonExistent_returnsFalse() {
        assertThat(alertStore.acknowledge(999L)).isFalse();
    }

    @Test
    void acknowledgeAll() {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "two"));

        boolean result = alertStore.acknowledgeAll();
        assertThat(result).isTrue();
        assertThat(alertStore.findUnacknowledged()).isEmpty();
    }

    // --- Delete ---

    @Test
    void delete_removesAlert() {
        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "test");
        alertStore.save(alert);

        boolean result = alertStore.delete(alert.getId());
        assertThat(result).isTrue();
        assertThat(alertStore.findById(alert.getId())).isEmpty();
    }

    @Test
    void delete_nonExistent_returnsFalse() {
        assertThat(alertStore.delete(999L)).isFalse();
    }

    @Test
    void deleteAll_clearsAllAlerts() {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "two"));

        int deleted = alertStore.deleteAll();
        assertThat(deleted).isEqualTo(2);
        assertThat(alertStore.findAll()).isEmpty();
    }

    // --- Count ---

    @Test
    void count_returnsTotal() {
        assertThat(alertStore.count()).isEqualTo(0);
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "two"));
        assertThat(alertStore.count()).isEqualTo(2);
    }

    @Test
    void countUnacknowledged() {
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "one"));
        Alert a2 = new Alert("ACCURACY", Severity.WARN, "two");
        alertStore.save(a2);
        alertStore.acknowledge(a2.getId());

        assertThat(alertStore.countUnacknowledged()).isEqualTo(1);
    }

    // --- Listener ---

    @Test
    void save_notifiesListeners() {
        List<Alert> received = new ArrayList<>();
        alertStore.addListener(received::add);

        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "test");
        alertStore.save(alert);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getId()).isEqualTo(alert.getId());
    }

    @Test
    void removeListener_stopsNotifications() {
        List<Alert> received = new ArrayList<>();
        Consumer<Alert> listener = received::add;
        alertStore.addListener(listener);
        alertStore.save(new Alert("LATENCY", Severity.CRITICAL, "first"));
        assertThat(received).hasSize(1);

        alertStore.removeListener(listener);
        alertStore.save(new Alert("ACCURACY", Severity.WARN, "second"));
        assertThat(received).hasSize(1); // Still 1 — listener was removed
    }

    @Test
    void listenerException_doesNotBreakSave() {
        alertStore.addListener(a -> { throw new RuntimeException("boom"); });

        Alert alert = new Alert("LATENCY", Severity.CRITICAL, "still saved");
        alertStore.save(alert);

        assertThat(alertStore.findById(alert.getId())).isPresent();
    }
}
