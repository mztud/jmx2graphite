package io.logz.jmx2graphite;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalRetryExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRetryExecutor.class);

    public static <T> T executeWithRetry(Callable<T> callable, int maxRetries, long delayMillis) throws IllegalStateException, InterruptedException {
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                return callable.call();
            } catch (Exception e) {
                LOGGER.trace("Excution failed: " + e.getMessage());
                attempt++;
                if (attempt >= maxRetries) {
                    throw new IllegalStateException(e.getMessage());
                }
                LOGGER.info("Attempt " + attempt + " failed. Retrying in " + delayMillis + " ms...");
                Thread.sleep(delayMillis); // Wait before retrying
            }
        }
        throw new IllegalStateException("Retry limit reached without success.");
    }
}
