// Dispofold program window: starts the Java launcher, checks the identity of the backend it starts and shows
// the interface through the dispofold scheme, which forwards every request with the session secret.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod browser;
mod protocol;
mod rules;
mod selftest;

use protocol::FromLauncher;
use rules::OWN;
use serde_json::json;
use std::borrow::Cow;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tauri::http::{header, Request, Response, StatusCode};
use tauri::webview::DownloadEvent;
use tauri::{AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_dialog::{DialogExt, MessageDialogKind};
use tokio::sync::watch;

const SCHEME: &str = "dispofold";
const SESSION_HEADER: &str = "x-dispofold-session";
const CSP: &str = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; \
font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
const RESTARTING: &str = r#"{"code":"desktop.restarting","params":{},"error":"Dispofold startet neu."}"#;
const QUIT_GRACE: Duration = Duration::from_secs(30);

/// What the window can do right now.
#[derive(Clone, Debug, PartialEq)]
enum Phase {
    /// Launcher or backend (re)starting: API requests get 503 desktop.restarting, pages the wait page.
    Waiting,
    NeedPassword { first_start: bool, error: Option<String> },
    /// Backend proved its identity; requests go to 127.0.0.1:port with the session value of this start.
    Ready { port: u16, session: String },
    Failed { code: String },
}

struct Core {
    secret: [u8; 32],
    phase: watch::Sender<Phase>,
    stdin: Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    /// Forwards page requests; follows redirects only to the same host and port.
    proxy: reqwest::Client,
    /// Identity challenge: no redirects, no session header.
    direct: reqwest::Client,
    quitting: AtomicBool,
    warned_network: AtomicBool,
    downloads: PathBuf,
    selftest: Option<Arc<selftest::SelfTest>>,
    app: OnceLock<AppHandle>,
    /// Set when the interface runs in the user's browser (Linux default) instead of the window.
    browser: OnceLock<Arc<browser::Browser>>,
}

/// Where a request came from: the window's own scheme, or the loopback listener of the browser front end.
#[derive(Clone, Copy, PartialEq, Debug)]
enum Via {
    Scheme,
    Listener,
}

impl Core {
    fn new(secret: [u8; 32], downloads: PathBuf, selftest: Option<Arc<selftest::SelfTest>>) -> Core {
        Core {
            secret,
            phase: watch::channel(Phase::Waiting).0,
            stdin: Mutex::new(None),
            child: Mutex::new(None),
            proxy: reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::custom(|a| {
                    let prev = a.previous().last().expect("a previous URL");
                    if rules::redirect_allowed(prev, a.url(), a.previous().len()) {
                        a.follow()
                    } else {
                        a.stop()
                    }
                }))
                .build()
                .expect("http client"),
            direct: reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(15))
                .build()
                .expect("http client"),
            quitting: AtomicBool::new(false),
            warned_network: AtomicBool::new(false),
            downloads,
            selftest,
            app: OnceLock::new(),
            browser: OnceLock::new(),
        }
    }

    fn send(&self, line: &str) {
        if let Some(stdin) = self.stdin.lock().unwrap().as_mut() {
            if writeln!(stdin, "{line}").and_then(|_| stdin.flush()).is_err() {
                eprintln!("[window] launcher stdin closed");
            }
        }
    }

    fn set(&self, p: Phase) {
        self.phase.send_replace(p);
    }

    fn app(&self) -> &AppHandle {
        self.app.get().expect("app handle set in setup")
    }

    /// Window closed or quit requested: ask the launcher to stop, give it time, then end anyway.
    fn begin_quit(self: &Arc<Self>) {
        if self.quitting.swap(true, Ordering::SeqCst) {
            return;
        }
        if let Some(w) = self.app().get_webview_window("main") {
            let _ = w.hide();
        }
        self.send(&protocol::quit());
        let core = self.clone();
        std::thread::spawn(move || {
            std::thread::sleep(QUIT_GRACE);
            eprintln!("[window] launcher did not end in time, stopping it");
            core.kill_child();
            core.app().exit(0);
        });
    }

    /// Window: load the unlock page; browser: open the browser again (fresh launch code, the wait page then
    /// leads to the unlock page).
    fn show_unlock(&self) {
        if let Some(b) = self.browser.get() {
            if let Err(e) = b.open() {
                eprintln!("[window] {e}");
            }
        } else if let Some(w) = self.app().get_webview_window("main") {
            if let Ok(u) = format!("{}/__shell/unlock", OWN.base()).parse() {
                let _ = w.navigate(u);
            }
        }
    }

    fn kill_child(&self) {
        self.stdin.lock().unwrap().take();
        if let Some(mut c) = self.child.lock().unwrap().take() {
            if matches!(c.try_wait(), Ok(None)) {
                let _ = c.kill();
            }
            let _ = c.wait();
        }
    }

    /// A state the program cannot continue from: German message, then end.
    fn fatal(self: &Arc<Self>, code: &str) {
        self.fatal_with(code, &serde_json::Map::new());
    }

    fn fatal_with(self: &Arc<Self>, code: &str, params: &serde_json::Map<String, serde_json::Value>) {
        eprintln!("[window] fatal: {code}");
        self.set(Phase::Failed { code: code.into() });
        self.quitting.store(true, Ordering::SeqCst);
        if let Some(st) = &self.selftest {
            st.step("launcher", false, 0, code);
            self.kill_child();
            st.finish(self.app());
            return;
        }
        if let Some(w) = self.app().get_webview_window("main") {
            let _ = w.hide();
        }
        self.app()
            .dialog()
            .message(rules::message(code, params))
            .title("Dispofold")
            .kind(MessageDialogKind::Error)
            .blocking_show();
        self.send(&protocol::quit());
        self.kill_child();
        self.app().exit(1);
    }
}

