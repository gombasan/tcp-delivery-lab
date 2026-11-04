package dev.tcpdeliverylab.simulator.tcp;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import dev.tcpdeliverylab.protocol.TcpFrameCodec;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TcpDeviceClient {
    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final AesGcmCodec codec;

    public void send(String message) throws IOException, GeneralSecurityException {
        send(message, 0);
    }

    public void send(String message, long sendDelayMillis) throws IOException, GeneralSecurityException {
        if (sendDelayMillis < 0) {
            throw new IllegalArgumentException("전송 지연 시간은 음수일 수 없습니다.");
        }
        byte[] body = codec.encrypt(message.getBytes(StandardCharsets.UTF_8));
        byte[] frame = TcpFrameCodec.encode(body);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);

            // 연결은 유지하되 데이터를 늦게 보내는 장비를 재현한다.
            if (sendDelayMillis > 0) {
                try {
                    Thread.sleep(sendDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("데이터 전송 전 대기가 중단되었습니다.", e);
                }
            }

            socket.getOutputStream().write(frame);
            socket.getOutputStream().flush();

            // 서버 응답확인.
            byte[] response = new byte[3];
            new DataInputStream(socket.getInputStream()).readFully(response);
            if (!Arrays.equals(response, "OK\n".getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("서버의 응답이 올바르지 않습니다.");
            }
        }
    }
}
