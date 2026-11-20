# ADR 0040 — Local biometric and passphrase messages

Accepted on 2026-11-20 for personal notes on one device.

Message credential ID 6 requires a passphrase and one authorized HMAC-SHA256
operation using a dedicated non-exportable Android Keystore key. Each seal or
open initializes a fresh MAC on a worker. The platform authenticates that
CryptoObject using strong biometrics (fingerprint on Android 8.x). The callback
checks operation identity, workspace validity and revision. No PIN fallback or
reusable time window is configured. Key loss on decryption fails closed.
New enrollment invalidates the key. Creation may replace an already invalid key.

The authenticated MAC input uses the existing length-prefixed field encoding
over the ASCII domain ZeroInput/message/v1/local-biometric-password, the
envelope's 16-byte salt and the exact UTF-8 passphrase. Its output feeds the
existing Argon2id profile and envelope key schedule. The credential consumes its
MAC once. The portable modes retain their IDs and byte format.

An authentication-success flag alone cannot protect copied messages. A
biometric-only option was considered and the user selected the combination.
The user sees the device binding and enrollment/loss warning before selection
and local decryption. Multiple strong biometrics enrolled on the same device
share access to the device factor. The passphrase is still required.

The IME moves this mode into the existing private StegoSourceActivity before
note entry. The platform biometric prompt hides the IME on the tested emulator,
which revokes the keyboard workspace. The handoff uses a one-use opaque token;
only an explicitly selected encrypted carrier may accompany a decrypt request.
The Activity uses its own keyboard and the existing authenticated private-save
flow. No connection to the previous editor is retained.

The existing [carrier assessment](../text-steganography-design.md#33-controlled-visible-wording-track)
applies independently. The short-word ranker considers up to eight two-character
candidates for a shared pinyin reading, with optional model availability, a
winning margin and a deadline. Its output cannot identify reversible alternatives
or reconstruct them on another device. A future experiment could use its offline
scorer to screen curated phrase alternatives using public corpus context.
Versioned unambiguous alternatives, independent round-trip decoding, capacity
measurement and human semantic review would still be required.

For example, “故事” and “股市” can share a pinyin reading yet encode different
facts. A phrase-pair experiment should begin with public, authored alternatives
in fixed sentence contexts, freeze names, quantities and quotations, and test
whether a separate decoder recovers every byte after a resource reload. Even a
100-byte secret with one AES layer currently produces 290 envelope bytes:
1,160 ideal four-way choices before any framing for a wording codec. Ordinary
poetry selection changes the public cover only.