/// Folder holding runtime/ and app/: next to the executable on Windows and in the ZIP, the resource folder of
/// the installed package elsewhere. `--program-dir` overrides it for development builds.
fn program_dir(app: &AppHandle, arg: Option<&PathBuf>) -> PathBuf {
    let dir = arg.cloned().unwrap_or_else(|| app.path().resource_dir().expect("resource folder"));
    // absolute, because the launcher runs with the program folder as its working folder; without the Windows
    // verbatim prefix \\?\ that the canonical exe path carries and that Java does not accept in paths
    plain_path(std::path::absolute(&dir).unwrap_or(dir))
}

fn plain_path(p: PathBuf) -> PathBuf {
    dunce::simplified(&p).to_path_buf()
}

fn java_path(program: &Path) -> PathBuf {
    if cfg!(windows) {
        program.join("runtime").join("bin").join("javaw.exe")
    } else {
        program.join("runtime").join("bin").join("java")
    }
}

/// Arguments of the launcher: shell mode, the program folder and the user's launcher options as given.
fn launcher_args(jar: &Path, program: &Path, args: &Args) -> Vec<std::ffi::OsString> {
    let mut v: Vec<std::ffi::OsString> = vec!["-jar".into(), jar.into(), "--shell".into(), "--program-dir".into(), program.into()];
    if let Some(d) = &args.data {
        v.extend(["--data".into(), d.into()]);
    }
    for (name, value) in &args.launcher_options {
        v.extend([name.into(), value.into()]);
    }
    v
}

fn start_launcher(core: &Arc<Core>, program: &Path, args: &Args) -> Result<(), String> {
    let java = java_path(program);
    let jar = program.join("app").join("dispofold-launcher.jar");
    if !java.is_file() || !jar.is_file() {
        return Err("desktop.program.incomplete".into());
    }
    let mut cmd = Command::new(&java);
    cmd.args(launcher_args(&jar, program, args));
    cmd.current_dir(program).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::inherit());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = cmd.spawn().map_err(|_| "desktop.program.incomplete".to_string())?;
    let mut stdin = child.stdin.take().expect("piped stdin");
    let stdout = child.stdout.take().expect("piped stdout");
    writeln!(stdin, "{}", protocol::hello(&core.secret)).map_err(|_| "desktop.launcher.ended".to_string())?;
    *core.stdin.lock().unwrap() = Some(stdin);
    *core.child.lock().unwrap() = Some(child);
    let core = core.clone();
    std::thread::spawn(move || read_launcher(core, stdout));
    Ok(())
}

/// Owns the launcher's stdout for its whole life and turns its lines into window states.
fn read_launcher(core: Arc<Core>, stdout: std::process::ChildStdout) {
    let mut exit_code: Option<i32> = None;
    let mut had_ready = false;
    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { break };
        let Some(msg) = protocol::parse_line(&line) else {
            eprintln!("[window] ignored launcher line");
            continue;
        };
        match msg {
            FromLauncher::NeedPassword { first_start, error } => match &core.selftest {
                Some(st) if error.is_none() => {
                    st.step("password requested", true, st.elapsed_ms(), if first_start { "first start" } else { "" });
                    core.send(&protocol::unlock(&st.password));
                }
                Some(_) => return core.fatal(error.as_deref().unwrap_or("desktop.password.wrong")),
                None => {
                    let was_running = matches!(*core.phase.borrow(), Phase::Ready { .. } | Phase::Waiting) && had_ready;
                    core.set(Phase::NeedPassword { first_start, error });
                    // rare: a later start asks again (e.g. a restored backup with an older password); an open
                    // interface page would only see 503, so bring the user to the unlock page
                    if was_running {
                        core.show_unlock();
                    }
                }
            },
            FromLauncher::Locked { error } => return core.fatal(error.as_deref().unwrap_or("desktop.lock.held")),
            FromLauncher::Starting { .. } => core.set(Phase::Waiting),
            FromLauncher::Ready { port, pid, nonce, proof } => {
                if !protocol::verify_ready(&core.secret, &nonce, port, pid, &proof) {
                    return core.fatal("desktop.identity.failed");
                }
                if !tauri::async_runtime::block_on(challenge(&core, port)) {
                    return core.fatal("desktop.identity.failed");
                }
                eprintln!("[window] backend verified on port {port}");
                if let Some(st) = &core.selftest {
                    st.step("backend verified", true, st.elapsed_ms(), "proof and challenge");
                }
                had_ready = true;
                // until here (since `starting`) nothing was forwarded; from now on with this start's session
                core.set(Phase::Ready { port, session: protocol::session_value(&core.secret, &nonce) });
            }
            FromLauncher::Warning { code, params } => {
                // the network warning once per start, every other warning as it comes
                let repeat = code == "desktop.dataFolder.network" && core.warned_network.swap(true, Ordering::SeqCst);
                if core.selftest.is_some() {
                    eprintln!("[window] warning {code}");
                } else if !repeat {
                    core.app()
                        .dialog()
                        .message(rules::message(&code, &params))
                        .title("Dispofold")
                        .kind(MessageDialogKind::Warning)
                        .show(|_| {});
                }
            }
            FromLauncher::Failed { code, params } => return core.fatal_with(&code, &params),
            FromLauncher::Exit { code } => exit_code = Some(code),
        }
    }
    // launcher ended
    let code = exit_code.unwrap_or(-1);
    eprintln!("[window] launcher ended with {code}");
    if let Some(st) = &core.selftest {
        st.step("launcher exit", code == 0, st.elapsed_ms(), &format!("code {code}"));
        core.kill_child();
        st.finish(core.app());
        return;
    }
    if code == 0 || core.quitting.load(Ordering::SeqCst) {
        core.kill_child();
        core.app().exit(0);
    } else {
        core.fatal("desktop.launcher.ended");
    }
}

