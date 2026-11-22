//! Public fixtures only. Uses RustCrypto's maintained GCM, never the reference project's adapter.
use sha2::{Digest, Sha256};
use hmac::{Hmac, Mac};
use zero_input_message_vectors::{aead, domain, fields, seal};

fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{b:02x}")).collect() }
fn hmac(key: &[u8], input: &[u8]) -> Vec<u8> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).unwrap();
    mac.update(input); mac.finalize().into_bytes().to_vec()
}
fn envelope(stages: &[(u8, usize)]) -> Vec<u8> {
    let mut counter: u8 = 0;
    let fresh = |size: usize| -> Vec<u8> { (0..size).map(|_| { let v=counter; counter=counter.wrapping_add(1); v }).collect() };
    seal(stages, b"public secret", &(0..32).collect::<Vec<u8>>(), b"public cover", fresh)
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
