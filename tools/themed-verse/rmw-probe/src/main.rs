//! Host-only comparison. Only return compressed bytes after exact reconstruction.
use std::io::{self, Read, Write};
use std::process::ExitCode;

fn main() -> io::Result<ExitCode> {
    if let Some(length) = std::env::args().nth(1) {
        let mut stream = Vec::new();
        io::stdin().take(2049).read_to_end(&mut stream)?;
        let Some(length) = length
            .parse::<usize>()
            .ok()
            .filter(|v| (1..=2048).contains(v))
        else {
            return Ok(ExitCode::FAILURE);
        };
        if stream.is_empty() || stream.len() > 2048 || stream.last() == Some(&0) {
            return Ok(ExitCode::FAILURE);
        }
        let mut bits = bit_vec::BitVec::from_bytes(&stream);
        while bits.pop() == Some(false) {}
        let mut output = Vec::new();
        for token in rmw_utf8::G.2.unbounded_decoder(&bits) {
            if token.len() > length - output.len() {
                return Ok(ExitCode::FAILURE);
            }
            output.extend_from_slice(token);
        }
        if output.len() != length || rmw_utf8::encode(&output) != stream {
            return Ok(ExitCode::FAILURE);
        }
        io::stdout().write_all(&output)?;
        return Ok(ExitCode::SUCCESS);
    }
    let mut original = String::new();
    io::stdin().take(2049).read_to_string(&mut original)?;
    if original.is_empty() || original.len() > 2048 {
        return Ok(ExitCode::from(1));
    }
    let encoded = rmw_utf8::encode(original.as_bytes());
    if rmw_utf8::decode(&encoded) != original {
        return Ok(ExitCode::from(2));
    }
    io::stdout().write_all(&encoded)?;
    Ok(ExitCode::SUCCESS)
}
