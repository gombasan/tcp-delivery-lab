package dev.tcpdeliverylab.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TcpFrameCodec {
    private TcpFrameCodec() {
    }

    // 이미 암호화된 본문 앞에 길이를 big-endian으로 기록한다.
    public static byte[] encode(byte[] body) {
        PacketFormat.validateBodyLength(body.length);
        return ByteBuffer.allocate(PacketFormat.LENGTH_BYTES + body.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(body.length)
                .put(body)
                .array();
    }

    // 완전히 수신된 프레임 한 건에서 본문을 추출한다. 소켓 누적 읽기는 담당하지 않는다.
    public static byte[] decode(byte[] frame) {
        if (frame.length < PacketFormat.LENGTH_BYTES) {
            throw new IllegalArgumentException("길이 필드를 읽을 수 없습니다. 최소 4바이트가 필요합니다.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();
        PacketFormat.validateBodyLength(length);
        if (buffer.remaining() != length) {
            throw new IllegalArgumentException("본문의 실제 길이가 길이 필드와 일치하지 않습니다.");
        }
        byte[] body = new byte[length];
        buffer.get(body);
        return body;
    }
}
