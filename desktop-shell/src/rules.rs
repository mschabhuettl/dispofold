//! Pure rules of the window: own origin, navigation, redirects, forwarded headers, download names, texts.
use std::path::{Path, PathBuf};
use tauri::Url;

/// Scheme and host of the window's own origin. WebView2 maps custom schemes to http://<scheme>.localhost.
#[derive(Clone, Copy, Debug)]
pub struct Origin {
    pub scheme: &'static str,
    pub host: &'static str,
}

#[cfg_attr(windows, allow(dead_code))]
pub const APPLE_LINUX_ORIGIN: Origin = Origin { scheme: "dispofold", host: "localhost" };
#[cfg_attr(not(windows), allow(dead_code))]
pub const WINDOWS_ORIGIN: Origin = Origin { scheme: "http", host: "dispofold.localhost" };

#[cfg(windows)]
pub const OWN: Origin = WINDOWS_ORIGIN;
#[cfg(not(windows))]
pub const OWN: Origin = APPLE_LINUX_ORIGIN;

impl Origin {
    pub fn base(&self) -> String {
        format!("{}://{}", self.scheme, self.host)
    }

    fn is_own(&self, u: &Url) -> bool {
        u.scheme() == self.scheme && u.host_str() == Some(self.host) && u.port().is_none()
    }

    /// The window shows only its own origin and blob: URLs the page created itself (downloads).
    pub fn navigation_allowed(&self, u: &Url) -> bool {
        if self.is_own(u) || u.as_str() == "about:blank" {
            return true;
        }
        if u.scheme() == "blob" {
            // blob:<origin>/<uuid>; the inner URL must be our own origin
            return Url::parse(u.path()).map(|inner| self.is_own(&inner)).unwrap_or(false);
        }
        false
    }
}

/// The proxy follows a backend redirect only to the same loopback host and port, at most 5 times:
/// the request carries the session header, which must never leave for another listener.
pub fn redirect_allowed(previous: &Url, next: &Url, hops: usize) -> bool {
    hops <= 5
        && next.scheme() == "http"
        && previous.scheme() == "http"
        && next.host_str() == previous.host_str()
        && next.port_or_known_default() == previous.port_or_known_default()
}

/// Request headers the page may not set or that the client sets itself.
pub fn forward_request_header(name: &str) -> bool {
    !matches!(
        name.to_ascii_lowercase().as_str(),
        "host" | "cookie" | "connection" | "content-length" | "transfer-encoding" | "accept-encoding" | "keep-alive"
            | "proxy-authorization" | "upgrade" | "te" | "x-dispofold-session"
    )
}

/// Response headers the window never sees: no cookies, no hop-by-hop headers, the shell sets its own CSP.
pub fn forward_response_header(name: &str) -> bool {
    !matches!(
        name.to_ascii_lowercase().as_str(),
        "set-cookie" | "connection" | "transfer-encoding" | "content-length" | "keep-alive" | "content-security-policy"
    )
}

