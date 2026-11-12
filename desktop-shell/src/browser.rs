//! Linux browser front end: the same proxy behind a loopback HTTP listener on `<name>.localhost:<port>`, entered
//! with a one-time launch code from a mode-600 launch file, then held by a host-only session cookie that is
//! separate from the backend secret.
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;
use base64::Engine;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub const COOKIE: &str = "dispofold_s";
const CODE_LIFETIME: Duration = Duration::from_secs(60);

/// Host name and port of one installation and data folder, kept so that the origin (and localStorage) survives.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Identity {
    pub name: String,
    pub port: u16,
}

pub struct Browser {
    pub host: String,
    session: String,
    code: Mutex<Option<(String, Instant)>>,
    launch_dir: PathBuf,
}

/// File in the state folder that holds the identity for this program and data folder.
pub fn identity_file(state_dir: &Path, program: &Path, data: Option<&Path>) -> PathBuf {
    let mut h = Sha256::new();
    h.update(program.to_string_lossy().as_bytes());
    h.update(b"|");
    h.update(data.map(|d| d.to_string_lossy().into_owned()).unwrap_or_default().as_bytes());
    let key: String = h.finalize().iter().take(8).map(|b| format!("{b:02x}")).collect();
    state_dir.join(format!("browser-{key}.json"))
}

/// Random 16-character lowercase name for the host `<name>.localhost`.
pub fn new_name() -> String {
    crate::protocol::random_bytes::<16>().iter().map(|b| (b'a' + b % 26) as char).collect()
}

pub fn load_identity(file: &Path) -> Option<Identity> {
    let id: Identity = serde_json::from_slice(&std::fs::read(file).ok()?).ok()?;
    let valid = id.name.len() == 16 && id.name.bytes().all(|b| b.is_ascii_lowercase());
    valid.then_some(id)
}

pub fn save_identity(file: &Path, id: &Identity) {
    if let Some(dir) = file.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let _ = std::fs::write(file, serde_json::to_vec(id).unwrap());
}

/// Listeners of the browser front end: 127.0.0.1 and [::1] on the same port. Browsers try `<name>.localhost` on
/// ::1 first, so the shell must hold that address too, or another local user could listen there and catch the
/// launch code and the cookie. The stored port is used if both are free, else a new port.
pub struct Listeners {
    pub v4: std::net::TcpListener,
    pub v6: Option<std::net::TcpListener>,
}

pub fn bind(preferred: Option<u16>) -> std::io::Result<Listeners> {
    let mut candidates: Vec<u16> = preferred.filter(|p| *p != 0).into_iter().collect();
    candidates.extend(std::iter::repeat_n(0, 20));
    let mut last = std::io::Error::other("no free port");
    for c in candidates {
        let v4 = match std::net::TcpListener::bind(("127.0.0.1", c)) {
            Ok(l) => l,
            Err(e) => {
                last = e;
                continue;
            }
        };
        let port = v4.local_addr()?.port();
        match std::net::TcpListener::bind(("::1", port)) {
            Ok(v6) => return Ok(Listeners { v4, v6: Some(v6) }),
            // no IPv6 loopback on this machine: nobody can listen there either
            Err(e) if matches!(e.kind(), std::io::ErrorKind::AddrNotAvailable | std::io::ErrorKind::Unsupported) => {
                return Ok(Listeners { v4, v6: None })
            }
            Err(e) => last = e, // [::1]:port taken by someone else: try another port
        }
    }
    Err(last)
}

fn equal(a: &str, b: &str) -> bool {
    // constant time through the HMAC comparison
    let mut m = Hmac::<Sha256>::new_from_slice(b"compare").unwrap();
    m.update(a.as_bytes());
    let mut n = Hmac::<Sha256>::new_from_slice(b"compare").unwrap();
    n.update(b.as_bytes());
    n.verify_slice(&m.finalize().into_bytes()).is_ok()
}

impl Browser {
    pub fn new(name: &str, port: u16, launch_dir: PathBuf) -> Browser {
        Browser {
            host: format!("{name}.localhost:{port}"),
            session: B64URL.encode(crate::protocol::random_bytes::<32>()),
            code: Mutex::new(None),
            launch_dir,
        }
    }

    pub fn origin(&self) -> String {
        format!("http://{}", self.host)
    }

    /// Requests for any other host name (DNS rebinding, other local names) are refused with 421.
    pub fn host_ok(&self, host: Option<&str>) -> bool {
        host == Some(self.host.as_str())
    }

