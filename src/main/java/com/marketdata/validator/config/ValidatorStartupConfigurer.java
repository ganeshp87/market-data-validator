package com.marketdata.validator.config;

import com.marketdata.validator.validator.ValidatorEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.Map;

/**
 * Applies validator threshold properties from application.properties to the
 * ValidatorEngine at startup. Runs before connections are auto-loaded (@Order(1))
 * so the first ticks from restored connections already use the configured thresholds.
 *
 * The runtime PUT /api/validation/config endpoint continues to work and overrides
 * these startup values for the duration of the server's lifetime.
 */
@Configuration
public class ValidatorStartupConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ValidatorStartupConfigurer.class);

    @Bean
    @Order(1)
    CommandLineRunner applyValidatorStartupConfig(ValidatorEngine engine,
                                                   ValidatorProperties props) {
        return args -> {
            engine.configure("LATENCY",
                    Map.of("thresholdMs", props.getLatency().getWarnThresholdMs()));

            engine.configure("ACCURACY",
                    Map.of("largeMovePercent",
                            String.valueOf(props.getAccuracy().getSpikePctThreshold() / 100.0)));

            engine.configure("COMPLETENESS",
                    Map.of("heartbeatThresholdMs", props.getCompleteness().getStaleThresholdMs()));

            engine.configure("THROUGHPUT",
                    Map.of("zeroThresholdSecs", props.getThroughput().getMinMessagesPerSecond()));

            log.info("Validator thresholds applied from application.properties: " +
                            "latency.warnThresholdMs={}, accuracy.spikePctThreshold={}%, " +
                            "completeness.staleThresholdMs={}ms, throughput.minMessagesPerSecond={}",
                    props.getLatency().getWarnThresholdMs(),
                    props.getAccuracy().getSpikePctThreshold(),
                    props.getCompleteness().getStaleThresholdMs(),
                    props.getThroughput().getMinMessagesPerSecond());
        };
    }
}
