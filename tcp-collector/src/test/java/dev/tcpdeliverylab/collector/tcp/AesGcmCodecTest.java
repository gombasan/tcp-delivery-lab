package dev.tcpdeliverylab.collector.tcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AesGcmCodecTest {
    private final byte[] key = new byte[24];

    AesGcmCodecTest() {
        new SecureRandom().nextBytes(key);
    }

    @Test
    @DisplayName("별도 패딩 없이 암호화한 데이터를 원문으로 복호화합니다.")
    void roundTripWithoutBlockPadding() throws Exception {
        AesGcmCodec codec = new AesGcmCodec(key);
        byte[] message = "GW001,23.5,온도".getBytes(StandardCharsets.UTF_8);
        byte[] packet = codec.encrypt(message);
        assertEquals(message.length + 12 + 16, packet.length);
        assertArrayEquals(message, codec.decrypt(packet));
    }

    @Test
    @DisplayName("같은 데이터를 암호화해도 서로 다른 nonce를 사용합니다.")
    void repeatedPlaintextUsesDifferentNonces() throws Exception {
        AesGcmCodec codec = new AesGcmCodec(key);
        byte[] first = codec.encrypt(new byte[] {42});
        byte[] second = codec.encrypt(new byte[] {42});
        assertFalse(Arrays.equals(Arrays.copyOf(first, 12), Arrays.copyOf(second, 12)));
    }

    @Test
    @DisplayName("nonce, 암호문 또는 인증 태그가 변조되면 복호화에 실패합니다.")
    void rejectsTamperedCiphertextAndTag() throws Exception {
        AesGcmCodec codec = new AesGcmCodec(key);
        byte[] packet = codec.encrypt("GW001,23.5".getBytes(StandardCharsets.UTF_8));
        for (int index : new int[] {0, 12, packet.length - 1}) {
            byte[] corrupted = packet.clone();
            corrupted[index] ^= 1;
            assertThrows(AEADBadTagException.class, () -> codec.decrypt(corrupted));
        }
    }

    @Test
    @DisplayName("다른 키로 복호화하면 인증에 실패합니다.")
    void rejectsWrongKey() throws Exception {
        byte[] wrongKey = key.clone();
        wrongKey[0] ^= 1;
        byte[] packet = new AesGcmCodec(key).encrypt(new byte[] {1});
        assertThrows(AEADBadTagException.class, () -> new AesGcmCodec(wrongKey).decrypt(packet));
    }

    @Test
    @DisplayName("키와 패킷의 길이를 검증하고 최대 길이의 데이터를 처리합니다.")
    void enforcesKeyAndPacketBounds() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new AesGcmCodec(new byte[16]));
        AesGcmCodec codec = new AesGcmCodec(key);
        assertThrows(IllegalArgumentException.class, () -> codec.encrypt(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> codec.encrypt(new byte[4069]));
        assertThrows(IllegalArgumentException.class, () -> codec.decrypt(new byte[28]));
        assertThrows(IllegalArgumentException.class, () -> codec.decrypt(new byte[4097]));
        assertArrayEquals(new byte[4068], codec.decrypt(codec.encrypt(new byte[4068])));
    }
}
