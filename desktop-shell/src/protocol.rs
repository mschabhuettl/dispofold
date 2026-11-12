//! Line protocol between the window and the launcher, and the HMAC values of the identity check.
//! Secret, nonce and challenge travel as standard base64 with padding; the HMAC key is the decoded secret.
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use hmac::{Hmac, Mac};
use serde::Deserialize;
use serde_json::{json, Map, Value};
use sha2::Sha256;

pub const PROTOCOL: u32 = 1;

/// A line the launcher writes to its stdout.
#[derive(Debug, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum FromLauncher {
    #[serde(rename_all = "camelCase")]
    NeedPassword { first_start: bool, #[serde(default)] error: Option<String> },
    Locked { #[serde(default)] error: Option<String> },
    Starting { #[serde(default)] version: Option<String> },
    Ready { port: u16, pid: u64, nonce: String, proof: String },
    Warning { code: String, #[serde(default)] params: Map<String, Value> },
    Failed { code: String, #[serde(default)] params: Map<String, Value> },
    Exit { code: i32 },
}

/// Parses one stdout line; unknown or malformed lines give None (they are logged and ignored).
pub fn parse_line(line: &str) -> Option<FromLauncher> {
    serde_json::from_str(line.trim()).ok()
}

pub fn hello(secret: &[u8]) -> String {
    json!({ "type": "hello", "secret": B64.encode(secret), "protocol": PROTOCOL }).to_string()
}

pub fn unlock(password: &str) -> String {
    json!({ "type": "unlock", "password": password }).to_string()
}

pub fn quit() -> String {
    json!({ "type": "quit" }).to_string()
}

pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    getrandom::fill(&mut b).expect("operating system random source");
    b
}

pub fn random_b64<const N: usize>() -> String {
    B64.encode(random_bytes::<N>())
}

fn mac(secret: &[u8], msg: &str) -> Hmac<Sha256> {
    let mut m = Hmac::<Sha256>::new_from_slice(secret).expect("any key length");
    m.update(msg.as_bytes());
    m
}

fn sign(secret: &[u8], msg: &str) -> String {
    B64.encode(mac(secret, msg).finalize().into_bytes())
}

/// Constant-time check of a base64 HMAC over msg.
fn verify(secret: &[u8], msg: &str, given_b64: &str) -> bool {
    match B64.decode(given_b64) {
        Ok(given) => mac(secret, msg).verify_slice(&given).is_ok(),
        Err(_) => false,
    }
}

pub fn verify_ready(secret: &[u8], nonce: &str, port: u16, pid: u64, proof: &str) -> bool {
    verify(secret, &format!("ready|{nonce}|{port}|{pid}"), proof)
}

pub fn verify_challenge(secret: &[u8], challenge: &str, port: u16, response: &str) -> bool {
    verify(secret, &format!("challenge|{challenge}|{port}"), response)
}

/// Value of the X-Dispofold-Session header for one backend start: bound to the nonce of its ready line, so a
/// value caught from an earlier start is worthless for the next one.
pub fn session_value(secret: &[u8], nonce: &str) -> String {
    sign(secret, &format!("session|{nonce}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    // Shared test vector with the Java side: secret = bytes 0..31.
    fn secret() -> Vec<u8> {
        (0u8..32).collect()
    }
    const NONCE: &str = "ZGVmZ2hpamtsbW5vcHFycw==";
    const CHALLENGE: &str = "yMnKy8zNzs/Q0dLT1NXW1w==";
    const PROOF: &str = "AYue2OIBHSl9qTJ72vnA6EYg5Bfwo7h33kUQvks72S0=";
    const RESPONSE: &str = "2xRqM6Vq/DHnKq2h6WkHjjan37wHFoS7j8Q4lgK8HYg=";
    // session per backend start, for NONCE and for CHALLENGE used as another start's nonce
    const SESSION: &str = "HlTrRK+7tRifwGl3ub6w2wHw7WUuIeQnomq4q3SD4OQ=";
    const SESSION_OTHER_START: &str = "jmVcnKTTvbihIjiQiwWuqos2IqxqnPowQXDW5JYRWEg=";

    #[test]
    fn hello_carries_the_secret_as_base64() {
        let v: Value = serde_json::from_str(&hello(&secret())).unwrap();
        assert_eq!(v["type"], "hello");
        assert_eq!(v["secret"], "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        assert_eq!(v["protocol"], 1);
    }

    #[test]
    fn ready_proof_matches_the_vector() {
        assert!(verify_ready(&secret(), NONCE, 43210, 4242, PROOF));
    }

    #[test]
    fn ready_proof_rejects_other_port_pid_nonce_or_secret() {
        assert!(!verify_ready(&secret(), NONCE, 43211, 4242, PROOF));
        assert!(!verify_ready(&secret(), NONCE, 43210, 4243, PROOF));
        assert!(!verify_ready(&secret(), CHALLENGE, 43210, 4242, PROOF));
        assert!(!verify_ready(&[7u8; 32], NONCE, 43210, 4242, PROOF));
        assert!(!verify_ready(&secret(), NONCE, 43210, 4242, "not base64!"));
        assert!(!verify_ready(&secret(), NONCE, 43210, 4242, ""));
    }

    #[test]
    fn challenge_response_matches_the_vector() {
        assert!(verify_challenge(&secret(), CHALLENGE, 43210, RESPONSE));
        assert!(!verify_challenge(&secret(), CHALLENGE, 43211, RESPONSE));
        assert!(!verify_challenge(&secret(), NONCE, 43210, RESPONSE));
        // a proof is no valid challenge response (different message prefix)
        assert!(!verify_challenge(&secret(), CHALLENGE, 43210, PROOF));
    }

    #[test]
    fn session_value_matches_the_vector() {
        assert_eq!(session_value(&secret(), NONCE), SESSION);
        assert_eq!(session_value(&secret(), CHALLENGE), SESSION_OTHER_START);
        // the former constant value is gone
        assert_ne!(session_value(&secret(), NONCE), "mwtco9204+ZmTxJAJsd8jwU2Qb0TjR10W4g0xn7H9v0=");
    }

    #[test]
    fn parses_every_launcher_line() {
        assert_eq!(
            parse_line(r#"{"type":"needPassword","firstStart":true,"error":null}"#),
            Some(FromLauncher::NeedPassword { first_start: true, error: None })
        );
        assert_eq!(
            parse_line(r#"{"type":"needPassword","firstStart":false,"error":"desktop.password.wrong"}"#),
            Some(FromLauncher::NeedPassword { first_start: false, error: Some("desktop.password.wrong".into()) })
        );
        assert_eq!(
            parse_line(r#"{"type":"locked","error":"desktop.lock.held"}"#),
            Some(FromLauncher::Locked { error: Some("desktop.lock.held".into()) })
        );
        assert_eq!(
            parse_line(r#"{"type":"starting","version":"1.2.3"}"#),
            Some(FromLauncher::Starting { version: Some("1.2.3".into()) })
        );
        assert_eq!(
            parse_line(&format!(r#"{{"type":"ready","port":43210,"pid":4242,"nonce":"{NONCE}","proof":"{PROOF}"}}"#)),
            Some(FromLauncher::Ready { port: 43210, pid: 4242, nonce: NONCE.into(), proof: PROOF.into() })
        );
        assert!(matches!(
            parse_line(r#"{"type":"warning","code":"desktop.dataFolder.network","params":{}}"#),
            Some(FromLauncher::Warning { code, .. }) if code == "desktop.dataFolder.network"
        ));
        assert!(matches!(
            parse_line(r#"{"type":"failed","code":"desktop.start.failed","params":{"version":"1.0.0"}}"#),
            Some(FromLauncher::Failed { code, params }) if code == "desktop.start.failed" && params["version"] == "1.0.0"
        ));
        assert_eq!(parse_line("{\"type\":\"exit\",\"code\":0}\r\n"), Some(FromLauncher::Exit { code: 0 }));
    }

    #[test]
    fn ignores_malformed_or_unknown_lines() {
        assert_eq!(parse_line(""), None);
        assert_eq!(parse_line("Starting Quarkus"), None);
        assert_eq!(parse_line(r#"{"type":"bogus"}"#), None);
        assert_eq!(parse_line(r#"{"type":"ready","port":70000,"pid":1,"nonce":"a","proof":"b"}"#), None);
        assert_eq!(parse_line(r#"{"type":"ready","port":1}"#), None);
    }

    #[test]
    fn unlock_and_quit_lines_are_single_line_json() {
        let u = unlock("pass\"word\nwith newline");
        assert!(!u.contains('\n'));
        let v: Value = serde_json::from_str(&u).unwrap();
        assert_eq!(v["password"], "pass\"word\nwith newline");
        assert_eq!(quit(), r#"{"type":"quit"}"#);
    }
}
