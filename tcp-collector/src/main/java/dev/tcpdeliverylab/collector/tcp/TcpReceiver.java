package dev.tcpdeliverylab.collector.tcp;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import dev.tcpdeliverylab.protocol.PacketFormat;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/** First learning step: one connection, one encrypted UTF-8 message, one plain OK response. */
@Slf4j
public final class TcpReceiver implements AutoCloseable {
    private final String host;
    private final int port;
    private final int readTimeoutMillis;
    private final AesGcmCodec codec;
    private final Consumer<String> onMessage;
    private volatile boolean running;
    private volatile Socket activeSocket;
    private ServerSocket listener;
    private Thread worker;

    public TcpReceiver(
        String host,
        int port,
        int readTimeoutMillis,
        AesGcmCodec codec,
        Consumer<String> onMessage
    ) {

        if (readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("타임아웃은 0보다 커야 합니다.");
        }

        this.host = host;
        this.port = port;
        this.readTimeoutMillis = readTimeoutMillis;
        this.codec = codec;
        this.onMessage = onMessage;
    }

    public void start() throws IOException {

        if (listener != null) {
            throw new IllegalStateException("기존에 실행된 Listener 가 존재합니다.");
        }

        ServerSocket socket = new ServerSocket();

        try {
            socket.bind(new InetSocketAddress(host, port));
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }

        listener = socket;
        running = true;
        worker = new Thread(this::receiveLoop, "tcp-receiver");
        worker.setDaemon(true);
        worker.start();
        log.info("TCP 연결 수신 대기를 시작합니다. => {}:{}", host, localPort());
    }

    public int localPort() {

        if (listener == null) {
            throw new IllegalStateException("실행된 Listener 가 없습니다. start() 를 먼저 호출하세요.");
        }

        return listener.getLocalPort();
    }

    private void receiveLoop() {

        while (running) {

            try (Socket socket = listener.accept()) {

                activeSocket = socket;

                // 연결을 기다리는 사이 종료됐으면 수신 처리를 건너뛴다.
                if (!running) {
                    continue;
                }

                socket.setSoTimeout(readTimeoutMillis);
                DataInputStream input = new DataInputStream(socket.getInputStream());

                int length = input.readInt();

                PacketFormat.validateBodyLength(length);

                byte[] packet = new byte[length];

                input.readFully(packet);

                byte[] plaintext = codec.decrypt(packet);
                String message = decodeUtf8(plaintext);

                onMessage.accept(message);

                socket.getOutputStream().write("OK\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

            } catch (IOException | GeneralSecurityException | RuntimeException e) {

                if (running) {
                    log.warn("TCP 메시지 처리 중 오류가 발생했습니다. => {}", e.getClass().getSimpleName());
                }
            } finally {
                activeSocket = null;
            }
        }
    }

    private static String decodeUtf8(byte[] plaintext) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (listener != null) {
            listener.close();
        }
        Socket socket = activeSocket;
        if (socket != null) {
            socket.close();
        }
        if (worker != null) {
            try {
                worker.join(readTimeoutMillis + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
