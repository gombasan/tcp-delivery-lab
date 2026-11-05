package dev.tcpdeliverylab.collector.tcp;

import dev.tcpdeliverylab.protocol.AesGcmCodec;
import dev.tcpdeliverylab.protocol.PacketFormat;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
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
        try {
            while (running) {
                selector.select(expireIdleReads());

                Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                while (running && selected.hasNext()) {
                    SelectionKey key = selected.next();
                    selected.remove();
                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            acceptConnection();
                        } else if (key.isReadable()) {
                            readMessage(key);
                        } else if (key.isWritable()) {
                            writeResponse(key);
                        }
                    } catch (IOException | GeneralSecurityException | RuntimeException e) {
                        if (key.channel() instanceof SocketChannel connection) {
                            closeConnection(connection);
                        }
                        log.warn("TCP 메시지 처리 중 오류가 발생했습니다. => {}", e.getClass().getSimpleName());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("TCP 연결을 기다리는 중 오류가 발생했습니다.", e);
        } finally {
            for (SelectionKey key : selector.keys()) {
                if (key.channel() instanceof SocketChannel connection) {
                    closeConnection(connection);
                }
            }
            try {
                selector.close();
            } catch (IOException e) {
                log.warn("Selector 종료 중 오류가 발생했습니다.", e);
            }
        }
    }

    private void acceptConnection() throws IOException {
        SocketChannel connection = listener.accept();
        if (connection == null) {
            return;
        }

        try {
            connection.configureBlocking(false);
            connection.register(selector, SelectionKey.OP_READ, new ConnectionState());
        } catch (IOException | RuntimeException e) {
            try (connection) {
                throw e;
            }
        }
    }

    private void readMessage(SelectionKey key) throws IOException, GeneralSecurityException {
        SocketChannel connection = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        int bytesRead = connection.read(state.input);
        if (bytesRead < 0) {
            throw new EOFException();
        }
        if (bytesRead == 0) {
            return;
        }

        state.lastReadNanos = System.nanoTime();
        if (state.input.hasRemaining()) {
            return;
        }

        if (state.readingLength) {
            state.input.flip();
            int length = state.input.getInt();
            PacketFormat.validateBodyLength(length);
            state.input = ByteBuffer.allocate(length);
            state.readingLength = false;
            return;
        }

        byte[] plaintext = codec.decrypt(state.input.array());
        onMessage.accept(decodeUtf8(plaintext));
        state.response = ByteBuffer.wrap("OK\n".getBytes(StandardCharsets.US_ASCII));
        writeResponse(key);
    }

    private void writeResponse(SelectionKey key) throws IOException {
        SocketChannel connection = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();
        connection.write(state.response);
        if (state.response.hasRemaining()) {
            key.interestOps(SelectionKey.OP_WRITE);
        } else {
            connection.close();
        }
    }

    private long expireIdleReads() {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis);
        long now = System.nanoTime();
        long nextTimeoutNanos = timeoutNanos;
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || !(key.attachment() instanceof ConnectionState state)
                    || state.response != null) {
                continue;
            }

            long remainingNanos = timeoutNanos - (now - state.lastReadNanos);
            if (remainingNanos <= 0) {
                closeConnection((SocketChannel) key.channel());
                log.warn("TCP 메시지 읽기 시간이 초과됐습니다.");
            } else {
                nextTimeoutNanos = Math.min(nextTimeoutNanos, remainingNanos);
            }
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nextTimeoutNanos));
    }

    private static void closeConnection(SocketChannel connection) {
        try {
            connection.close();
        } catch (IOException e) {
            log.warn("TCP 연결 종료 중 오류가 발생했습니다.", e);
        }
    }

    private static final class ConnectionState {
        private ByteBuffer input = ByteBuffer.allocate(Integer.BYTES);
        private boolean readingLength = true;
        private long lastReadNanos = System.nanoTime();
        private ByteBuffer response;
    }

    private static String decodeUtf8(byte[] plaintext) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        if (listener != null) {
            listener.close();
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(readTimeoutMillis + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
