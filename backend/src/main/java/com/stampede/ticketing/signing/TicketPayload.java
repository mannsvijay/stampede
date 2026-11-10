package com.stampede.ticketing.signing;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a ticket's signature covers (PRD 6.9, D-018), in the compact binary format of
 * docs/ticket-format.md. Format 1 is exactly {@value #LENGTH} bytes, all integers big-endian:
 *
 * <pre>
 * offset  size  field
 *      0     1  format        0x01
 *      1     4  keyId         first 4 bytes of SHA-256(raw public key)
 *      5    16  ticketId      UUID, most significant byte first
 *     21    16  eventId       UUID
 *     37    16  seatId        UUID
 *     53    16  holderUserId  UUID (the holder's user id only, no personal data)
 *     69     8  issuedAt      seconds since 1970-01-01T00:00:00Z, signed 64-bit
 *     77     4  version       ticket version, starts at 1, +1 on every transfer
 * </pre>
 *
 * Fixed offsets, no field names and no text: the smallest encoding that still has a format byte to
 * evolve it, which keeps the QR code small enough to scan easily from a phone screen.
 *
 * @param keyId    8 lowercase hex characters (the 4 key id bytes)
 * @param issuedAt whole seconds only: the payload has no room for fractions, and the database row must
 *                 hold exactly what was signed
 */
public record TicketPayload(String keyId, UUID ticketId, UUID eventId, UUID seatId, UUID holderUserId,
                            Instant issuedAt, int version) {

    public static final byte FORMAT = 1;
    public static final int LENGTH = 81;

    private static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{8}");

    public TicketPayload {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(holderUserId, "holderUserId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("keyId must be 8 lowercase hex characters: " + keyId);
        }
        if (issuedAt.getNano() != 0) {
            throw new IllegalArgumentException("issuedAt must be whole seconds: " + issuedAt);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1: " + version);
        }
    }

    /** The exact bytes that are signed. */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(LENGTH);  // big-endian by default
        buffer.put(FORMAT);
        buffer.put(HexFormat.of().parseHex(keyId));
        putUuid(buffer, ticketId);
        putUuid(buffer, eventId);
        putUuid(buffer, seatId);
        putUuid(buffer, holderUserId);
        buffer.putLong(issuedAt.getEpochSecond());
        buffer.putInt(version);
        return buffer.array();
    }

    /**
     * Reads a payload. Only call this on bytes whose signature has been verified: decoding proves
     * nothing about authenticity.
     *
     * @throws IllegalArgumentException if the bytes aren't a format-1 payload
     */
    public static TicketPayload decode(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("A ticket payload is " + LENGTH + " bytes, got " + bytes.length);
        }
        if (bytes[0] != FORMAT) {
            throw new IllegalArgumentException("Unknown ticket payload format " + bytes[0]);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, LENGTH - 1);
        byte[] keyId = new byte[4];
        buffer.get(keyId);
        return new TicketPayload(HexFormat.of().formatHex(keyId), getUuid(buffer), getUuid(buffer),
                getUuid(buffer), getUuid(buffer), Instant.ofEpochSecond(buffer.getLong()), buffer.getInt());
    }

    /** The key id (8 hex characters) named in raw payload bytes, before anything is verified. */
    static String keyIdOf(byte[] bytes) {
        return HexFormat.of().formatHex(bytes, 1, 5);
    }

    private static void putUuid(ByteBuffer buffer, UUID uuid) {
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