/// The listener on the port must hold the secret: a fresh challenge must come back signed.
async fn challenge(core: &Core, port: u16) -> bool {
    let c = protocol::random_b64::<16>();
    let r = core
        .direct
        .post(format!("http://127.0.0.1:{port}/api/desktop/challenge"))
        .header(header::CONTENT_TYPE, "application/json")
        .body(json!({ "challenge": c }).to_string())
        .send()
        .await;
    let Ok(r) = r else { return false };
    if !r.status().is_success() {
        return false;
    }
    let Ok(body) = r.bytes().await else { return false };
    let v: serde_json::Value = serde_json::from_slice(&body).unwrap_or_default();
    protocol::verify_challenge(&core.secret, &c, port, v["response"].as_str().unwrap_or(""))
}

fn respond(status: StatusCode, content_type: &str, body: impl Into<Vec<u8>>) -> Response<Vec<u8>> {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "no-store")
        .header("content-security-policy", CSP)
        .body(body.into())
        .unwrap()
}

fn shell_asset(app: &AppHandle, name: &str) -> Response<Vec<u8>> {
    match name {
        "fonts/plex-400.woff2" => {
            return respond(StatusCode::OK, "font/woff2", &include_bytes!("../../frontend/public/fonts/ibm-plex-sans-latin-400-normal.woff2")[..])
        }
        "icon.svg" => return respond(StatusCode::OK, "image/svg+xml", &include_bytes!("../../branding/icon.svg")[..]),
        "fonts/plex-600.woff2" => {
            return respond(StatusCode::OK, "font/woff2", &include_bytes!("../../frontend/public/fonts/ibm-plex-sans-latin-600-normal.woff2")[..])
        }
        _ => {}
    }
    let file = match name {
        "unlock" => "unlock.html".to_string(),
        "wait" => "wait.html".to_string(),
        "running" => "running.html".to_string(),
        "launch" => "launch.html".to_string(),
        "relogin" => "relogin.html".to_string(),
        n if n.ends_with(".js") || n.ends_with(".css") => n.to_string(),
        _ => return respond(StatusCode::NOT_FOUND, "text/plain", "nicht gefunden"),
    };
    match app.asset_resolver().get(file) {
        Some(a) => respond(StatusCode::OK, &a.mime_type, a.bytes),
        None => respond(StatusCode::NOT_FOUND, "text/plain", "nicht gefunden"),
    }
}

fn state_json(p: &Phase) -> String {
    match p {
        Phase::Waiting => json!({ "phase": "waiting" }),
        Phase::NeedPassword { first_start, error } => json!({ "phase": "needPassword", "firstStart": first_start, "error": error }),
        Phase::Ready { .. } => json!({ "phase": "ready" }),
        Phase::Failed { code } => json!({ "phase": "failed", "code": code }),
    }
    .to_string()
}

/// Requests below /__shell/ are answered here and never reach the backend.
async fn shell_request(core: &Arc<Core>, via: Via, method: &tauri::http::Method, path: &str, body: &[u8]) -> Response<Vec<u8>> {
    let name = path.trim_start_matches("/__shell/");
    let name = name.split('?').next().unwrap_or("");
    match (method.as_str(), name) {
        ("GET", "state") => respond(StatusCode::OK, "application/json", state_json(&core.phase.borrow())),
        ("GET", "mode") => {
            let front = if core.browser.get().is_some() { "browser" } else { "window" };
            respond(StatusCode::OK, "application/json", json!({ "frontEnd": front }).to_string())
        }
        // buttons of the small "Dispofold läuft" window; never reachable from the browser
        ("POST", "open") if via == Via::Scheme => match core.browser.get() {
            Some(b) => {
                if let Err(e) = b.open() {
                    eprintln!("[window] {e}");
                }
                respond(StatusCode::OK, "application/json", "{}")
            }
            None => respond(StatusCode::NOT_FOUND, "text/plain", ""),
        },
        ("POST", "quit") if via == Via::Scheme => {
            core.begin_quit();
            respond(StatusCode::OK, "application/json", "{}")
        }
        ("POST", "unlock") => {
            let v: serde_json::Value = serde_json::from_slice(body).unwrap_or_default();
            let Some(password) = v["password"].as_str() else {
                return respond(StatusCode::BAD_REQUEST, "application/json", "{}");
            };
            let mut rx = core.phase.subscribe();
            rx.mark_unchanged();
            // only one answer per password request: check and switch to Waiting in one step
            let claimed = core.selftest.is_none()
                && core.phase.send_if_modified(|p| {
                    let asked = matches!(p, Phase::NeedPassword { .. });
                    if asked {
                        *p = Phase::Waiting;
                    }
                    asked
                });
            if !claimed {
                return respond(StatusCode::CONFLICT, "application/json", state_json(&core.phase.borrow()));
            }
            core.send(&protocol::unlock(password));
            // keep the request open until the launcher answers: ready, a new password request, or failure
            loop {
                let wait = tokio::time::timeout(Duration::from_secs(600), rx.changed()).await;
                if !matches!(wait, Ok(Ok(()))) {
                    break;
                }
                if !matches!(*rx.borrow(), Phase::Waiting) {
                    break;
                }
            }
            let p = rx.borrow().clone();
            respond(StatusCode::OK, "application/json", state_json(&p))
        }
        ("GET", "selftest/begin") | ("POST", "selftest/report") | ("POST", "selftest/abort") if via == Via::Scheme => match &core.selftest {
            Some(st) => st.handle(core.app(), name, body),
            None => respond(StatusCode::NOT_FOUND, "text/plain", "nicht gefunden"),
        },
        ("GET", n) => shell_asset(core.app(), n),
        _ => respond(StatusCode::METHOD_NOT_ALLOWED, "text/plain", ""),
    }
}

