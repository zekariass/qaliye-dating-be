package com.qaliye.backend.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "discovery")
public class DiscoveryProperties {

    private Rewind rewind = new Rewind();
    private Queue queue = new Queue();
    private Cursor cursor = new Cursor();
    private Distance distance = new Distance();

    public Rewind getRewind() { return rewind; }
    public void setRewind(Rewind rewind) { this.rewind = rewind; }

    public Queue getQueue() { return queue; }
    public void setQueue(Queue queue) { this.queue = queue; }

    public Cursor getCursor() { return cursor; }
    public void setCursor(Cursor cursor) { this.cursor = cursor; }

    public Distance getDistance() { return distance; }
    public void setDistance(Distance distance) { this.distance = distance; }

    public static class Rewind {
        private int matchGracePeriodMinutes = 10;
        public int matchGracePeriodMinutes() { return matchGracePeriodMinutes; }
        public void setMatchGracePeriodMinutes(int v) { this.matchGracePeriodMinutes = v; }
    }

    public static class Queue {
        private int batchSize = 10;
        public int batchSize() { return batchSize; }
        public void setBatchSize(int v) { this.batchSize = v; }
    }

    public static class Cursor {
        private int maxAgeMinutes = 30;
        public int maxAgeMinutes() { return maxAgeMinutes; }
        public void setMaxAgeMinutes(int v) { this.maxAgeMinutes = v; }
    }

    public static class Distance {
        private int minKm = 1;
        public int minKm() { return minKm; }
        public void setMinKm(int v) { this.minKm = v; }
    }
}
