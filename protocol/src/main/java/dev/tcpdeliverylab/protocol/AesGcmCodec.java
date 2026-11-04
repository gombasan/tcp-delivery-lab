package dev.tcpdeliverylab.protocol;

import static dev.tcpdeliverylab.protocol.PacketFormat.*;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmCodec {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCodec(byte[] keyBytes) {

        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-192 키는 24바이트여야 합니다.");
        }

        key = new SecretKeySpec(keyBytes, "AES");
    }

    // nonce 뒤에 암호문과 인증 태그를 붙여 본문을 구성한다.
    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {

        if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
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

        validateBodyLength(packet.length);

        byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        return cipher.doFinal(packet, NONCE_BYTES, packet.length - NONCE_BYTES);
    }
}
