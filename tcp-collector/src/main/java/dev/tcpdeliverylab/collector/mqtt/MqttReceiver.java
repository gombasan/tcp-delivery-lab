package dev.tcpdeliverylab.collector.mqtt;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "lab.transport", havingValue = "mqtt")
public class MqttReceiver implements AutoCloseable {
    private final MqttClient client;

    public MqttReceiver(
            @Value("${lab.mqtt.broker-uri}") String brokerUri,
            @Value("${lab.mqtt.client-id}") String clientId,
            @Value("${lab.mqtt.topic}") String topic,
            @Value("${lab.mqtt.qos}") int qos,
            @Value("${lab.mqtt.clean-session:false}") boolean cleanSession,
            @Value("${lab.tcp.aes-key-base64}") String encodedKey
    ) throws MqttException {
        AesGcmCodec codec = new AesGcmCodec(Base64.getDecoder().decode(encodedKey));
        client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
        client.setTimeToWait(10000);
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT 브로커 연결이 끊겼습니다. 수집 앱을 재시작하면 세션을 이어받습니다.", cause);
            }

            @Override
            public void messageArrived(String receivedTopic, MqttMessage message) throws Exception {
                byte[] plaintext = codec.decrypt(message.getPayload());
                String envelope = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
                int separator = envelope.indexOf('|');
                if (separator < 1) {
                    throw new IllegalArgumentException("메시지 ID가 없습니다.");
                }
                log.info("MQTT 장비 데이터를 수신했습니다. 토픽={}, 메시지 ID={}, 데이터={}, QoS={}, 재전달 표시={}",
                        receivedTopic, envelope.substring(0, separator), envelope.substring(separator + 1),
                        message.getQos(), message.isDuplicate());
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 수집 앱에서는 메시지를 발행하지 않는다.
            }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(cleanSession);
        options.setConnectionTimeout(5);
        try {
            client.connect(options);
            client.subscribe(topic, qos);
            log.info("MQTT 구독을 시작했습니다. 클라이언트 ID={}, 토픽={}, QoS={}, 세션 초기화={}",
                    clientId, topic, qos, cleanSession);
        } catch (MqttException | RuntimeException e) {
            try {
                close();
            } catch (MqttException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    @Override
    public void close() throws MqttException {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } finally {
            client.close();
        }
    }
}
