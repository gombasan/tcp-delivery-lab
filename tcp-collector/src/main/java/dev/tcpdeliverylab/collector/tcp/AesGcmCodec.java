package dev.tcpdeliverylab.collector.tcp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmCodec {
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;
    public static final int MAX_PACKET_BYTES = 4096;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCodec(byte[] keyBytes) {

        if (keyBytes.length != 24) {
            throw new IllegalArgumentException("AES-192 키는 24바이트여야 합니다.");
        }

        key = new SecretKeySpec(keyBytes, "AES");
    }

    // Packet body: nonce followed by ciphertext and the authentication tag.
    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {

        if (plaintext.length == 0 || plaintext.length > MAX_PACKET_BYTES - NONCE_BYTES - TAG_BYTES) {
            throw new IllegalArgumentException("메시지 길이는 1바이트 이상 4068바이트 이하여야 합니다.");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext);

        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    public byte[] decrypt(byte[] packet) throws GeneralSecurityException {

        if (packet.length <= NONCE_BYTES + TAG_BYTES || packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("암호화된 패킷의 길이가 올바르지 않습니다.");
        }

        byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        return cipher.doFinal(packet, NONCE_BYTES, packet.length - NONCE_BYTES);
    }
}
