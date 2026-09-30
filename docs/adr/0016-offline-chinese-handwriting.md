# ADR 0016: Offline Chinese handwriting

Status: accepted, 2026-09-30. The user approved offline Chinese recognition,
quality-first model selection, and public inference in sensitive editors without
personal-data access or learning.

## Context

Touch handwriting needs a separate recognition path from Rime keystrokes. A public
OCR model can process a rendered single character without editor context, while
online services and personal language models would cross the input privacy boundary.
The existing ONNX Runtime already supplies a worker-owned offline inference runtime.

## Decision

The UI owns at most 48 bounded strokes of 512 points each. The app coordinator
copies them into one replaceable, debounced worker request. `model-scoring` validates
and loads the pinned PP-OCRv5 mobile recognition graph and separately licensed
Tegaki Zinnia simplified/traditional stroke models. A pure Kotlin classifier
extracts bounded, normalized stroke features and evaluates immutable sparse
weights; ONNX supplies stroke-order-independent visual alternatives. The two
paths return an interleaved, deduplicated list of up to 16 Han candidates. The
user explicitly selects a candidate;
the service verifies the active editor binding and commits it directly. Handwriting
does not query editor text, private dictionaries, emoji history or clipboard, and
does not learn or persist stroke data, including in sensitive editors.

Both touch axes use one physical scale, so floating and one-handed canvases do
not stretch written shapes. Starting a stroke immediately clears old candidates
and invalidates previous inference, before the new stroke is released. Pointer
cancellation restores only completed strokes; resizing clears the current
character instead of mixing coordinate spaces. Long strokes are decimated within
the 512-point bound while preserving the continuing endpoint.

Session, view, panel and settings changes invalidate pending work. Stale callbacks
cannot reach a new editor. Application-owned stroke and raster buffers are wiped;
native allocator temporaries cannot be guaranteed byte-for-byte erased. Fixed
public OCR assets alone are copied to `noBackupFilesDir` after size/hash checks.
Stroke model tables are streamed from bundled assets on the existing worker and
verified before use. Their binary parser checks counts, lengths, Unicode labels,
finite weights, monotonic offsets and feature/class indices. No executable code,
runtime download, native library or external input is introduced by these tables.
Failure leaves the ordinary keyboard usable and shows an unavailable state.

## Consequences

The OCR graph alone was inadequate for the independent complex traditional
samples. On the 20 public Tegaki test strokes, the desktop original OCR path
returned 2 correct first candidates and 4 correct top-eight candidates; the new
hybrid returned 13 and 18 respectively. These are a small regression corpus, not
a representative accuracy promise. Simplified Tomoe templates overlap the stroke
model's training data and must never be reported as held-out accuracy. Android
pixel rendering, latency and memory are checked separately; see
[handwriting quality validation](../handwriting-quality-validation.md).

The packed stroke tables add 46,068,784 uncompressed bytes. They contain the same
learned coefficients as the pinned upstream models in a feature-major layout;
no retraining or quantization is performed. Inference reads only the nonzero
input features and uses less than 1 MiB of mutable stroke/scoring scratch,
in addition to public model memory and ONNX workspaces. Parsing and inference
remain on one worker, with bounded cancellation checks and scratch wiping.
Removing the old duplicate OCR asset copy offsets part of the package increase.
Exact compressed APK size and device memory depend on the build/device.

This scope recognizes one Han character per selection, not cursive phrases or
non-Chinese scripts. Stroke order and malformed/merged strokes can still affect
the classifier; visual OCR alternatives remain available. The larger PP-OCRv5
server graph was evaluated and rejected because it increased size and latency
without improving the development corpus. Native Zinnia was rejected in favor
of a bounded Kotlin implementation, avoiding another JNI/ABI maintenance surface.
The data is LGPL-2.1 and the adapted feature equations are BSD-3-Clause; upstream
archives, corresponding XML data, hashes and regeneration commands are recorded
in the validation document and third-party notices. An online recognizer or
personal adaptation remains outside the approved privacy boundary.
