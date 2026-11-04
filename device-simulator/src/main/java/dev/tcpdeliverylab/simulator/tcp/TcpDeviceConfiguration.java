package dev.tcpdeliverylab.simulator.tcp;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lab.transport", havingValue = "tcp", matchIfMissing = true)
public class TcpDeviceConfiguration {
    @Bean
    public AesGcmCodec aesGcmCodec(@Value("${lab.tcp.aes-key-base64:}") String encodedKey) {
        if (encodedKey.isBlank()) {
            throw new IllegalArgumentException("TCP_AES_KEY_BASE64 설정이 없습니다. 24바이트 키를 Base64로 인코딩하여 설정하세요.");
        }
        return new AesGcmCodec(Base64.getDecoder().decode(encodedKey));
    }

    @Bean
    public TcpDeviceClient tcpDeviceClient(
            @Value("${lab.tcp.host:localhost}") String host,
            @Value("${lab.tcp.port:9090}") int port,
            @Value("${lab.tcp.connect-timeout-millis:3000}") int connectTimeoutMillis,
            @Value("${lab.tcp.read-timeout-millis:3000}") int readTimeoutMillis,
            AesGcmCodec codec
    ) {
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("타임아웃은 0보다 커야 합니다.");
        }
        return new TcpDeviceClient(host, port, connectTimeoutMillis, readTimeoutMillis, codec);
    }
}
