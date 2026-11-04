package dev.tcpdeliverylab.simulator.tcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TcpDeviceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TcpDeviceConfiguration.class);
    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    @DisplayName("설정된 키로 암호화 객체와 TCP 송신 클라이언트를 빈으로 등록합니다.")
    void registersTcpBeans() {
        runner.withPropertyValues("lab.tcp.aes-key-base64=" + TEST_KEY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AesGcmCodec.class);
            assertThat(context).hasSingleBean(TcpDeviceClient.class);
        });
    }

    @Test
    @DisplayName("암호화 키 설정이 없으면 시작에 실패합니다.")
    void rejectsMissingKey() {
        runner.withPropertyValues("lab.tcp.aes-key-base64=").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("타임아웃이 0 이하이면 시작에 실패합니다.")
    void rejectsInvalidTimeout() {
        runner.withPropertyValues("lab.tcp.aes-key-base64=" + TEST_KEY,
                "lab.tcp.connect-timeout-millis=0").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("TCP 통신을 선택하지 않으면 TCP 전용 빈을 등록하지 않습니다.")
    void skipsTcpBeansForOtherTransport() {
        runner.withPropertyValues("lab.transport=mqtt").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TcpDeviceClient.class);
            assertThat(context).doesNotHaveBean(AesGcmCodec.class);
        });
    }
}
