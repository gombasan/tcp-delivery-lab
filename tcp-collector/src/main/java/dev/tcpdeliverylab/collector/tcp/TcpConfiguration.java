package dev.tcpdeliverylab.collector.tcp;

import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lab.transport", havingValue = "tcp", matchIfMissing = true)
public class TcpConfiguration {

    @Bean
    public AesGcmCodec aesGcmCodec(@Value("${lab.tcp.aes-key-base64:}") String encodedKey) {

        if (encodedKey.isBlank()) {
            throw new IllegalArgumentException("TCP_AES_KEY_BASE64 설정이 없습니다. 24바이트 키를 Base64로 인코딩하여 설정하세요.");
        }

        return new AesGcmCodec(Base64.getDecoder().decode(encodedKey));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TcpReceiver tcpReceiver(
            @Value("${lab.tcp.host:127.0.0.1}") String host,
            @Value("${lab.tcp.port:9090}") int port,
            @Value("${lab.tcp.read-timeout-millis:3000}") int readTimeoutMillis,
            AesGcmCodec codec
    ) {
        return new TcpReceiver(
            host,
            port,
            readTimeoutMillis,
            codec,
            message -> log.info("장비 데이터를 수신했습니다. => {}", message)
        );
    }
}
