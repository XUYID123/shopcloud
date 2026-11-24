package org.spring.shopmessaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;

import java.time.Duration;

public final class RocketMqConnection {
    private RocketMqConnection() { }

    public static ClientConfiguration configure(String endpoints, boolean tls, String accessKey, String secretKey) {
        if (accessKey.isBlank() != secretKey.isBlank() || (!tls && !accessKey.isBlank())) {
            throw new IllegalArgumentException("RocketMQ credentials require both keys and TLS");
        }
        var client = ClientConfiguration.newBuilder().setEndpoints(endpoints)
                .enableSsl(tls).setRequestTimeout(Duration.ofSeconds(5));
        if (!accessKey.isBlank()) {
            client.setCredentialProvider(new StaticSessionCredentialsProvider(accessKey, secretKey));
        }
        return client.build();
    }
}
