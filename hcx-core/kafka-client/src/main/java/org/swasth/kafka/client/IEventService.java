package org.swasth.kafka.client;

public interface IEventService {

    void send(String topic, String key, String message) throws Exception;
    boolean isHealthy();
}
