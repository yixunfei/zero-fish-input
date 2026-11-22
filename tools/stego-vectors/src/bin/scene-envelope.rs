//! Host-only authenticated envelope for the public scene calibration.
//!
//! Input is a 32-byte public fixture key, a 32-byte resource digest, then the
//! packet. This research binding is deliberately distinct from Android modes.
use aes_gcm::aead::rand_core::RngCore;
use aes_gcm::aead::{Aead, KeyInit, OsRng, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use std::io::{self, Read, Write};
use zero_input_message_vectors::{domain, fields};

const VERSION: u8 = 127;
const METADATA_LEN: usize = 128;

fn random<const N: usize>() -> [u8; N] {
    let mut result = [0; N];
    OsRng.fill_bytes(&mut result);
    result
}

fn binding(profile: &[u8]) -> [u8; 32] {
    Sha256::digest(fields(&[&domain("host-scene-research-v1"), profile])).into()
}

fn meta_key(key: &[u8], header: &[u8], binding: &[u8; 32]) -> Option<[u8; 32]> {
    let hkdf = Hkdf::<Sha256>::new(Some(&header[..16]), key);
    let mut output = [0; 32];
    hkdf.expand(
        &fields(&[&domain("metadata-key"), header, binding]),
        &mut output,
    )
    .ok()?;
    Some(output)
}

fn seal(key: &[u8], binding: &[u8; 32], packet: &[u8]) -> Option<Vec<u8>> {
    if !(1..=2048).contains(&packet.len()) {
        return None;
    }
    let aes_nonce = random::<12>();
    let aes_key = random::<32>();
    let x_nonce = random::<24>();
    let x_key = random::<32>();
    let frame_len = (36 + packet.len()).div_ceil(64) * 64;
    let mut metadata = vec![1, 2, 0, VERSION];
    metadata.extend_from_slice(&(frame_len as u32).to_be_bytes());
    metadata.extend_from_slice(&[1, 3]);
    metadata.extend_from_slice(&aes_nonce);
    metadata.extend_from_slice(&aes_key);
    metadata.extend_from_slice(&[2, 3]);
    metadata.extend_from_slice(&x_nonce);
    metadata.extend_from_slice(&x_key);
    let mut header = random::<16>().to_vec();
    header.extend_from_slice(&[2, 0, 0, METADATA_LEN as u8]);
    header.extend_from_slice(&random::<24>());
    let mk = meta_key(key, &header, binding)?;
    let aad = fields(&[&domain("metadata-aad"), &header, binding]);
    let encrypted = XChaCha20Poly1305::new((&mk).into())
        .encrypt(
            XNonce::from_slice(&header[20..44]),
            Payload {
                msg: &metadata,
                aad: &aad,
            },
        )
        .ok()?;
    let transcript = Sha256::digest(fields(&[
        &domain("descriptor"),
        &header,
        &metadata,
        binding,
    ]));
    let mut frame = vec![0; frame_len];
    OsRng.fill_bytes(&mut frame);
    frame[..4].copy_from_slice(&(packet.len() as u32).to_be_bytes());
    frame[4..36].copy_from_slice(binding);
    frame[36..36 + packet.len()].copy_from_slice(packet);
    let aad = fields(&[&domain("layer-aad"), &transcript, &[0, 2, 1, 3]]);
    let first = Aes256Gcm::new((&aes_key).into())
        .encrypt(
            Nonce::from_slice(&aes_nonce),
            Payload {
                msg: &frame,
                aad: &aad,
            },
        )
        .ok()?;
    let aad = fields(&[&domain("layer-aad"), &transcript, &[1, 2, 2, 3]]);
    let second = XChaCha20Poly1305::new((&x_key).into())
        .encrypt(
            XNonce::from_slice(&x_nonce),
            Payload {
                msg: &first,
                aad: &aad,
            },
        )
        .ok()?;
    Some([header, encrypted, second].concat())
}

fn open(key: &[u8], binding: &[u8; 32], envelope: &[u8]) -> Option<Vec<u8>> {
    if envelope.len() < 268 || envelope.len() > 2380 || (envelope.len() - 204) % 64 != 0 {
        return None;
    }
    let header = &envelope[..44];
    if header[16..20] != [2, 0, 0, METADATA_LEN as u8] {
        return None;
    }
    let mk = meta_key(key, header, binding)?;
    let aad = fields(&[&domain("metadata-aad"), header, binding]);
    let meta_end = 44 + METADATA_LEN;
    let metadata = XChaCha20Poly1305::new((&mk).into())
        .decrypt(
            XNonce::from_slice(&header[20..44]),
            Payload {
                msg: &envelope[44..meta_end],
                aad: &aad,
            },
        )
        .ok()?;
    if metadata.len() != 112
        || metadata[..4] != [1, 2, 0, VERSION]
        || metadata[8..10] != [1, 3]
        || metadata[54..56] != [2, 3]
    {
        return None;
    }
    let frame_len = u32::from_be_bytes(metadata[4..8].try_into().ok()?) as usize;
    if frame_len < 64
        || frame_len > 2112
        || frame_len % 64 != 0
        || envelope.len() != meta_end + frame_len + 32
    {
        return None;
    }
    let transcript = Sha256::digest(fields(&[&domain("descriptor"), header, &metadata, binding]));
    let aad = fields(&[&domain("layer-aad"), &transcript, &[1, 2, 2, 3]]);
    let first = XChaCha20Poly1305::new_from_slice(&metadata[80..112])
        .ok()?
        .decrypt(
            XNonce::from_slice(&metadata[56..80]),
            Payload {
                msg: &envelope[meta_end..],
                aad: &aad,
            },
        )
        .ok()?;
    let aad = fields(&[&domain("layer-aad"), &transcript, &[0, 2, 1, 3]]);
    let frame = Aes256Gcm::new_from_slice(&metadata[22..54])
        .ok()?
        .decrypt(
            Nonce::from_slice(&metadata[10..22]),
            Payload {
                msg: &first,
                aad: &aad,
            },
        )
        .ok()?;
    let len = u32::from_be_bytes(frame[..4].try_into().ok()?) as usize;
    if !(1..=2048).contains(&len)
        || (36 + len).div_ceil(64) * 64 != frame_len
        || frame[4..36] != binding[..]
    {
        return None;
    }
    Some(frame[36..36 + len].to_vec())
}

fn main() -> io::Result<()> {
    let failure = || io::Error::from(io::ErrorKind::InvalidData);
    let mut input = Vec::new();
    io::stdin().take(64 + 2381).read_to_end(&mut input)?;
    if !(65..=64 + 2380).contains(&input.len()) {
        return Err(failure());
    }
    let digest = binding(&input[32..64]);
    let result = match std::env::args().nth(1).as_deref() {
        Some("seal") => seal(&input[..32], &digest, &input[64..]),
        Some("open") => open(&input[..32], &digest, &input[64..]),
        _ => None,
    }
    .ok_or_else(failure)?;
    io::stdout().write_all(&result)
}
