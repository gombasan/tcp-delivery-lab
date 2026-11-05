package dev.tcpdeliverylab.collector.tcp;

import static org.junit.jupiter.api.Assertions.*;
import dev.tcpdeliverylab.protocol.AesGcmCodec;
import dev.tcpdeliverylab.protocol.TcpFrameCodec;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TcpReceiverTest {
    private AesGcmCodec codec;
    private TcpReceiver receiver;
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void start() throws Exception {
        byte[] key = new byte[24];
        new SecureRandom().nextBytes(key);
        codec = new AesGcmCodec(key);
        receiver = new TcpReceiver("127.0.0.1", 0, 1000, codec, received::add);
        receiver.start();
    }

    @AfterEach
    void stop() throws Exception {
        receiver.close();
    }

    @Test
    @DisplayName("프레임을 나누어 전송해도 원문을 수신하고 정상 응답합니다.")
    void receivesFrameWrittenInSmallPieces() throws Exception {
        String message = "GW001,23.5";
        byte[] packet = codec.encrypt(message.getBytes(StandardCharsets.UTF_8));
        byte[] frame = ByteBuffer.allocate(4 + packet.length).putInt(packet.length).put(packet).array();
        try (Socket socket = connect()) {
            for (byte value : frame) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
            socket.shutdownOutput();
            assertEquals("OK\n", new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
            assertEquals(message, received.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("허용 길이를 초과한 프레임을 거부하고 다음 연결을 처리합니다.")
    void rejectsOversizedFrameAndAcceptsNextConnection() throws Exception {
        try (Socket socket = connect()) {
            new DataOutputStream(socket.getOutputStream()).writeInt(4097);
            socket.shutdownOutput();
            assertEquals(0, socket.getInputStream().readAllBytes().length);
            assertTrue(received.isEmpty());
        }
        sendValidMessage();
    }

    @Test
    @DisplayName("변조된 프레임은 수신 콜백을 실행하지 않고 다음 연결을 처리합니다.")
    void rejectsTamperedFrameWithoutDeliveringPlaintext() throws Exception {
        byte[] packet = codec.encrypt("GW001,23.5".getBytes(StandardCharsets.UTF_8));
        packet[packet.length - 1] ^= 1;
        try (Socket socket = connect()) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(packet.length);
            out.write(packet);
            out.flush();
            socket.shutdownOutput();
            assertEquals(0, socket.getInputStream().readAllBytes().length);
            assertTrue(received.isEmpty());
        }
        sendValidMessage();
    }

    @Test
    @DisplayName("전송 중 잘린 프레임을 거부하고 다음 연결을 처리합니다.")
    void rejectsTruncatedFrameAndKeepsListening() throws Exception {
        try (Socket socket = connect()) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(40);
            out.write(new byte[] {1, 2});
            out.flush();
            socket.shutdownOutput();
            assertEquals(0, socket.getInputStream().readAllBytes().length);
            assertTrue(received.isEmpty());
        }
        sendValidMessage();
    }

    @Test
    @DisplayName("한 연결의 길이 필드가 미완성인 동안 다른 연결의 메시지를 처리합니다.")
    void receivesAnotherMessageWhileLengthIsIncomplete() throws Exception {
        String message = "GW001,23.5";
        byte[] packet = codec.encrypt(message.getBytes(StandardCharsets.UTF_8));
        byte[] frame = TcpFrameCodec.encode(packet);

        try (Socket slow = connect()) {
            slow.getOutputStream().write(frame, 0, 2);

            sendValidMessage();

            slow.getOutputStream().write(frame, 2, frame.length - 2);
            assertEquals("OK\n", new String(slow.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
            assertEquals(message, received.poll(2, TimeUnit.SECONDS));
        }
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", receiver.localPort());
        socket.setSoTimeout(3000);
        return socket;
    }

    private void sendValidMessage() throws Exception {
        byte[] packet = codec.encrypt("GW002,18.0".getBytes(StandardCharsets.UTF_8));
        try (Socket socket = connect()) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(TcpFrameCodec.encode(packet));
            out.flush();
            assertEquals("OK\n", new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
            assertEquals("GW002,18.0", received.poll(2, TimeUnit.SECONDS));
        }
    }
}
