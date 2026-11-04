package dev.tcpdeliverylab.simulator;

import dev.tcpdeliverylab.simulator.tcp.TcpDeviceClient;
import java.io.IOException;
import java.security.GeneralSecurityException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "lab.transport", havingValue = "tcp", matchIfMissing = true)
public class DeviceSimulatorRunner implements ApplicationRunner {
    private final TcpDeviceClient client;

    @Value("${lab.device-id:GW001}")
    private String deviceId;

    // Spring Boot 시작 준비가 끝나면 한 번 실행한다.
    @Override
    public void run(ApplicationArguments args) throws IOException, GeneralSecurityException {
        String message = deviceId + ",23.5";
        log.info("장비 데이터를 전송합니다. => {}", message);
        client.send(message);
        log.info("장비 데이터 전송을 완료했습니다. => {}", deviceId);
    }
}
