//! Host prototype: stdin = 32-byte random key, 32-byte profile digest, binary input.
use aes_gcm::aead::rand_core::RngCore;
use aes_gcm::aead::{Aead, KeyInit, OsRng, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use std::io::{self, Read, Write};
use zero_input_message_vectors::{domain, fields, seal_bound};

fn open(key: &[u8], binding: &[u8; 32], version: u8, envelope: &[u8]) -> Option<Vec<u8>> {
    let size = envelope.len();
    if !(162..=2210).contains(&size) || (size - 98) % 64 != 0 {
        return None;
    }
    let header = &envelope[..44];
    if header[16..20] != [2, 0, 0, 38] {
        return None;
    }
    let hkdf = Hkdf::<Sha256>::new(Some(&header[..16]), key);
    let mut mk = [0; 32];
    hkdf.expand(
        &fields(&[&domain("metadata-key"), header, binding]),
        &mut mk,
    )
    .ok()?;
    let aad = fields(&[&domain("metadata-aad"), header, binding]);
    let metadata = XChaCha20Poly1305::new((&mk).into())
        .decrypt(
            XNonce::from_slice(&header[20..44]),
            Payload {
                msg: &envelope[44..82],
                aad: &aad,
            },
        )
        .ok()?;
    if metadata.len() != 22
        || metadata[..4] != [1, 1, 0, version]
        || metadata[4..8] != ((size - 98) as u32).to_be_bytes()
        || metadata[8..10] != [1, 3]
    {
        return None;
    }
    let transcript = Sha256::digest(fields(&[&domain("descriptor"), header, &metadata, binding]));
    let identity = [0, 1, 1, 3];
    let mut sk = [0; 32];
    hkdf.expand(
        &fields(&[&domain("layer-key"), &transcript, &identity]),
        &mut sk,
    )
    .ok()?;
    let aad = fields(&[&domain("layer-aad"), &transcript, &identity]);
    let frame = Aes256Gcm::new((&sk).into())
        .decrypt(
            Nonce::from_slice(&metadata[10..]),
            Payload {
                msg: &envelope[82..],
                aad: &aad,
            },
        )
        .ok()?;
    let length = u32::from_be_bytes(frame[..4].try_into().ok()?) as usize;
    if !(1..=2048).contains(&length)
        || (36 + length).div_ceil(64) * 64 != frame.len()
        || frame[4..36] != binding[..]
    {
        return None;
    }
    Some(frame[36..36 + length].to_vec())
}

fn main() -> io::Result<()> {
    let failure = || io::Error::from(io::ErrorKind::InvalidData);
    let mut buffer = Vec::new();
    io::stdin().take(64 + 2211).read_to_end(&mut buffer)?;
    if !(65..=64 + 2210).contains(&buffer.len()) {
        return Err(failure());
    }
    let (key, rest) = buffer.split_at(32);
    let (profile, input) = rest.split_at(32);
    let command = std::env::args().nth(1).ok_or_else(failure)?;
    let (binding, version) = match command.as_str() {
        "seal" | "open" => {
            let cover = format!(
                "ZeroInput/host-verse/v1/{}",
                profile
                    .iter()
                    .map(|b| format!("{b:02x}"))
                    .collect::<String>()
            );
            (
                Sha256::digest(fields(&[&domain("exact-cover"), cover.as_bytes()])).into(),
                1,
            )
        }
        "seal-wording-trial" | "open-wording-trial" => (
            Sha256::digest(fields(&[&domain("natural-wording-trial"), profile])).into(),
            2,
        ),
        _ => return Err(failure()),
    };
    let result = if command.starts_with("seal") {
        if input.len() > 2048 {
            return Err(failure());
        }
        seal_bound(&[(1, 256)], input, key, &binding, version, |size| {
            let mut random = vec![0; size];
            OsRng.fill_bytes(&mut random);
            random
        })
    } else {
        open(key, &binding, version, input).ok_or_else(failure)?
    };
    io::stdout().write_all(&result)
}
