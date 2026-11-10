# Ticket QR format, version 1

How a Stampede ticket is encoded in its QR code, and how a scanner verifies it **offline**, with
nothing but a public key downloaded in advance. Decision: D-018. Code: `ticketing.signing`
(`TicketPayload`, `TicketSigner`, `TicketVerifier`).

## 1. The QR string

```
base64url(payload) "." base64url(signature)
```

- `base64url` is RFC 4648 section 5 (`-` and `_` instead of `+` and `/`), **without** `=` padding.
- `payload` is exactly 81 bytes (section 2), so its part is always 108 characters.
- `signature` is the 64-byte Ed25519 signature (RFC 8032) over the 81 payload bytes, 86 characters.
- The whole string is 195 ASCII characters. Encode it in the QR code in byte mode.

## 2. The payload (81 bytes)

All integers are big-endian. A UUID is its 16 bytes in the usual order: the first byte is the
first two hex digits of its text form (`11111111-1111-...` starts `0x11 0x11`).

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 1 | format | `0x01`. Reject any other value. |
| 1 | 4 | keyId | Which key signed it: the first 4 bytes of SHA-256 over the raw 32-byte public key. Its hex form is the `kid` of `GET /tickets/keys`. |
| 5 | 16 | ticketId | The ticket's UUID. |
| 21 | 16 | eventId | The event's UUID. |
| 37 | 16 | seatId | The seat's UUID. |
| 53 | 16 | holderUserId | The holder's user UUID. No name or email: the payload is signed, not encrypted, so anyone can read it. |
| 69 | 8 | issuedAt | Seconds since 1970-01-01T00:00:00Z, signed 64-bit. |
| 77 | 4 | version | Ticket version, signed 32-bit, starts at 1. Transfer (PRD 6.9, not built yet) will increment it, so the old QR no longer matches the server's records. |

The seat's label ("Section A, Row 3, Seat 5") is **not** in the payload. The wallet shows it from
the server's records; a scanner that wants to display it looks the seat id up in the event's seat
map, downloaded with the keys.

## 3. Getting the public keys

`GET /tickets/keys` (no login) returns a JSON Web Key Set (RFC 7517) with Ed25519 keys in the
OKP form of RFC 8037:

```json
{ "keys": [ { "kty": "OKP", "crv": "Ed25519", "kid": "16ea0a37", "x": "<base64url of 32 bytes>",
              "alg": "EdDSA", "use": "sig" } ] }
```

`x` is the raw 32-byte public key. Download the set before going offline and keep every key in it.
Today there is one key; after a rotation the set lists more, and old tickets name their key by id.

## 4. Verifying, step by step

1. Split the string at the single `.`. Exactly two parts, or reject.
2. base64url-decode both. The payload must be 81 bytes, the signature 64 bytes, or reject.
3. Byte 0 must be `0x01`, or reject.
4. Read bytes 1 to 4 as the key id (8 lowercase hex characters). No key with that `kid`: reject.
5. Verify the Ed25519 signature over all 81 payload bytes with that key. Invalid: reject.
6. Only now read the other fields. Nothing before step 5 proves anything.

Signature verification proves the server issued exactly this ticket. It does **not** prove the ticket
is still valid: whether it was already scanned (check-in) or replaced by a transfer (a higher version)
needs the server's records, synced when the scanner is back online (`POST /checkins`, later).

## 5. Test vector

Built with the **test-only** key from `backend/src/test/resources/config/application.properties`
(seed = the 32 ASCII bytes `stampede-test-only-ticket-key-00`). Never use it outside tests.
`TicketSignerTest.knownAnswerVectorOfTheFormatDocument` checks this exact vector.

| Field | Value |
|---|---|
| public key `x` | `s5RRrUy6gk3iu6wVCqS2QU4UGUo3KVPSUhtH_XSiU6s` |
| keyId | `16ea0a37` |
| ticketId | `11111111-1111-4111-8111-111111111111` |
| eventId | `22222222-2222-4222-8222-222222222222` |
| seatId | `33333333-3333-4333-8333-333333333333` |
| holderUserId | `44444444-4444-4444-8444-444444444444` |
| issuedAt | `2026-11-10T12:00:00Z` (`0x000000006af30740`) |
| version | `1` |

Payload bytes (hex, split by field):

```
01 16ea0a37 11111111111141118111111111111111 22222222222242228222222222222222
33333333333343338333333333333333 44444444444444448444444444444444 000000006af30740 00000001
```

QR string (195 characters, one line):

```
ARbqCjcRERERERFBEYERERERERERIiIiIiIiQiKCIiIiIiIiIjMzMzMzM0MzgzMzMzMzMzNERERERERERIREREREREREAAAAAGrzB0AAAAAB.5_GwMTIhLuHjOFTr-jOb1j7rXMTRC7Q9vvQm19L6dn_O5UJ4yvrtJ5tus0JFTitX5_BC0Ty6sfy5YeMWACjuCg
```

Ed25519 signatures are deterministic, so any correct implementation produces exactly this string.

## 6. Changing the format

Add a new format byte (`0x02`) instead of changing version 1. A scanner rejects formats it doesn't
know, so a new format needs scanners updated before tickets use it.