async fn handle(core: Arc<Core>, via: Via, req: Request<Vec<u8>>) -> Response<Vec<u8>> {
    let (parts, body) = req.into_parts();
    let path = parts.uri.path_and_query().map(|p| p.as_str().to_string()).unwrap_or_else(|| "/".into());
    if path.starts_with("/__shell/") {
        return shell_request(&core, via, &parts.method, &path, &body).await;
    }
    let phase = core.phase.borrow().clone();
    let Phase::Ready { port, session } = phase else {
        if path.starts_with("/api/") {
            return respond(StatusCode::SERVICE_UNAVAILABLE, "application/json", RESTARTING);
        }
        // page load while the backend is not ready: the wait page reloads once it is
        return shell_asset(core.app(), "wait");
    };
    let mut rb = core.proxy.request(parts.method.clone(), format!("http://127.0.0.1:{port}{path}"));
    for (k, v) in parts.headers.iter() {
        if rules::forward_request_header(k.as_str()) {
            rb = rb.header(k, v);
        }
    }
    let rb = rb.header(SESSION_HEADER, &session).body(body);
    let resp = match rb.send().await {
        Ok(r) => r,
        // backend gone between ready and now (restart): same answer as during a restart
        Err(_) if path.starts_with("/api/") => return respond(StatusCode::SERVICE_UNAVAILABLE, "application/json", RESTARTING),
        Err(_) => return shell_asset(core.app(), "wait"),
    };
    let mut out = Response::builder().status(resp.status());
    for (k, v) in resp.headers().iter() {
        if rules::forward_response_header(k.as_str()) {
            out = out.header(k, v);
        }
    }
    out = out.header("content-security-policy", CSP);
    match resp.bytes().await {
        Ok(b) => out.body(b.to_vec()).unwrap(),
        Err(_) => respond(StatusCode::BAD_GATEWAY, "application/json", r#"{"code":"desktop.unreachable","params":{}}"#),
    }
}

/// Largest request body through the browser listener: the update file (1 GiB) plus some room.
const MAX_BODY: usize = (1 << 30) + (1 << 20);

const DESKTOP_LOGIN: &str =
    r#"{"code":"security.desktopLogin","params":{},"error":"Bitte Dispofold neu starten."}"#;

/// Browser front end: host, launch code, cookie and origin checks in front of the same handler as the window.
async fn browser_gate(core: Arc<Core>, b: Arc<browser::Browser>, mut req: Request<Vec<u8>>) -> Response<Vec<u8>> {
    let header = |r: &Request<Vec<u8>>, n: header::HeaderName| r.headers().get(n).and_then(|v| v.to_str().ok()).map(String::from);
    if !b.host_ok(header(&req, header::HOST).as_deref()) {
        return respond(StatusCode::MISDIRECTED_REQUEST, "text/plain", "");
    }
    let path = req.uri().path().to_string();
    let cookie_ok = b.cookie_ok(header(&req, header::COOKIE).as_deref());
    if path == "/__shell/launch" && req.method() == tauri::http::Method::POST {
        let v: serde_json::Value = serde_json::from_slice(req.body()).unwrap_or_default();
        let fresh = b.take_code(v["code"].as_str().unwrap_or(""));
        if !fresh && !cookie_ok {
            return respond(StatusCode::FORBIDDEN, "application/json", DESKTOP_LOGIN);
        }
        let mut r = respond(StatusCode::OK, "application/json", "{}");
        if fresh {
            r.headers_mut().insert(header::SET_COOKIE, b.set_cookie().parse().unwrap());
        }
        return r;
    }
    // the launch page and its look work without the cookie; nothing else does
    let open = matches!(
        path.as_str(),
        "/__shell/launch" | "/__shell/launch.js" | "/__shell/shell.css" | "/__shell/icon.svg" | "/__shell/fonts/plex-400.woff2"
            | "/__shell/fonts/plex-600.woff2"
    );
    if !open && !cookie_ok {
        if path.starts_with("/api/") {
            return respond(StatusCode::UNAUTHORIZED, "application/json", DESKTOP_LOGIN);
        }
        let mut r = shell_asset(core.app(), "relogin");
        *r.status_mut() = StatusCode::UNAUTHORIZED;
        return r;
    }
    if !b.origin_ok(header(&req, header::ORIGIN).as_deref()) {
        return respond(StatusCode::FORBIDDEN, "application/json", DESKTOP_LOGIN);
    }
    // the backend accepts only the window origins; the browser origin was checked here
    req.headers_mut().remove(header::ORIGIN);
    handle(core, Via::Listener, req).await
}

/// Loopback HTTP/1.1 listener of the browser front end.
async fn serve_browser(core: Arc<Core>, b: Arc<browser::Browser>, listener: std::net::TcpListener) {
    use http_body_util::BodyExt;
    listener.set_nonblocking(true).expect("non-blocking listener");
    let listener = tokio::net::TcpListener::from_std(listener).expect("listener");
    loop {
        let stream = match listener.accept().await {
            Ok((stream, _)) => stream,
            Err(_) => {
                // e.g. too many open files: do not spin
                tokio::time::sleep(Duration::from_millis(200)).await;
                continue;
            }
        };
        let (core, b) = (core.clone(), b.clone());
        tauri::async_runtime::spawn(async move {
            let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
                let (core, b) = (core.clone(), b.clone());
                async move {
                    let (parts, body) = req.into_parts();
                    let host = parts.headers.get(header::HOST).and_then(|v| v.to_str().ok());
                    let out = if !b.host_ok(host) {
                        // refused before the body is read
                        respond(StatusCode::MISDIRECTED_REQUEST, "text/plain", "")
                    } else {
                        match http_body_util::Limited::new(body, MAX_BODY).collect().await {
                            Ok(c) => browser_gate(core, b, Request::from_parts(parts, c.to_bytes().to_vec())).await,
                            Err(_) => respond(StatusCode::PAYLOAD_TOO_LARGE, "text/plain", ""),
                        }
                    };
                    let (p, out) = out.into_parts();
                    Ok::<_, std::convert::Infallible>(hyper::Response::from_parts(p, http_body_util::Full::new(bytes::Bytes::from(out))))
                }
            });
            let io = hyper_util::rt::TokioIo::new(stream);
            let _ = hyper::server::conn::http1::Builder::new().serve_connection(io, svc).await;
        });
    }
}