/// File name for a download: only the last path component of the suggestion, never empty or hidden.
pub fn download_name(suggested: Option<&Path>) -> String {
    let raw = suggested.and_then(|p| p.file_name()).map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let clean: String = raw
        .chars()
        .map(|c| if c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') { '_' } else { c })
        .collect();
    let clean = clean.trim().trim_start_matches('.').to_string();
    if clean.is_empty() {
        "download".into()
    } else {
        clean
    }
}

/// First free path in dir for name: "name.ext", then "name (2).ext", "name (3).ext", ...
pub fn unique_path(dir: &Path, name: &str, exists: impl Fn(&Path) -> bool) -> PathBuf {
    let first = dir.join(name);
    if !exists(&first) {
        return first;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    (2..)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|p| !exists(p))
        .expect("an unused name")
}

/// German text for a code from the launcher or the window itself; None for an unknown code.
/// Parameters are ids, versions and numbers (never names or paths) and are filled in as they come.
pub fn known_text(code: &str, params: &serde_json::Map<String, serde_json::Value>) -> Option<String> {
    let text = match code {
        "desktop.password.wrong" => "Das Passwort ist falsch.",
        "desktop.password.short" => "Das Passwort muss mindestens 12 Zeichen lang sein.",
        "desktop.password.mismatch" => "Die beiden Passwörter stimmen nicht überein.",
        "desktop.lock.held" => "Dispofold läuft bereits mit diesem Datenordner. Bitte das schon geöffnete Fenster verwenden.",
        "desktop.identity.failed" => "Dispofold konnte seinen eigenen Hintergrunddienst nicht sicher erkennen und wurde \
            zum Schutz der Daten beendet. Bitte Dispofold neu starten.",
        "desktop.launcher.ended" => "Der Hintergrunddienst von Dispofold wurde unerwartet beendet.",
        "desktop.program.incomplete" => "Die Programmdateien von Dispofold sind unvollständig. Bitte Dispofold neu \
            installieren oder das ZIP erneut entpacken.",
        "desktop.dataFolder.network" => "Der Datenordner liegt auf einem Netzlaufwerk. Das kann die Datenbank \
            beschädigen. Empfohlen ist ein lokaler Ordner.",
        "desktop.update.refused" => "Ein Update wurde abgelehnt, weil es die Prüfung nicht bestanden hat (Grund: \
            {reason}). Dispofold arbeitet mit der bisherigen Version weiter.",
        "desktop.update.rolledBack" => "Die Version {version} ist nicht gestartet. Dispofold arbeitet wieder mit der \
            vorherigen Version.",
        "desktop.version.damaged" => "Die Version {version} ist beschädigt und wird nicht verwendet (Grund: {reason}).",
        "desktop.state.damaged" => "Die gespeicherten Versionsangaben von Dispofold waren beschädigt und wurden nicht \
            verwendet.",
        "desktop.protocol.invalid" => "Programmfenster und Hintergrunddienst von Dispofold passen nicht zusammen. \
            Bitte Dispofold neu installieren.",
        "desktop.launcher.usage" => "Dispofold wurde mit ungültigen Angaben gestartet.",
        "desktop.launcher.programDirMissing" => "Dem Hintergrunddienst fehlt der Programmordner. Bitte Dispofold neu \
            installieren.",
        "desktop.launcher.error" => "Im Hintergrunddienst von Dispofold ist ein unerwarteter Fehler aufgetreten \
            ({type}).",
        "desktop.translocated" => "macOS hat Dispofold in einen versteckten, schreibgeschützten Ordner kopiert. Bitte \
            Dispofold.app in den Ordner \u{201e}Programme\u{201c} ziehen und von dort neu starten.",
        "desktop.dataFolder.unsafe" => "Der Pfad des Datenordners enthält Zeichen, die Dispofold nicht verwenden kann \
            (\";\" oder \"${\"). Bitte einen anderen Ordner verwenden.",
        "desktop.version.dataNewer" => "Die Daten wurden zuletzt mit der neueren Version {lastStarted} geöffnet. \
            Version {version} kann sie nicht verwenden. Bitte die neuere Version installieren.",
        "desktop.app.startFailed" => "Dispofold {version} konnte nicht gestartet werden.",
        "desktop.app.crashed" => "Dispofold {version} wurde unerwartet beendet (Code {code}).",
        _ => return None,
    };
    let mut out = text.to_string();
    for (k, v) in params {
        let value = match v {
            serde_json::Value::String(s) => s.clone(),
            other => other.to_string(),
        };
        out = out.replace(&format!("{{{k}}}"), &value);
    }
    Some(out)
}

/// German text for any code: the known text, else a generic one that names the code.
pub fn message(code: &str, params: &serde_json::Map<String, serde_json::Value>) -> String {
    known_text(code, params).unwrap_or_else(|| format!("Dispofold konnte nicht gestartet werden (Fehlercode {code})."))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u(s: &str) -> Url {
        Url::parse(s).unwrap()
    }

    #[test]
    fn navigation_stays_on_the_own_origin_linux_and_macos() {
        let o = APPLE_LINUX_ORIGIN;
        assert!(o.navigation_allowed(&u("dispofold://localhost/")));
        assert!(o.navigation_allowed(&u("dispofold://localhost/__shell/unlock")));
        assert!(o.navigation_allowed(&u("blob:dispofold://localhost/6abcd9e6-746a-450f-98d5-c7bcffda1d18")));
        assert!(o.navigation_allowed(&u("about:blank")));
        assert!(!o.navigation_allowed(&u("dispofold://other/")));
        assert!(!o.navigation_allowed(&u("dispofold://localhost:8080/")));
        assert!(!o.navigation_allowed(&u("http://127.0.0.1:43210/")));
        assert!(!o.navigation_allowed(&u("https://example.org/")));
        assert!(!o.navigation_allowed(&u("blob:https://example.org/6abcd9e6")));
        assert!(!o.navigation_allowed(&u("file:///etc/passwd")));
        assert!(!o.navigation_allowed(&u("http://dispofold.localhost/")));
    }

    #[test]
    fn navigation_stays_on_the_own_origin_windows() {
        let o = WINDOWS_ORIGIN;
        assert!(o.navigation_allowed(&u("http://dispofold.localhost/")));
        assert!(o.navigation_allowed(&u("http://dispofold.localhost/plan?m=2027-01")));
        assert!(o.navigation_allowed(&u("blob:http://dispofold.localhost/6abcd9e6-746a")));
        assert!(!o.navigation_allowed(&u("https://dispofold.localhost/")));
        assert!(!o.navigation_allowed(&u("http://dispofold.localhost:81/")));
        assert!(!o.navigation_allowed(&u("http://evil.localhost/")));
        assert!(!o.navigation_allowed(&u("http://127.0.0.1:43210/")));
        assert!(!o.navigation_allowed(&u("dispofold://localhost/")));
    }

    #[test]
    fn redirects_only_to_the_same_host_and_port() {
        let prev = u("http://127.0.0.1:43210/api/x");
        assert!(redirect_allowed(&prev, &u("http://127.0.0.1:43210/api/y"), 1));
        assert!(!redirect_allowed(&prev, &u("http://127.0.0.1:43211/api/y"), 1));
        assert!(!redirect_allowed(&prev, &u("http://localhost:43210/api/y"), 1));
        assert!(!redirect_allowed(&prev, &u("https://127.0.0.1:43210/api/y"), 1));
        assert!(!redirect_allowed(&prev, &u("http://example.org/"), 1));
        assert!(!redirect_allowed(&prev, &u("http://127.0.0.1:43210/api/y"), 6));
    }

    #[test]
    fn page_cannot_set_the_session_or_cookies() {
        for h in ["X-Dispofold-Session", "cookie", "Host", "content-length", "Transfer-Encoding"] {
            assert!(!forward_request_header(h), "{h}");
        }
        for h in ["content-type", "accept", "if-match", "origin", "sec-fetch-site"] {
            assert!(forward_request_header(h), "{h}");
        }
        assert!(!forward_response_header("Set-Cookie"));
        assert!(!forward_response_header("content-security-policy"));
        assert!(forward_response_header("content-disposition"));
        assert!(forward_response_header("content-type"));
    }

    #[test]
    fn every_launcher_code_in_docs_has_a_german_text() {
        // docs/desktop.md, section "Meldungen des Launchers": every `desktop.x.y` code listed there
        let doc = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../docs/desktop.md")).unwrap();
        let section = doc.split("### Meldungen des Launchers").nth(1).expect("section in docs/desktop.md");
        let section = section.split("\n#").next().unwrap();
        let codes: Vec<&str> = section
            .split('`')
            .skip(1)
            .step_by(2)
            .filter(|c| c.starts_with("desktop.") && !c.contains('*') && c.chars().all(|ch| ch.is_ascii_alphanumeric() || ch == '.'))
            .collect();
        assert!(codes.len() >= 13, "codes found: {codes:?}");
        let none = serde_json::Map::new();
        let missing: Vec<&&str> = codes.iter().filter(|c| known_text(c, &none).is_none()).collect();
        assert!(missing.is_empty(), "no German text for {missing:?}");
        // the codes the window itself and the protocol use
        for c in ["desktop.password.wrong", "desktop.password.short", "desktop.lock.held", "desktop.identity.failed",
            "desktop.launcher.ended", "desktop.program.incomplete"] {
            assert!(known_text(c, &none).is_some(), "{c}");
        }
    }

    #[test]
    fn texts_fill_in_parameters_and_fall_back_with_the_code() {
        let p: serde_json::Map<String, serde_json::Value> =
            serde_json::from_str(r#"{"version":"1.2.3","code":137}"#).unwrap();
        assert_eq!(message("desktop.app.crashed", &p), "Dispofold 1.2.3 wurde unerwartet beendet (Code 137).");
        assert_eq!(message("desktop.something.new", &p), "Dispofold konnte nicht gestartet werden (Fehlercode desktop.something.new).");
        assert!(!message("desktop.translocated", &p).contains('{'));
    }

    #[test]
    fn download_names_never_overwrite() {
        let dir = Path::new("/d");
        let taken = ["/d/plan.json", "/d/plan (2).json", "/d/README"];
        let exists = |p: &Path| taken.iter().any(|t| Path::new(t) == p);
        assert_eq!(unique_path(dir, "neu.csv", exists), PathBuf::from("/d/neu.csv"));
        assert_eq!(unique_path(dir, "plan.json", exists), PathBuf::from("/d/plan (3).json"));
        assert_eq!(unique_path(dir, "README", exists), PathBuf::from("/d/README (2)"));
    }

    #[test]
    fn download_names_are_plain_file_names() {
        assert_eq!(download_name(Some(Path::new("/tmp/x/dienstplan-2027-01.json"))), "dienstplan-2027-01.json");
        assert_eq!(download_name(Some(Path::new(".bashrc"))), "bashrc");
        assert_eq!(download_name(Some(Path::new("a:b?.csv"))), "a_b_.csv");
        assert_eq!(download_name(None), "download");
        assert_eq!(download_name(Some(Path::new(".."))), "download");
    }
}