    /// A present Origin must be our own; the proxy drops it afterwards (the backend knows only the window origins).
    pub fn origin_ok(&self, origin: Option<&str>) -> bool {
        origin.is_none_or(|o| o == self.origin())
    }

    pub fn cookie_ok(&self, cookie_header: Option<&str>) -> bool {
        cookie_header
            .into_iter()
            .flat_map(|h| h.split(';'))
            .filter_map(|c| c.trim().split_once('='))
            .any(|(k, v)| k == COOKIE && equal(v, &self.session))
    }

    pub fn set_cookie(&self) -> String {
        format!("{COOKIE}={}; HttpOnly; SameSite=Strict; Path=/", self.session)
    }

    /// New one-time code; an older one is no longer valid.
    pub fn mint_code(&self) -> String {
        let c = B64URL.encode(crate::protocol::random_bytes::<24>());
        *self.code.lock().unwrap() = Some((c.clone(), Instant::now()));
        c
    }

    /// True once for the current code within its lifetime.
    pub fn take_code(&self, given: &str) -> bool {
        self.take_code_at(given, Instant::now())
    }

    fn take_code_at(&self, given: &str, now: Instant) -> bool {
        let mut slot = self.code.lock().unwrap();
        match slot.as_ref() {
            Some((c, at)) if equal(c, given) => {
                let fresh = now.duration_since(*at) <= CODE_LIFETIME;
                *slot = None;
                fresh
            }
            _ => false,
        }
    }

    /// Writes the launch file (readable only by the user) that sends the browser to the launch page with the code
    /// in the fragment; the code never appears in a command line.
    pub fn write_launch_file(&self, dir: &Path) -> std::io::Result<PathBuf> {
        let code = self.mint_code();
        let url = format!("{}/__shell/launch#{code}", self.origin());
        let html = format!(
            "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>Dispofold</title>\
             <meta http-equiv=\"refresh\" content=\"0;url={url}\"></head>\
             <body><a href=\"{url}\">Dispofold öffnen</a></body></html>"
        );
        std::fs::create_dir_all(dir)?;
        let file = dir.join("dispofold-start.html");
        let _ = std::fs::remove_file(&file);
        write_private(&file, html.as_bytes())?;
        Ok(file)
    }

    /// Mints a code, writes the launch file and opens the browser with it.
    pub fn open(&self) -> Result<(), String> {
        let file = self.write_launch_file(&self.launch_dir).map_err(|e| format!("launch file: {}", e.kind()))?;
        open_browser(&file)
    }
}

#[cfg(unix)]
fn write_private(file: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(file)?;
    f.write_all(bytes)
}

#[cfg(not(unix))]
fn write_private(file: &Path, bytes: &[u8]) -> std::io::Result<()> {
    std::fs::write(file, bytes)
}

/// Folder for the launch file that the default browser can read: snap and flatpak browsers see neither /run/user
/// nor hidden folders in home, but they read their own per-user folder.
pub fn launch_dir(desktop_id: Option<&str>) -> PathBuf {
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
    if let Some(id) = desktop_id {
        // snap desktop ids look like firefox_firefox.desktop, chromium_chromium.desktop
        if let Some((snap, _)) = id.split_once('_') {
            // the snap's own common folder (created by snapd on first use; creating it earlier is harmless)
            if Path::new("/snap/bin").join(snap).exists() {
                return home.join("snap").join(snap).join("common").join("dispofold");
            }
        }
        let app = id.trim_end_matches(".desktop");
        let flatpak = home.join(".var").join("app").join(app);
        if flatpak.is_dir() {
            return flatpak.join("cache").join("dispofold");
        }
    }
    dirs::runtime_dir().or_else(dirs::cache_dir).unwrap_or(home).join("dispofold")
}

pub fn default_desktop_id() -> Option<String> {
    let out = Command::new("xdg-settings").args(["get", "default-web-browser"]).output().ok()?;
    let id = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!id.is_empty()).then_some(id)
}

/// Exec line of a desktop entry, split into words (field codes removed).
fn desktop_exec(id: &str) -> Option<Vec<String>> {
    let mut dirs_list = vec![dirs::data_dir()?];
    let sys = std::env::var("XDG_DATA_DIRS").unwrap_or_else(|_| "/usr/local/share:/usr/share".into());
    dirs_list.extend(sys.split(':').map(PathBuf::from));
    dirs_list.push(PathBuf::from("/var/lib/snapd/desktop"));
    for d in dirs_list {
        let Ok(text) = std::fs::read_to_string(d.join("applications").join(id)) else { continue };
        let exec = text
            .lines()
            .skip_while(|l| l.trim() != "[Desktop Entry]")
            .find_map(|l| l.strip_prefix("Exec="))?;
        let words: Vec<String> = exec
            .split_whitespace()
            .filter(|w| !w.starts_with('%'))
            .map(|w| w.trim_matches('"').to_string())
            .collect();
        return (!words.is_empty()).then_some(words);
    }
    None
}