/// Command line: `[--window] [--data <folder>] [--keep-backups <n>] [--ready-timeout <s>] [--selftest <result.json> --data <empty folder>] [--program-dir <folder>]`.
#[derive(Default)]
struct Args {
    selftest: Option<PathBuf>,
    data: Option<PathBuf>,
    program_dir: Option<PathBuf>,
    window: bool,
    /// Launcher options passed on unchanged (name, value).
    launcher_options: Vec<(String, String)>,
}

fn parse_args(mut it: impl Iterator<Item = String>) -> Result<Args, String> {
    let mut a = Args::default();
    while let Some(arg) = it.next() {
        let mut value = || it.next().map(PathBuf::from).ok_or(format!("{arg} needs a value"));
        match arg.as_str() {
            "--selftest" => a.selftest = Some(value()?),
            "--data" => a.data = Some(value()?),
            "--program-dir" => a.program_dir = Some(value()?),
            "--window" => a.window = true,
            "--keep-backups" | "--ready-timeout" => {
                let v = it.next().ok_or(format!("{arg} needs a value"))?;
                if v.is_empty() || !v.bytes().all(|b| b.is_ascii_digit()) {
                    return Err(format!("{arg} needs a number"));
                }
                a.launcher_options.push((arg.clone(), v));
            }
            _ => return Err(format!("unknown argument {arg}")),
        }
    }
    if a.selftest.is_some() && a.data.is_none() {
        return Err("--selftest needs --data".into());
    }
    Ok(a)
}

/// Environment variables that open WebKit's remote inspector (an unauthenticated loopback port).
fn inspector_vars(keys: impl Iterator<Item = std::ffi::OsString>) -> Vec<std::ffi::OsString> {
    keys.filter(|k| k.to_string_lossy().to_ascii_uppercase().starts_with("WEBKIT_INSPECTOR")).collect()
}

