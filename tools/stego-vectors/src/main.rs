//! Public fixtures only. Uses RustCrypto's maintained GCM, never the reference project's adapter.
use aes_gcm::{aead::{Aead, KeyInit, Payload}, AesGcm, Nonce};
use aes_gcm::aead::consts::U12;
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use sha2::{Digest, Sha256};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};

fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{b:02x}")).collect() }
fn fields(values: &[&[u8]]) -> Vec<u8> {
    values.iter().flat_map(|v| { let mut f = (v.len() as u32).to_be_bytes().to_vec(); f.extend_from_slice(v); f }).collect()
}
fn domain(value: &str) -> Vec<u8> { format!("ZeroInput/message/v1/{value}").into_bytes() }
fn hmac(key: &[u8], input: &[u8]) -> Vec<u8> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).unwrap();
    mac.update(input); mac.finalize().into_bytes().to_vec()
}
fn aead(id: u8, bits: usize, key: &[u8], nonce: &[u8], aad: &[u8], input: &[u8]) -> Vec<u8> {
    let payload = Payload { msg: input, aad };
    macro_rules! gcm { ($cipher:ty) => { AesGcm::<$cipher,U12>::new_from_slice(key).unwrap().encrypt(Nonce::from_slice(nonce), payload).unwrap() }; }
    match (id, bits) {
        (1,128) => gcm!(aes::Aes128), (1,192) => gcm!(aes::Aes192), (1,256) => gcm!(aes::Aes256),
        (2,256) => XChaCha20Poly1305::new_from_slice(key).unwrap().encrypt(XNonce::from_slice(nonce), payload).unwrap(),
        (3,256) => gcm!(kuznyechik::Kuznyechik),
        (4,128 | 192 | 256) => AesGcm::<twofish::Twofish,U12>::from(
            twofish::Twofish::new_from_slice(key).unwrap()).encrypt(Nonce::from_slice(nonce), payload).unwrap(),
        (5,128) => gcm!(camellia::Camellia128), (5,192) => gcm!(camellia::Camellia192), (5,256) => gcm!(camellia::Camellia256),
        (6,128) => gcm!(sm4::Sm4), _ => panic!("unsupported fixture"),
    }
}

fn envelope(stages: &[(u8, usize)]) -> Vec<u8> {
    let mut counter: u8 = 0;
    let mut fresh = |size: usize| -> Vec<u8> { (0..size).map(|_| { let v=counter; counter=counter.wrapping_add(1); v }).collect() };
    let nonces: Vec<_> = stages.iter().map(|(id,_)| fresh(if *id==2 {24} else {12})).collect();
    let mut metadata = vec![1, stages.len() as u8, 0, 1];
    metadata.extend_from_slice(&64u32.to_be_bytes());
    for (i,(id,bits)) in stages.iter().enumerate() { metadata.extend_from_slice(&[*id,(*bits/64-1) as u8]); metadata.extend_from_slice(&nonces[i]); }
    let salt = fresh(16);
    let mut header = salt.clone(); header.extend_from_slice(&[2,0]);
    header.extend_from_slice(&((metadata.len()+16) as u16).to_be_bytes()); let mn=fresh(24); header.extend_from_slice(&mn);
    let binding = Sha256::digest(fields(&[&domain("exact-cover"), b"public cover"]));
    let key: Vec<u8> = (0..32).collect();
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), &key);
    let mut mk = [0u8;32]; hkdf.expand(&fields(&[&domain("metadata-key"), &header, &binding]), &mut mk).unwrap();
    let encrypted = aead(2,256,&mk,&mn,&fields(&[&domain("metadata-aad"),&header,&binding]),&metadata);
    let transcript = Sha256::digest(fields(&[&domain("descriptor"),&header,&metadata,&binding]));
    let secret = b"public secret";
    let mut frame = fresh(64); frame[..4].copy_from_slice(&(secret.len() as u32).to_be_bytes());
    frame[4..36].copy_from_slice(&binding); frame[36..36+secret.len()].copy_from_slice(secret);
    for (i,(id,bits)) in stages.iter().enumerate() {
        let identity = [i as u8,stages.len() as u8,*id,(*bits/64-1) as u8];
        let mut sk=vec![0u8;bits/8]; hkdf.expand(&fields(&[&domain("layer-key"),&transcript,&identity]),&mut sk).unwrap();
        frame=aead(*id,*bits,&sk,&nonces[i],&fields(&[&domain("layer-aad"),&transcript,&identity]),&frame);
    }
    [header,encrypted,frame].concat()
}

fn main() {
    println!("# RustCrypto independent public message fixtures; id,bits,key,nonce,aad,plaintext,ciphertext_tag");
    for (id,widths) in [(1,vec![128,192,256]),(2,vec![256]),(3,vec![256]),(4,vec![128,192,256]),(5,vec![128,192,256]),(6,vec![128])] {
        for bits in widths {
            let key: Vec<u8>=(0..bits/8).map(|i|i as u8).collect();
            let nonce: Vec<u8>=(0..if id==2 {24} else {12}).map(|i|0xa0+i).collect();
            let aad=b"public descriptor fixture"; let text=b"independent public payload with a partial block";
            println!("{id},{bits},{},{},{},{},{}",hex(&key),hex(&nonce),hex(aad),hex(text),hex(&aead(id,bits,&key,&nonce,aad,text)));
        }
    }
    let salt: Vec<u8> = (0..16).collect();
    for (name,memory,iterations) in [("standard",65536,3),("increased",131072,3),("high-memory",262144,4)] {
        let params=argon2::Params::new(memory,iterations,1,Some(32)).unwrap(); let mut out=[0u8;32];
        argon2::Argon2::new(argon2::Algorithm::Argon2id,argon2::Version::V0x13,params)
            .hash_password_into(b"public fixture passphrase",&salt,&mut out).unwrap();
        println!("# argon2id-{name}={}",hex(&out));
    }
    let file: Vec<u8> = (0..128).collect();
    let file_factor = Sha256::digest(fields(&[&domain("key-file"),&128u64.to_be_bytes(),&Sha256::digest(&file)]));
    println!("# key-file-factor={}",hex(&file_factor));
    let random: Vec<u8> = (0..32).collect();
    for mode in 1..=5 {
        let factor = match mode {
            1 => b"public fixture passphrase".to_vec(),
            2 => random.clone(),
            3 => file_factor.to_vec(),
            4 => hmac(&file_factor,&fields(&[&domain("passphrase-file"),b"public fixture passphrase"])),
            5 => hmac(&file_factor,&fields(&[&domain("random-key-file"),&random])),
            _ => unreachable!()
        };
        let root = if mode==1 || mode==4 {
            let mut out=vec![0u8;32];
            argon2::Argon2::new(argon2::Algorithm::Argon2id,argon2::Version::V0x13,
                argon2::Params::new(65536,3,1,Some(32)).unwrap()).hash_password_into(&factor,&salt,&mut out).unwrap();
            out
        } else { factor };
        println!("# credential-root-{mode}={}",hex(&hmac(&salt,&root)));
    }
    println!("# envelope-aes={}",hex(&envelope(&[(1,256)])));
    println!("# envelope-six={}",hex(&envelope(&[(1,256),(2,256),(3,256),(4,256),(5,256),(6,128)])));
}
