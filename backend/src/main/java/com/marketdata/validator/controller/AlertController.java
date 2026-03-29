package com.marketdata.validator.controller;

import com.marketdata.validator.model.Alert;
import com.marketdata.validator.store.AlertStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertStore alertStore;

    public AlertController(AlertStore alertStore) {
        this.alertStore = alertStore;
    }

    @GetMapping
    public List<Alert> getAll() {
        return alertStore.findAll();
    }

    @GetMapping("/unacknowledged")
    public List<Alert> getUnacknowledged() {
        return alertStore.findUnacknowledged();
    }

    @GetMapping("/count")
    public long getUnacknowledgedCount() {
        return alertStore.countUnacknowledged();
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<?> acknowledge(@PathVariable long id) {
        boolean updated = alertStore.acknowledge(id);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/acknowledge-all")
    public ResponseEntity<?> acknowledgeAll() {
        alertStore.acknowledgeAll();
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        boolean deleted = alertStore.delete(id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<?> deleteAll() {
        alertStore.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
