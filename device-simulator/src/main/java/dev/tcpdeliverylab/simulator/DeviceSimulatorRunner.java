package dev.tcpdeliverylab.simulator;

import dev.tcpdeliverylab.simulator.tcp.TcpDeviceClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final int DEVICE_COUNT = 100;

    private final TcpDeviceClient client;

    @Value("${lab.device-id:GW001}")
    private String deviceId;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("장비 데이터 전송 실험을 시작합니다. => 동시 전송 장비 수: {}", DEVICE_COUNT);

        CountDownLatch ready = new CountDownLatch(DEVICE_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(DEVICE_COUNT);
        try {
            List<Future<SendResult>> futures = new ArrayList<>();
            for (int i = 1; i <= DEVICE_COUNT; i++) {
                String currentDeviceId = deviceId + "-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sendOnce(currentDeviceId);
                }));
            }

            // 모든 장비가 준비되면 함께 출발시킨다. 실제 실행 시점은 OS 스케줄링에 따라 다를 수 있다.
            ready.await();
            long startedAt = System.nanoTime();
            start.countDown();

            List<SendResult> results = new ArrayList<>();
            for (Future<SendResult> future : futures) {
                results.add(future.get());
            }
            long totalMillis = (System.nanoTime() - startedAt) / 1_000_000;

            int successCount = 0;
            int timeoutCount = 0;
            int failureCount = 0;
            LongSummaryStatistics durations = new LongSummaryStatistics();

            // 모든 전송이 끝난 뒤 기록하여 요청별 로그 출력이 실험에 끼치는 영향을 줄인다.
            // 출력은 제출 순서이며, 실제 완료 순서를 뜻하지 않는다.
            for (SendResult result : results) {
                durations.accept(result.elapsedNanos());
                switch (result.outcome()) {
                    case SUCCESS -> successCount++;
                    case TIMEOUT -> timeoutCount++;
                    case FAILURE -> failureCount++;
                }
                log.info("장비 전송 결과를 확인합니다. => 장비: {}, 결과: {}, 소요 시간: {}ms, 오류: {}",
                        result.deviceId(), result.outcome().description,
                        result.elapsedNanos() / 1_000_000.0, result.error());
            }

            log.info("장비 전송 실험을 완료했습니다. => 전체: {}, 성공: {}, 타임아웃: {}, 기타 실패: {}, 전체 소요 시간: {}ms",
                    DEVICE_COUNT, successCount, timeoutCount, failureCount, totalMillis);
            log.info("요청별 소요 시간을 확인합니다. => 최소: {}ms, 평균: {}ms, 최대: {}ms",
                    durations.getMin() / 1_000_000.0,
                    durations.getAverage() / 1_000_000.0,
                    durations.getMax() / 1_000_000.0);
        } finally {
            executor.shutdownNow();
        }
    }

    private SendResult sendOnce(String currentDeviceId) {
        // 출발 신호를 받은 뒤 암호화, 연결, 전송과 응답 대기를 측정한다.
        long startedAt = System.nanoTime();
        try {
            client.send(currentDeviceId + ",23.5");
            return new SendResult(currentDeviceId, System.nanoTime() - startedAt, Outcome.SUCCESS, "없음");
        } catch (SocketTimeoutException e) {
            return new SendResult(currentDeviceId, System.nanoTime() - startedAt,
                    Outcome.TIMEOUT, e.getClass().getSimpleName());
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return new SendResult(currentDeviceId, System.nanoTime() - startedAt,
                    Outcome.FAILURE, e.getClass().getSimpleName());
        }
    }

    private enum Outcome {
        SUCCESS("성공"), TIMEOUT("타임아웃"), FAILURE("실패");

        private final String description;

        Outcome(String description) {
            this.description = description;
        }
    }

    private record SendResult(String deviceId, long elapsedNanos, Outcome outcome, String error) {
    }
}
