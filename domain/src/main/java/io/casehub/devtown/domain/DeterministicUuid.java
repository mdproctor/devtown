package io.casehub.devtown.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class DeterministicUuid {

    private static final UUID DNS_NAMESPACE =
            UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    public static final UUID MERGE_DECISION_NS =
            v5(DNS_NAMESPACE, "casehub.io/devtown/merge-decision");

    public static UUID v5(UUID namespace, String name) {
        byte[] nsBytes = uuidToBytes(namespace);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 not available", e);
        }

        sha1.update(nsBytes);
        sha1.update(nameBytes);
        byte[] hash = sha1.digest();

        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // IETF variant

        ByteBuffer buf = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    private DeterministicUuid() {}
}
