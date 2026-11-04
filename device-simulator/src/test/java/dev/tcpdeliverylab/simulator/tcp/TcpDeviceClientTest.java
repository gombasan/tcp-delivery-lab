package dev.tcpdeliverylab.simulator.tcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import dev.tcpdeliverylab.protocol.PacketFormat;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TcpDeviceClientTest {
    @ParameterizedTest
    @ValueSource(longs = {0, 1000})
    @DisplayName("연결 후 지정한 시간 동안 전송을 기다리고 암호화된 프레임과 정상 응답을 주고받습니다.")
    void sendsEncryptedFrameAndReceivesAcknowledgement(long delayMillis) throws Exception {
        AesGcmCodec codec = new AesGcmCodec(new byte[PacketFormat.KEY_BYTES]);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            Future<String> received = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    if (delayMillis > 0) {
                        socket.setSoTimeout(200);
                        assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
                    }
                    socket.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    int length = input.readInt();
                    PacketFormat.validateBodyLength(length);
                    byte[] body = new byte[length];
                    input.readFully(body);
                    String message = new String(codec.decrypt(body), StandardCharsets.UTF_8);
                    socket.getOutputStream().write("OK\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return message;
                }
            });

            TcpDeviceClient client = new TcpDeviceClient(
                    server.getInetAddress().getHostAddress(), server.getLocalPort(),
                    1000, 3000, codec);
            client.send("GW001,23.5", delayMillis);

            assertEquals("GW001,23.5", received.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("정상 응답이 아니면 전송을 성공으로 처리하지 않습니다.")
    void rejectsUnexpectedAcknowledgement() throws Exception {
        AesGcmCodec codec = new AesGcmCodec(new byte[PacketFormat.KEY_BYTES]);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            Future<?> response = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    int length = input.readInt();
                    PacketFormat.validateBodyLength(length);
                    input.readFully(new byte[length]);
                    socket.getOutputStream().write("NG\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });

            TcpDeviceClient client = new TcpDeviceClient(
                    server.getInetAddress().getHostAddress(), server.getLocalPort(),
                    1000, 3000, codec);
            assertThrows(IOException.class, () -> client.send("GW001,23.5"));
            response.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
