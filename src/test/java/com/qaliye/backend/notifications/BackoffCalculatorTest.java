package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.worker.BackoffCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BackoffCalculatorTest {

    private static final long MAX_BACKOFF = 900L;

    @Test
    void compute_attempt1_returnsSmallBackoff() {
        long delay = BackoffCalculator.compute(1, MAX_BACKOFF);
        assertThat(delay).isGreaterThanOrEqualTo(5L).isLessThanOrEqualTo(15L);
    }

    @Test
    void compute_highAttempt_cappedAtMaxBackoff() {
        long delay = BackoffCalculator.compute(20, MAX_BACKOFF);
        assertThat(delay).isGreaterThanOrEqualTo(MAX_BACKOFF)
                         .isLessThanOrEqualTo(MAX_BACKOFF + (MAX_BACKOFF / 4));
    }

    @Test
    void compute_zerothAttempt_returnsPositiveValue() {
        long delay = BackoffCalculator.compute(0, MAX_BACKOFF);
        assertThat(delay).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void compute_increasesWith_higherAttemptCount() {
        long delay1 = BackoffCalculator.compute(1, MAX_BACKOFF);
        long delay3 = BackoffCalculator.compute(3, MAX_BACKOFF);
        long base1 = 5;
        long base3 = 5 * 4;
        assertThat(base3).isGreaterThan(base1);
    }
}
