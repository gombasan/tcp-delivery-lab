package dev.tcpdeliverylab.simulator.mqtt;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "lab.transport", havingValue = "mqtt")
public class MqttDeviceRunner implements ApplicationRunner {
    @Value("${lab.mqtt.broker-uri}")
    private String brokerUri;
    @Value("${lab.mqtt.client-id}")
    private String clientId;
    @Value("${lab.mqtt.topic}")
    private String topic;
    @Value("${lab.mqtt.qos}")
    private int qos;
    @Value("${lab.mqtt.message-id:}")
    private String configuredMessageId;
    @Value("${lab.device-id}")
    private String deviceId;
    @Value("${lab.tcp.aes-key-base64}")
    private String encodedKey;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String messageId = configuredMessageId.isBlank() ? UUID.randomUUID().toString() : configuredMessageId;
        if (messageId.contains("|")) {
            throw new IllegalArgumentException("메시지 ID에는 구분자를 넣을 수 없습니다.");
        }
        AesGcmCodec codec = new AesGcmCodec(Base64.getDecoder().decode(encodedKey));
        String data = deviceId + ",23.5";
        byte[] payload = codec.encrypt((messageId + "|" + data).getBytes(StandardCharsets.UTF_8));

        try (MqttClient client = new MqttClient(brokerUri, clientId, new MemoryPersistence())) {
            client.setTimeToWait(10000);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            client.connect(options);
            try {
                log.info("MQTT 메시지를 발행합니다. 토픽={}, 메시지 ID={}, 데이터={}, QoS={}",
                        topic, messageId, data, qos);
                client.publish(topic, payload, qos, false);
                log.info("MQTT 발행 절차를 완료했습니다. 메시지 ID={}, QoS={}", messageId, qos);
            } finally {
                client.disconnect();
            }
        }
    }
}
