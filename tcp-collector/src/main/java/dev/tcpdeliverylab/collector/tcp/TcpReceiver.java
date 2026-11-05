package dev.tcpdeliverylab.collector.tcp;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import dev.tcpdeliverylab.protocol.PacketFormat;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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
    private ServerSocketChannel listener;
    private Selector selector;
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

        ServerSocketChannel channel = ServerSocketChannel.open();

        Selector acceptSelector = null;
        try {
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(host, port));
            acceptSelector = Selector.open();
            channel.register(acceptSelector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException e) {
            try (channel) {
                try (Selector failedSelector = acceptSelector) {
                    throw e;
                }
            }
        }

        listener = channel;
        selector = acceptSelector;
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

        return listener.socket().getLocalPort();
    }

    private void receiveLoop() {

        while (running) {

            try {
                if (selector.select() == 0 || !running) {
                    continue;
                }

                // 이 Selector에는 리스너 하나만 등록되어 있다.
                selector.selectedKeys().clear();
                SocketChannel connection = listener.accept();
                if (connection == null) {
                    continue;
                }

                receive(connection.socket());
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                if (running) {
                    log.warn("TCP 메시지 처리 중 오류가 발생했습니다. => {}", e.getClass().getSimpleName());
                }
            } finally {
                activeSocket = null;
            }
        }
    }

    private void receive(Socket socket) throws IOException, GeneralSecurityException {
        try (socket) {

            activeSocket = socket;

            // 연결을 기다리는 사이 종료됐으면 수신 처리를 건너뛴다.
            if (!running) {
                return;
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
        }
    }

    private static String decodeUtf8(byte[] plaintext) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (selector != null) {
            selector.close();
        }
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
