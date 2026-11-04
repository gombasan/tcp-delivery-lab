package dev.tcpdeliverylab.protocol;

public final class PacketFormat {
    public static final int LENGTH_BYTES = Integer.BYTES;
    public static final int KEY_BYTES = 24;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;
    public static final int MAX_BODY_BYTES = 4096;
    public static final int MAX_PLAINTEXT_BYTES = MAX_BODY_BYTES - NONCE_BYTES - TAG_BYTES;

    private PacketFormat() {
    }

    // 본문은 nonce, 암호문과 인증 태그를 포함하며 길이 필드 자체는 제외한다.
    public static void validateBodyLength(int length) {
        if (length <= NONCE_BYTES + TAG_BYTES || length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("패킷의 길이가 올바르지 않습니다.");
        }
    }
}