pub fn is_chromium_family(id_or_cmd: &str) -> bool {
    let s = id_or_cmd.to_ascii_lowercase();
    ["chromium", "chrome", "brave", "microsoft-edge", "msedge", "vivaldi"].iter().any(|n| s.contains(n))
}

/// Command that opens the launch file: $BROWSER if set, else the default browser's own command (Chromium family
/// in app mode), else xdg-open.
pub fn browser_command(file: &Path, env_browser: Option<String>, desktop_id: Option<&str>) -> Vec<String> {
    let url = format!("file://{}", file.display());
    if let Some(b) = env_browser.filter(|b| !b.trim().is_empty()) {
        let mut words: Vec<String> = b.split(':').next().unwrap_or("").split_whitespace().map(String::from).collect();
        words.push(file.display().to_string());
        return words;
    }
    if let Some(mut exec) = desktop_id.and_then(desktop_exec) {
        if desktop_id.is_some_and(is_chromium_family) || is_chromium_family(&exec[0]) {
            exec.push(format!("--app={url}"));
        } else {
            exec.push(url);
        }
        return exec;
    }
    vec!["xdg-open".into(), file.display().to_string()]
}

pub fn open_browser(file: &Path) -> Result<(), String> {
    let id = default_desktop_id();
    let cmd = browser_command(file, std::env::var("BROWSER").ok(), id.as_deref());
    eprintln!("[window] opening the browser: {}", cmd[0]);
    Command::new(&cmd[0])
        .args(&cmd[1..])
        .stdin(std::process::Stdio::null())
        .spawn()
        .map(|mut child| {
            // reap it when it ends (a browser that hands over to a running instance ends at once)
            std::thread::spawn(move || child.wait());
        })
        .map_err(|e| format!("browser: {}", e.kind()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("dispofold-{tag}-{}", new_name()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn host_must_match_exactly() {
        let b = Browser::new("abcdefghijklmnop", 41234, PathBuf::from("."));
        assert!(b.host_ok(Some("abcdefghijklmnop.localhost:41234")));
        assert!(!b.host_ok(Some("abcdefghijklmnop.localhost")));
        assert!(!b.host_ok(Some("127.0.0.1:41234")));
        assert!(!b.host_ok(Some("localhost:41234")));
        assert!(!b.host_ok(Some("evil.example:41234")));
        assert!(!b.host_ok(Some("ABCDEFGHIJKLMNOP.localhost:41234")));
        assert!(!b.host_ok(None));
    }

    #[test]
    fn origin_must_be_own_when_present() {
        let b = Browser::new("abcdefghijklmnop", 41234, PathBuf::from("."));
        assert!(b.origin_ok(None));
        assert!(b.origin_ok(Some("http://abcdefghijklmnop.localhost:41234")));
        assert!(!b.origin_ok(Some("http://evil.localhost:41234")));
        assert!(!b.origin_ok(Some("null")));
    }

    #[test]
    fn session_cookie_is_checked_and_host_only() {
        let b = Browser::new("abcdefghijklmnop", 41234, PathBuf::from("."));
        let set = b.set_cookie();
        assert!(set.contains("HttpOnly") && set.contains("SameSite=Strict") && set.contains("Path=/"));
        assert!(!set.to_ascii_lowercase().contains("domain="));
        let value = set.split(';').next().unwrap();
        assert!(b.cookie_ok(Some(value)));
        assert!(b.cookie_ok(Some(&format!("theme=dark; {value}; x=1"))));
        assert!(!b.cookie_ok(None));
        assert!(!b.cookie_ok(Some("dispofold_s=wrong")));
        assert!(!b.cookie_ok(Some(&value.replace(COOKIE, "other"))));
        // a new start has another session value
        let other = Browser::new("abcdefghijklmnop", 41234, PathBuf::from("."));
        assert!(!other.cookie_ok(Some(value)));
    }

    #[test]
    fn launch_code_works_once_and_expires() {
        let b = Browser::new("abcdefghijklmnop", 41234, PathBuf::from("."));
        let c = b.mint_code();
        assert!(!b.take_code("wrong"));
        assert!(b.take_code(&c), "first use");
        assert!(!b.take_code(&c), "reuse");
        let old = b.mint_code();
        let new = b.mint_code();
        assert!(!b.take_code(&old), "replaced by a newer code");
        assert!(b.take_code(&new));
        let late = b.mint_code();
        let at = b.code.lock().unwrap().as_ref().unwrap().1;
        assert!(!b.take_code_at(&late, at + Duration::from_secs(61)), "expired");
        assert!(!b.take_code(&late), "expired code is gone");
    }

    #[cfg(unix)]
    #[test]
    fn launch_file_is_private_and_carries_the_code_only_in_the_fragment() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tmp("launch");
        let b = Browser::new("abcdefghijklmnop", 41234, dir.clone());
        let f = b.write_launch_file(&dir).unwrap();
        assert_eq!(std::fs::metadata(&f).unwrap().permissions().mode() & 0o777, 0o600);
        let html = std::fs::read_to_string(&f).unwrap();
        let code = b.code.lock().unwrap().as_ref().unwrap().0.clone();
        assert!(html.contains(&format!("http://abcdefghijklmnop.localhost:41234/__shell/launch#{code}")));
        // written again (second start): still private, old code invalid
        let f2 = b.write_launch_file(&dir).unwrap();
        assert_eq!(std::fs::metadata(&f2).unwrap().permissions().mode() & 0o777, 0o600);
        assert!(!b.take_code(&code));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn identity_is_kept_per_program_and_data_folder() {
        let dir = tmp("state");
        let a = identity_file(&dir, Path::new("/usr/lib/Dispofold"), None);
        let b = identity_file(&dir, Path::new("/usr/lib/Dispofold"), Some(Path::new("/srv/daten")));
        assert_ne!(a, b);
        assert_eq!(a, identity_file(&dir, Path::new("/usr/lib/Dispofold"), None));
        assert_eq!(load_identity(&a), None);
        let id = Identity { name: new_name(), port: 41234 };
        assert!(id.name.len() == 16 && id.name.bytes().all(|c| c.is_ascii_lowercase()));
        save_identity(&a, &id);
        assert_eq!(load_identity(&a), Some(id));
        std::fs::write(&b, r#"{"name":"Bad.Name","port":1}"#).unwrap();
        assert_eq!(load_identity(&b), None);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn bind_takes_the_stored_port_if_free_on_both_loopbacks() {
        let first = bind(None).unwrap();
        let port = first.v4.local_addr().unwrap().port();
        if let Some(v6) = &first.v6 {
            assert_eq!(v6.local_addr().unwrap().port(), port);
            assert!(v6.local_addr().unwrap().ip().is_loopback());
        }
        // taken: a new port
        let other = bind(Some(port)).unwrap();
        assert_ne!(other.v4.local_addr().unwrap().port(), port);
        drop(first);
        let again = bind(Some(port)).unwrap();
        assert_eq!(again.v4.local_addr().unwrap().port(), port);
        assert!(again.v4.local_addr().unwrap().ip().is_loopback());
    }

    #[test]
    fn a_squatter_on_ipv6_loopback_moves_the_port() {
        // someone else listens on [::1]:port; the shell must not use that port
        let Ok(squat) = std::net::TcpListener::bind(("::1", 0)) else { return }; // no IPv6 here
        let port = squat.local_addr().unwrap().port();
        let l = bind(Some(port)).unwrap();
        assert_ne!(l.v4.local_addr().unwrap().port(), port);
        assert!(l.v6.is_some());
    }

    #[test]
    fn browser_command_never_carries_the_code() {
        let f = Path::new("/run/user/1000/dispofold/dispofold-start.html");
        assert_eq!(browser_command(f, Some("my-browser --new".into()), None), vec!["my-browser", "--new", f.to_str().unwrap()]);
        assert_eq!(browser_command(f, None, Some("does-not-exist.desktop")), vec!["xdg-open", f.to_str().unwrap()]);
        assert!(is_chromium_family("google-chrome.desktop"));
        assert!(is_chromium_family("chromium_chromium.desktop"));
        assert!(is_chromium_family("/usr/bin/brave-browser-stable"));
        assert!(!is_chromium_family("firefox_firefox.desktop"));
        assert!(!is_chromium_family("org.kde.falkon.desktop"));
    }

    #[test]
    fn launch_dir_for_snap_and_flatpak_browsers() {
        // without such folders the user's runtime folder is used
        let d = launch_dir(Some("nonexistent_nonexistent.desktop"));
        assert!(d.ends_with("dispofold"));
        assert!(!d.to_string_lossy().contains("/snap/nonexistent"));
    }
}