fn main() {
    if !cfg!(debug_assertions) {
        // before WebKit starts; the launcher and the backend inherit the cleaned environment
        for k in inspector_vars(std::env::vars_os().map(|(k, _)| k)) {
            std::env::remove_var(k);
        }
    }
    let args = match parse_args(std::env::args().skip(1)) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("dispofold: {e}\nusage: dispofold [--window] [--data <folder>] [--keep-backups <n>] [--ready-timeout <s>] [--selftest <result.json> --data <empty folder>]");
            std::process::exit(2);
        }
    };
    let selftest = match (&args.selftest, &args.data) {
        (Some(result), Some(data)) => match selftest::SelfTest::prepare(result, data) {
            Ok(st) => Some(Arc::new(st)),
            Err(e) => {
                eprintln!("dispofold: {e}");
                std::process::exit(2);
            }
        },
        _ => None,
    };
    let downloads = selftest
        .as_ref()
        .map(|s| s.downloads.clone())
        .or_else(dirs::download_dir)
        .or_else(dirs::home_dir)
        .unwrap_or_else(|| PathBuf::from("."));
    let core = Arc::new(Core::new(protocol::random_bytes::<32>(), downloads, selftest));
    // Linux: the browser is the default front end (WebKitGTK is not fluent without a GPU); the window stays
    // available with --window and is always used by the self-test. Windows and macOS always use the window.
    let use_browser = cfg!(target_os = "linux") && !args.window && core.selftest.is_none();
    if cfg!(target_os = "linux") && !use_browser && std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        // the fastest WebKitGTK renderer for software rendering (A7 measurement)
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    let mut builder = tauri::Builder::default();
    if core.selftest.is_none() {
        // a second start brings the open window to the front instead
        let c_single = core.clone();
        builder = builder.plugin(tauri_plugin_single_instance::init(move |app, _args, _cwd| {
            if let Some(b) = c_single.browser.get() {
                // browser front end: a fresh launch code and the browser again
                if let Err(e) = b.open() {
                    eprintln!("[window] {e}");
                }
                return;
            }
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.unminimize();
                let _ = w.show();
                let _ = w.set_focus();
            }
        }));
    }
    let c_scheme = core.clone();
    let c_setup = core.clone();
    let c_run = core.clone();
    let app = builder
        .plugin(tauri_plugin_dialog::init())
        .register_asynchronous_uri_scheme_protocol(SCHEME, move |_ctx, req, responder| {
            let core = c_scheme.clone();
            tauri::async_runtime::spawn(async move {
                let (p, b) = handle(core, Via::Scheme, req).await.into_parts();
                responder.respond(Response::from_parts(p, Cow::<'static, [u8]>::Owned(b)));
            });
        })
        .setup(move |app| {
            let core = c_setup;
            let _ = core.app.set(app.handle().clone());
            let program = program_dir(app.handle(), args.program_dir.as_ref());
            if use_browser {
                let state = dirs::state_dir().or_else(dirs::data_local_dir).unwrap_or_else(|| PathBuf::from("."));
                let file = browser::identity_file(&state.join("dispofold"), &program, args.data.as_deref());
                let stored = browser::load_identity(&file);
                let listeners = browser::bind(stored.as_ref().map(|i| i.port))?;
                let id = browser::Identity {
                    name: stored.map(|i| i.name).unwrap_or_else(browser::new_name),
                    port: listeners.v4.local_addr()?.port(),
                };
                browser::save_identity(&file, &id);
                let desktop_id = browser::default_desktop_id();
                let b = Arc::new(browser::Browser::new(&id.name, id.port, browser::launch_dir(desktop_id.as_deref())));
                let _ = core.browser.set(b.clone());
                tauri::async_runtime::spawn(serve_browser(core.clone(), b.clone(), listeners.v4));
                if let Some(v6) = listeners.v6 {
                    tauri::async_runtime::spawn(serve_browser(core.clone(), b, v6));
                }
            }
            let start = if use_browser { format!("{}/__shell/running", OWN.base()) } else { format!("{}/", OWN.base()) };
            let mut b = WebviewWindowBuilder::new(app, "main", WebviewUrl::CustomProtocol(start.parse().unwrap()))
                .title("Dispofold");
            b = if use_browser {
                b.inner_size(420.0, 260.0).resizable(false).center()
            } else {
                b.inner_size(1400.0, 900.0).min_inner_size(960.0, 600.0).center()
            };
            b = b
                .on_navigation(|u| {
                    let ok = OWN.navigation_allowed(u);
                    if !ok {
                        eprintln!("[window] navigation blocked");
                    }
                    ok
                });
            let dl = core.clone();
            b = b.on_download(move |w, ev| {
                match ev {
                    DownloadEvent::Requested { destination, .. } => {
                        let name = rules::download_name(Some(destination.as_path()));
                        let _ = std::fs::create_dir_all(&dl.downloads);
                        *destination = rules::unique_path(&dl.downloads, &name, |p| p.exists());
                    }
                    DownloadEvent::Finished { path, success, .. } => {
                        if let (true, Some(p)) = (success, path) {
                            let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                            let folder = dl.downloads.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                            if let Some(st) = &dl.selftest {
                                st.downloaded(p.clone());
                            }
                            let detail = json!({ "name": name, "folder": folder });
                            let _ = w.eval(format!(
                                "window.dispatchEvent(new CustomEvent('dispofold-download', {{ detail: {detail} }}))"
                            ));
                        }
                    }
                    _ => {}
                }
                true
            });
            if let Some(st) = &core.selftest {
                b = b.initialization_script(selftest::PAGE_SCRIPT).data_directory(st.webview_dir.clone());
            }
            b.build()?;
            if let Some(st) = &core.selftest {
                st.watchdog(app.handle().clone());
            }
            if let Err(code) = start_launcher(&core, &program, &args) {
                let core = core.clone();
                std::thread::spawn(move || core.fatal(&code));
            } else if let Some(b) = core.browser.get() {
                if let Err(e) = b.open() {
                    eprintln!("[window] {e}");
                }
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("window start");
    app.run(move |_app, ev| match ev {
        RunEvent::WindowEvent { label, event: WindowEvent::CloseRequested { api, .. }, .. } if label == "main" => {
            api.prevent_close();
            c_run.begin_quit();
        }
        RunEvent::ExitRequested { code: None, api, .. } => {
            api.prevent_exit();
            c_run.begin_quit();
        }
        RunEvent::Exit => c_run.kill_child(),
        _ => {}
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(v: &[&str]) -> Result<Args, String> {
        parse_args(v.iter().map(|s| s.to_string()))
    }

    #[test]
    fn selftest_needs_a_data_folder() {
        assert!(args(&[]).is_ok());
        let a = args(&["--selftest", "r.json", "--data", "d"]).unwrap();
        assert_eq!(a.selftest, Some(PathBuf::from("r.json")));
        assert_eq!(a.data, Some(PathBuf::from("d")));
        assert!(args(&["--selftest", "r.json"]).is_err());
        assert!(args(&["--data", "d"]).is_ok());
        assert!(args(&["--window"]).unwrap().window);
        assert!(args(&["--selftest"]).is_err());
        assert!(args(&["--other"]).is_err());
    }

    /// Minimal HTTP/1.1 backend on a free loopback port: answers each request with the request head it saw as
    /// JSON, plus a Set-Cookie; /redirect-same and /redirect-away answer 302.
    fn echo_backend() -> u16 {
        use std::io::Read;
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for s in l.incoming() {
                let mut s = s.unwrap();
                let mut buf = vec![0u8; 65536];
                let n = s.read(&mut buf).unwrap();
                let head = String::from_utf8_lossy(&buf[..n]).to_string();
                let first = head.lines().next().unwrap_or("").to_string();
                let (status, extra) = if first.contains("/redirect-same") {
                    ("302 Found", "Location: /landed\r\n".to_string())
                } else if first.contains("/redirect-away") {
                    ("302 Found", "Location: http://127.0.0.1:9/away\r\n".to_string())
                } else {
                    ("200 OK", String::new())
                };
                let body = serde_json::json!({ "head": head }).to_string();
                let _ = write!(
                    s,
                    "HTTP/1.1 {status}\r\n{extra}Content-Type: application/json\r\nSet-Cookie: k=v\r\nContent-Security-Policy: none\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
            }
        });
        port
    }

    fn page_request(method: &str, path: &str, headers: &[(&str, &str)]) -> Request<Vec<u8>> {
        let mut b = Request::builder().method(method).uri(format!("dispofold://localhost{path}"));
        for (k, v) in headers {
            b = b.header(*k, *v);
        }
        b.body(Vec::new()).unwrap()
    }

    fn seen_head(r: Response<Vec<u8>>) -> String {
        let v: serde_json::Value = serde_json::from_slice(r.body()).unwrap();
        v["head"].as_str().unwrap().to_lowercase()
    }

    #[test]
    fn proxy_adds_the_session_and_strips_cookies_both_ways() {
        let core = Arc::new(Core::new([9u8; 32], PathBuf::from("."), None));
        let port = echo_backend();
        core.set(Phase::Ready { port, session: "session-of-this-start".into() });
        let r = tauri::async_runtime::block_on(handle(
            core.clone(),
            Via::Scheme,
            page_request("GET", "/api/me?x=1", &[("X-Dispofold-Session", "forged"), ("Cookie", "a=b"), ("Origin", "dispofold://localhost")]),
        ));
        assert_eq!(r.status(), 200);
        assert!(r.headers().get("set-cookie").is_none());
        assert_eq!(r.headers().get("content-security-policy").unwrap(), CSP);
        let head = seen_head(r);
        assert!(head.starts_with("get /api/me?x=1 http/1.1"), "{head}");
        assert!(head.contains("x-dispofold-session: session-of-this-start"));
        assert!(!head.contains("forged"));
        assert!(!head.contains("cookie:"));
        assert!(head.contains(&format!("host: 127.0.0.1:{port}")));
        assert!(head.contains("origin: dispofold://localhost"));
    }

    #[test]
    fn proxy_follows_redirects_only_on_the_same_port() {
        let core = Arc::new(Core::new([9u8; 32], PathBuf::from("."), None));
        let port = echo_backend();
        core.set(Phase::Ready { port, session: "session-of-this-start".into() });
        let same = tauri::async_runtime::block_on(handle(core.clone(), Via::Scheme, page_request("GET", "/redirect-same", &[])));
        assert_eq!(same.status(), 200);
        assert!(seen_head(same).starts_with("get /landed "));
        let away = tauri::async_runtime::block_on(handle(core.clone(), Via::Scheme, page_request("GET", "/redirect-away", &[])));
        assert_eq!(away.status(), 302);
    }

    #[test]
    fn api_requests_get_503_restarting_while_the_backend_is_not_ready() {
        let core = Arc::new(Core::new([9u8; 32], PathBuf::from("."), None));
        for p in [Phase::Waiting, Phase::NeedPassword { first_start: false, error: None }] {
            core.set(p);
            let r = tauri::async_runtime::block_on(handle(core.clone(), Via::Scheme, page_request("POST", "/api/plans/2027-01/solve", &[])));
            assert_eq!(r.status(), 503);
            let v: serde_json::Value = serde_json::from_slice(r.body()).unwrap();
            assert_eq!(v["code"], "desktop.restarting");
        }
    }

    fn browser_request(method: &str, path: &str, headers: &[(&str, &str)], body: &str) -> Request<Vec<u8>> {
        let mut b = Request::builder().method(method).uri(path);
        for (k, v) in headers {
            b = b.header(*k, *v);
        }
        b.body(body.as_bytes().to_vec()).unwrap()
    }

    #[test]
    fn browser_listener_checks_host_code_cookie_and_origin() {
        let core = Arc::new(Core::new([9u8; 32], PathBuf::from("."), None));
        let port = echo_backend();
        core.set(Phase::Ready { port, session: "session-of-this-start".into() });
        let b = Arc::new(browser::Browser::new("abcdefghijklmnop", 41234, PathBuf::from(".")));
        let host = "abcdefghijklmnop.localhost:41234";
        let run = |r| tauri::async_runtime::block_on(browser_gate(core.clone(), b.clone(), r));

        // other host names: 421
        assert_eq!(run(browser_request("GET", "/api/me", &[("Host", "127.0.0.1:41234")], "")).status(), 421);
        // no cookie: 401 with the desktop login code
        let r = run(browser_request("GET", "/api/me", &[("Host", host)], ""));
        assert_eq!(r.status(), 401);
        assert!(String::from_utf8_lossy(r.body()).contains("security.desktopLogin"));
        // launch code: once, gives the cookie
        let code = b.mint_code();
        let body = format!(r#"{{"code":"{code}"}}"#);
        let r = run(browser_request("POST", "/__shell/launch", &[("Host", host)], &body));
        assert_eq!(r.status(), 200);
        let cookie = r.headers().get("set-cookie").unwrap().to_str().unwrap().split(';').next().unwrap().to_string();
        assert_eq!(run(browser_request("POST", "/__shell/launch", &[("Host", host)], &body)).status(), 403);
        // with the cookie: forwarded with the session header, without the cookie and the browser origin
        let own = "http://abcdefghijklmnop.localhost:41234";
        let r = run(browser_request("POST", "/api/x", &[("Host", host), ("Cookie", &cookie), ("Origin", own)], "{}"));
        assert_eq!(r.status(), 200);
        let head = seen_head(r);
        assert!(head.contains("x-dispofold-session:"));
        assert!(!head.contains("origin:") && !head.contains("cookie:") && !head.contains("dispofold_s"));
        assert!(head.contains(&format!("host: 127.0.0.1:{port}")));
        // foreign origin: 403 even with the cookie
        let r = run(browser_request("POST", "/api/x", &[("Host", host), ("Cookie", &cookie), ("Origin", "http://evil.example")], "{}"));
        assert_eq!(r.status(), 403);
        // window-only endpoints are not reachable through the listener
        let r = run(browser_request("POST", "/__shell/quit", &[("Host", host), ("Cookie", &cookie)], ""));
        assert_eq!(r.status(), 405);
        assert!(!core.quitting.load(Ordering::SeqCst));
    }

    #[cfg(windows)]
    #[test]
    fn program_folder_without_the_verbatim_prefix() {
        assert_eq!(plain_path(PathBuf::from(r"\\?\C:\Users\x\Dispofold")), PathBuf::from(r"C:\Users\x\Dispofold"));
    }

    #[test]
    fn launcher_gets_the_program_folder_data_folder_and_user_options() {
        let a = args(&["--keep-backups", "5", "--data", "/d", "--ready-timeout", "90", "--window"]).unwrap();
        let v: Vec<String> = launcher_args(Path::new("/p/app/l.jar"), Path::new("/p"), &a)
            .into_iter()
            .map(|s| s.to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            v,
            ["-jar", "/p/app/l.jar", "--shell", "--program-dir", "/p", "--data", "/d", "--keep-backups", "5", "--ready-timeout", "90"]
        );
        let plain: Vec<String> = launcher_args(Path::new("/p/app/l.jar"), Path::new("/p"), &args(&[]).unwrap())
            .into_iter()
            .map(|s| s.to_string_lossy().into_owned())
            .collect();
        assert_eq!(plain, ["-jar", "/p/app/l.jar", "--shell", "--program-dir", "/p"]);
        // values are checked; --change-password stays a launcher-only call
        assert!(args(&["--keep-backups", "x"]).is_err());
        assert!(args(&["--keep-backups"]).is_err());
        assert!(args(&["--change-password"]).is_err());
    }

    #[test]
    fn requests_after_starting_are_not_forwarded_to_the_old_port() {
        let core = Arc::new(Core::new([9u8; 32], PathBuf::from("."), None));
        let port = echo_backend();
        core.set(Phase::Ready { port, session: "s1".into() });
        let ok = tauri::async_runtime::block_on(handle(core.clone(), Via::Scheme, page_request("GET", "/api/me", &[])));
        assert_eq!(ok.status(), 200);
        // the launcher reports `starting`: the old port is not used any more, the page gets 503
        core.set(Phase::Waiting);
        let r = tauri::async_runtime::block_on(handle(core.clone(), Via::Scheme, page_request("GET", "/api/me", &[])));
        assert_eq!(r.status(), 503);
    }

    #[test]
    fn webkit_inspector_variables_are_found_for_removal() {
        let keys = ["WEBKIT_INSPECTOR_SERVER", "WEBKIT_INSPECTOR_HTTP_SERVER", "WEBKIT_INSPECTOR_FOO", "webkit_inspector_server",
            "WEBKIT_DISABLE_DMABUF_RENDERER", "PATH", "HOME"];
        let found = inspector_vars(keys.iter().map(std::ffi::OsString::from));
        assert_eq!(found, ["WEBKIT_INSPECTOR_SERVER", "WEBKIT_INSPECTOR_HTTP_SERVER", "WEBKIT_INSPECTOR_FOO", "webkit_inspector_server"]);
    }

    #[test]
    fn page_states_for_the_shell_pages() {
        assert_eq!(state_json(&Phase::Waiting), r#"{"phase":"waiting"}"#);
        let v: serde_json::Value =
            serde_json::from_str(&state_json(&Phase::NeedPassword { first_start: true, error: Some("desktop.password.short".into()) }))
                .unwrap();
        assert_eq!(v["phase"], "needPassword");
        assert_eq!(v["firstStart"], true);
        assert_eq!(v["error"], "desktop.password.short");
        // the port never reaches the page
        assert_eq!(state_json(&Phase::Ready { port: 43210, session: "s".into() }), r#"{"phase":"ready"}"#);
    }
}
