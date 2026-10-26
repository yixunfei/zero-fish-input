# ADR 0027: Reviewed prebuilt artifacts for the exact default Rime generation

Status: accepted (user confirmation, 2026-10-25)

## Context

A fresh default public dictionary took 71.046 seconds on the API 36 x86_64
userdebug emulator, exceeding the existing 60-second readiness budget. The
work was real librime table construction inside maintenance, not evidence of a
main-thread deadlock. Increasing that budget would hide the wait; publishing
`READY` before conversion verification would trade correctness for a metric.

The compiled table, prism and reverse files must match the generation-specific
schema and primary dictionary header. In particular, the Android installer
writes the selected generation into `zeroinput_public.dict.yaml`, whose bytes
participate in librime's checksum. A host-built file with the original header
is therefore not an acceptable substitute.

## Decision

`tools/rime-prebuilt/generate.py` uses the pinned librime 1.13.1 deployer and
locked public inputs to produce the default table, prism and reverse files. It
reproduces Android's normalized schema JSON and generation-specific dictionary
header, then compresses the three outputs into reviewed XZ assets. The manifest
binds source identity, normalized source bytes, grammar absence, runtime
version/commit, binary formats, lengths and hashes. Generation is not a
bit-reproducibility claim for arbitrary host toolchains.

`RimeAssetInstaller` remains the sole deployment owner. It installs only the
three allowlisted files into unpublished shared `build` storage, with bounded
XZ decoding, original/archive SHA-256 checks and atomic generation activation.
These artifacts apply only to the exact managed, no-grammar, unmodified default
generation. Source dictionaries remain the content authority and remain in the
APK. Other generations and recoverable incompatibility retain the existing
worker-owned native compiler; no parallel initializer is introduced.

Native maintenance joining, session creation and actual public conversion
remain prerequisites for `READY`. Existing verified custom generations do not
undergo default-prebuilt hash matching. Failure still selects the memory-only
fallback, without exposing personal text to the artifact pipeline.

The measured isolated cold-start median is 12.174 seconds on the same emulator
class, versus the 71.046-second measured baseline. The final clean-APK regression
median is 7.996 seconds. Clean package comparison measures a 25,546,438-byte
(24.36 MiB) increase, accepted for this first-install improvement. This is not
a physical ARM performance or release-readiness claim. Generic deployment does
not copy packaged archives into base/shared caches. Older app-owned archive
copies are reclaimed after native conversion, preserving user-generation data;
the APK delta remains unchanged while duplicate private storage is avoided.

## Alternatives and consequences

- Increasing timeouts or removing maintenance joining was rejected: necessary
  work and readiness would be obscured rather than improved.
- Uncompressed compiled tables would avoid XZ CPU work but add roughly 74.5 MB
  of assets rather than 25.5 MB; compressed reviewed artifacts are preferred.
- Rebuilding the default dictionary on every fresh device avoids the artifact
  pipeline but retains the measured unacceptable first-install wait.
- Treating prebuilt files as another dictionary source was rejected. Dictionary
  additions, replacements, disabled tables and optional grammar use their own
  generation and native compilation.

Builds must verify the reviewed manifest and inputs. Changing source data,
schema normalization, the installer generation algorithm or pinned native
formats requires regeneration and device verification. Corruption and changed
sources must exercise recovery, not just successful reuse. Warm reuse is not
claimed to be faster without comparable measurements.

See [architecture](../architecture.md#compressed-deployment-and-reduced-inference-adr-0022)
and the measured [UX performance report](../rime-ux-performance-2026-10-25.md).
