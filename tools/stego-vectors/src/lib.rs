//! Independent host message reference, shared by public vector tools.
use aes_gcm::aead::consts::U12;
use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    AesGcm, Nonce,
};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
pub fn fields(values: &[&[u8]]) -> Vec<u8> {
    values
        .iter()
        .flat_map(|v| {
            let mut f = (v.len() as u32).to_be_bytes().to_vec();
            f.extend_from_slice(v);
            f
        })
        .collect()
}
pub fn domain(value: &str) -> Vec<u8> {
    format!("ZeroInput/message/v1/{value}").into_bytes()
}
pub fn aead(id: u8, bits: usize, key: &[u8], nonce: &[u8], aad: &[u8], input: &[u8]) -> Vec<u8> {
    let payload = Payload { msg: input, aad };
    macro_rules! gcm {
        ($cipher:ty) => {
            AesGcm::<$cipher, U12>::new_from_slice(key)
                .unwrap()
                .encrypt(Nonce::from_slice(nonce), payload)
                .unwrap()
        };
    }
    match (id, bits) {
        (1, 128) => gcm!(aes::Aes128),
        (1, 192) => gcm!(aes::Aes192),
        (1, 256) => gcm!(aes::Aes256),
        (2, 256) => XChaCha20Poly1305::new_from_slice(key)
            .unwrap()
            .encrypt(XNonce::from_slice(nonce), payload)
            .unwrap(),
        (3, 256) => gcm!(kuznyechik::Kuznyechik),
        (4, 128 | 192 | 256) => {
            AesGcm::<twofish::Twofish, U12>::from(twofish::Twofish::new_from_slice(key).unwrap())
                .encrypt(Nonce::from_slice(nonce), payload)
                .unwrap()
        }
        (5, 128) => gcm!(camellia::Camellia128),
        (5, 192) => gcm!(camellia::Camellia192),
        (5, 256) => gcm!(camellia::Camellia256),
        (6, 128) => gcm!(sm4::Sm4),
        _ => panic!("unsupported fixture"),
    }
}

pub fn seal(
    stages: &[(u8, usize)],
    secret: &[u8],
    key: &[u8],
    cover: &[u8],
    fresh: impl FnMut(usize) -> Vec<u8>,
) -> Vec<u8> {
    let binding = Sha256::digest(fields(&[&domain("exact-cover"), cover])).into();
    seal_bound(stages, secret, key, &binding, 1, fresh)
}

pub fn seal_bound(
    stages: &[(u8, usize)],
    secret: &[u8],
    key: &[u8],
    binding: &[u8; 32],
    version: u8,
    mut fresh: impl FnMut(usize) -> Vec<u8>,
) -> Vec<u8> {
    let nonces: Vec<_> = stages
        .iter()
        .map(|(id, _)| fresh(if *id == 2 { 24 } else { 12 }))
        .collect();
    let mut metadata = vec![1, stages.len() as u8, 0, version];
    let frame_len = (36 + secret.len()).div_ceil(64) * 64;
    metadata.extend_from_slice(&(frame_len as u32).to_be_bytes());
    for (i, (id, bits)) in stages.iter().enumerate() {
        metadata.extend_from_slice(&[*id, (*bits / 64 - 1) as u8]);
        metadata.extend_from_slice(&nonces[i]);
    }
    let salt = fresh(16);
    let mut header = salt.clone();
    header.extend_from_slice(&[2, 0]);
    header.extend_from_slice(&((metadata.len() + 16) as u16).to_be_bytes());
    let mn = fresh(24);
    header.extend_from_slice(&mn);
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), key);
    let mut mk = [0u8; 32];
    hkdf.expand(
        &fields(&[&domain("metadata-key"), &header, binding]),
        &mut mk,
    )
    .unwrap();
    let encrypted = aead(
        2,
        256,
        &mk,
        &mn,
        &fields(&[&domain("metadata-aad"), &header, binding]),
        &metadata,
    );
    let transcript = Sha256::digest(fields(&[
        &domain("descriptor"),
        &header,
        &metadata,
        binding,
    ]));
    let mut frame = fresh(frame_len);
    frame[..4].copy_from_slice(&(secret.len() as u32).to_be_bytes());
    frame[4..36].copy_from_slice(binding);
    frame[36..36 + secret.len()].copy_from_slice(secret);
    for (i, (id, bits)) in stages.iter().enumerate() {
        let identity = [i as u8, stages.len() as u8, *id, (*bits / 64 - 1) as u8];
        let mut sk = vec![0u8; bits / 8];
        hkdf.expand(
            &fields(&[&domain("layer-key"), &transcript, &identity]),
            &mut sk,
        )
        .unwrap();
        frame = aead(
            *id,
            *bits,
            &sk,
            &nonces[i],
            &fields(&[&domain("layer-aad"), &transcript, &identity]),
            &frame,
        );
    }
    [header, encrypted, frame].concat()
}
