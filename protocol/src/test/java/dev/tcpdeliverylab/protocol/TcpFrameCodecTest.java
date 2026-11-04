package dev.tcpdeliverylab.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TcpFrameCodecTest {
    @Test
    @DisplayName("본문 길이를 4바이트 big-endian으로 기록하고 본문은 그대로 유지합니다.")
    void writesBigEndianLengthBeforeBody() {
        byte[] body = new byte[257];
        Arrays.fill(body, (byte) 7);
        byte[] frame = TcpFrameCodec.encode(body);
        assertArrayEquals(new byte[] {0, 0, 1, 1}, Arrays.copyOf(frame, 4));
        assertEquals(261, frame.length);
        assertArrayEquals(body, Arrays.copyOfRange(frame, 4, frame.length));
    }

    @Test
    @DisplayName("최소 길이와 최대 길이의 본문을 프레임으로 변환하고 복원합니다.")
    void roundTripsBoundaryLengths() {
        for (int length : new int[] {29, 4096}) {
            byte[] body = new byte[length];
            new SecureRandom().nextBytes(body);
            assertArrayEquals(body, TcpFrameCodec.decode(TcpFrameCodec.encode(body)));
        }
    }

    @Test
    @DisplayName("암호화부터 프레임 조립과 해석을 거쳐 원문으로 복호화합니다.")
    void roundTripsEncryptedMessage() throws Exception {
        byte[] key = new byte[24];
        new SecureRandom().nextBytes(key);
        AesGcmCodec codec = new AesGcmCodec(key);
        byte[] message = "GW001,23.5,온도".getBytes(StandardCharsets.UTF_8);
        byte[] frame = TcpFrameCodec.encode(codec.encrypt(message));
        assertArrayEquals(message, codec.decrypt(TcpFrameCodec.decode(frame)));
    }

    @Test
    @DisplayName("빈 본문과 허용 범위를 벗어난 본문은 프레임으로 만들 수 없습니다.")
    void rejectsInvalidBodyLengths() {
        for (int length : new int[] {0, 28, 4097}) {
            assertThrows(IllegalArgumentException.class, () -> TcpFrameCodec.encode(new byte[length]));
        }
    }

    @Test
    @DisplayName("길이 필드가 부족하거나 음수 또는 허용 길이 초과이면 거부합니다.")
    void rejectsInvalidLengthPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> TcpFrameCodec.decode(new byte[3]));
        for (int length : new int[] {-1, 0, 28, 4097, Integer.MAX_VALUE}) {
            byte[] frame = ByteBuffer.allocate(4).putInt(length).array();
            assertThrows(IllegalArgumentException.class, () -> TcpFrameCodec.decode(frame));
        }
    }

    @Test
    @DisplayName("길이 필드보다 본문이 짧거나 길면 프레임을 거부합니다.")
    void rejectsTruncatedOrExtraBody() {
        byte[] frame = TcpFrameCodec.encode(new byte[29]);
        assertThrows(IllegalArgumentException.class,
                () -> TcpFrameCodec.decode(Arrays.copyOf(frame, frame.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> TcpFrameCodec.decode(Arrays.copyOf(frame, frame.length + 1)));
    }
}
