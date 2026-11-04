package dev.tcpdeliverylab.simulator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.tcpdeliverylab.simulator.tcp.TcpDeviceClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class DeviceSimulatorRunnerTest {
    @Test
    @DisplayName("100개 장비가 각각 한 건을 병렬 전송하고 성공, 타임아웃과 기타 실패를 구분하여 집계합니다.")
    void aggregatesConcurrentResults(CapturedOutput output) throws Exception {
        TcpDeviceClient client = mock(TcpDeviceClient.class);
        AtomicInteger entered = new AtomicInteger();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(99);
        doAnswer(invocation -> {
            int index = entered.getAndIncrement();
            started.countDown();
            if (!started.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("전송 작업이 동시에 실행되지 않았습니다.");
            }
            if (index == 1) {
                throw new SocketTimeoutException("테스트 응답 대기 시간이 초과되었습니다.");
            }
            if (index == 2) {
                throw new IOException("테스트 연결에 실패했습니다.");
            }
            return null;
        }).when(client).send(anyString());

        DeviceSimulatorRunner runner = new DeviceSimulatorRunner(client);
        ReflectionTestUtils.setField(runner, "deviceId", "GW001");
        Instant beforeRun = Instant.now();
        runner.run(new DefaultApplicationArguments());
        Instant afterRun = Instant.now();

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(client, times(99)).send(messages.capture());
        verify(client).send(messages.capture(), eq(5000L));
        assertThat(messages.getAllValues()).hasSize(100).doesNotHaveDuplicates()
                .contains("GW001-1,23.5", "GW001-100,23.5");
        assertThat(output).contains("전체: 100, 성공: 98, 타임아웃: 1, 기타 실패: 1");
        assertThat(output).contains("요청별 소요 시간을 확인합니다.");
        assertThat(output).contains("오류: SocketTimeoutException, 오류 메시지: 테스트 응답 대기 시간이 초과되었습니다.");
        assertThat(output).contains("오류: IOException, 오류 메시지: 테스트 연결에 실패했습니다.");
        var failureTimes = output.getOut().lines()
                .filter(line -> line.contains("결과: 타임아웃") || line.contains("결과: 실패"))
                .map(line -> Instant.parse(line.substring(line.indexOf("실패 시각: ") + "실패 시각: ".length())))
                .toList();
        assertThat(failureTimes).hasSize(2)
                .allSatisfy(time -> assertThat(time).isBetween(beforeRun, afterRun));
    }
}
