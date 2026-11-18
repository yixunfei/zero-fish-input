# Independent message vectors

This host-only public-fixture tool cross-checks the production Bouncy Castle/Tink
implementation with pinned RustCrypto GCM, XChaCha20-Poly1305, Argon2id and HKDF.
No native component or Rust dependency enters an Android APK. No code was copied
from Cipher Vault Toolbox. Dependencies retain their upstream MIT/Apache-2.0
licenses and are pinned with checksums in Cargo.lock.

From the repository root:

```powershell
cargo run --manifest-path tools/stego-vectors/Cargo.toml --locked --quiet
./gradlew.bat :security:testDebugUnitTest
```

The checked public output is
`security/src/test/resources/message/rustcrypto-v1.txt`. The JVM tests compare all
12 cipher/key-width modes, all three KDF profiles, five credential combinations,
the bounded file digest, and complete single/six-layer envelopes byte for byte.
They also run RFC 5869, RFC 9106, RFC 8998, NIST AES-GCM and the IETF XChaCha draft
published vectors. Cascade regression tests cover all 720 six-algorithm orders
and valid outer layers around each damaged inner tag. This is
implementation agreement and regression evidence, not an independent security
audit or a claim of certification for uncommon GCM profiles.
