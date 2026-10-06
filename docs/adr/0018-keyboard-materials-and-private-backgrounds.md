# ADR 0018: Independent keyboard materials and private backgrounds

Status: Accepted (2026-10-04)

## Context

The user approved six keyboard styles, independent borders, non-green defaults,
local image backgrounds and opacity. Existing presets combine palette and key
geometry and contain no image storage. Arbitrary images create an import boundary
and cannot be decoded on the input thread or exposed through existing user stores.

## Decision

Separate immutable palette, material, geometry and background options in ime-ui.
Use dedicated bounded drawables for Classic, Flat, Raised, Soft touch, Metal and
Frosted glass. Keep key hit boxes unchanged. Blend background opacity onto the
opaque keyboard surface, protect header/label contrast, and never capture another
application for glass effects. Default to cool gray/blue and Flat; retain stored
palette/height identifiers and give new fields defaults without a migration layer.

The app owns user-granted image import and a serial bounded worker. Accept only
PNG/JPEG/WebP with strict byte/pixel/edge bounds, normalize orientation, resample,
and strip metadata before atomic encryption under a dedicated Keystore alias.
Background revisions live in noBackupFilesDir; preferences hold only a UUID.
Publish only successfully written current imports. Removal invalidates imports
before serial file/key deletion. View bindings own cancellable image loading and
release their display bitmap when hidden, replaced or destroyed. No global decoded
image cache is permitted. UI callbacks have a deadline and release their owners on
cancellation; native decode/provider cancellation remains best-effort.

AndroidX ExifInterface 1.4.1 provides maintained orientation parsing across API 26+;
the legacy platform parser has known security issues flagged by Lint. The pinned
75,523-byte AAR is Apache-2.0, adds no permissions/components/networking, and is
recorded in THIRD_PARTY.md and NOTICE using the existing Apache license copy.

## Consequences and alternatives

Bitmap/EXIF/blur/crypto work stays off input threads. Encoded buffers are wiped;
renderer/native internal copies cannot guarantee erasure. Decoration is deliberately
visible when the keyboard is shown. The settings window is secure, and preview has
no editor or personal-data ports. Failed imports keep the selected image; failed
loads use a palette fallback. Failed deletion can be retried.

Rejected alternatives include retaining document URI grants, storing unencrypted
source photos, adding storage permissions, runtime theme downloads, a permanent
decoded bitmap cache, and blurring the host application's screen. Tests use public
synthetic fixtures for malformed imports, cancellation/deletion races, ciphertext
corruption, key loss, geometry, themes, widths and opacity.
