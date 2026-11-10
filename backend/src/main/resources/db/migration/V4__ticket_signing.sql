-- M1.6 (D-018): what a ticket needs beyond V1 to be signed, re-displayed and verified offline.
--
-- key_id: which signing key signed this ticket. GET /me/tickets rebuilds the QR payload from this
--   row (the payload carries the key id), and after a key rotation old tickets still name their key.
--   8 lowercase hex characters: the first 4 bytes of SHA-256 over the raw 32-byte public key.
-- section_code, row_label, seat_number: a snapshot of the seat's label at issue time, passed in by
--   booking, so ticketing can show "Section A, Row 3, Seat 5" without reading inventory's tables
--   (D-007). Not part of the signed payload: the scanner trusts the seat id, the label is display.
--
-- NOT NULL without a default is safe: no code path wrote tickets before this migration.
-- uq_tickets_live_seat (one live ticket per seat) is unchanged; issuing relies on it (ON CONFLICT).

ALTER TABLE tickets
    ADD COLUMN key_id       text    NOT NULL,
    ADD COLUMN section_code text    NOT NULL,
    ADD COLUMN row_label    text    NOT NULL,
    ADD COLUMN seat_number  integer NOT NULL;

ALTER TABLE tickets ADD CONSTRAINT ck_tickets_key_id CHECK (key_id ~ '^[0-9a-f]{8}$');
ALTER TABLE tickets ADD CONSTRAINT ck_tickets_seat_number_positive CHECK (seat_number > 0);
-- An Ed25519 signature is always exactly 64 bytes.
ALTER TABLE tickets ADD CONSTRAINT ck_tickets_signature_length CHECK (octet_length(signature) = 64);
