//! `--selftest <result.json> --data <empty folder>`: runs the real chain (launcher, backend, window) and drives the
//! page through the scheme: open the app, load demo data, plan 2027-01, export JSON through the download path,
//! quit through the interface. Writes `{ok, steps, plan}` and ends with 0 only if every step passed.
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::http::{Response, StatusCode};
use tauri::AppHandle;

/// Steps that must all be present and passed.
const REQUIRED: [&str; 8] =
    ["password requested", "backend verified", "open app", "demo data", "solve", "export", "quit", "launcher exit"];
const TIMEOUT: Duration = Duration::from_secs(20 * 60);

/// Injected only in self-test mode; runs on every page of the own origin and drives the app once.
pub const PAGE_SCRIPT: &str = include_str!("selftest.js");

#[derive(Serialize, Deserialize, Clone)]
pub struct Step {
    pub name: String,
    pub ms: u64,
    pub ok: bool,
    pub detail: String,
}

pub struct SelfTest {
    result: PathBuf,
    pub downloads: PathBuf,
    pub webview_dir: PathBuf,
    pub password: String,
    started: Instant,
    steps: Mutex<Vec<Step>>,
    plan: Mutex<Option<Value>>,
    download: Mutex<Option<PathBuf>>,
    begun: AtomicBool,
    finished: AtomicBool,
}

impl SelfTest {
    /// Refuses a data folder that exists and is not empty; work files go next to the result file.
    pub fn prepare(result: &Path, data: &Path) -> Result<SelfTest, String> {
        if data.exists() && std::fs::read_dir(data).map(|mut d| d.next().is_some()).unwrap_or(true) {
            return Err("--data must be a new or empty folder".into());
        }
        let parent = result.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
        std::fs::create_dir_all(parent).map_err(|_| "cannot create the result folder".to_string())?;
        let work = parent.join("selftest-work");
        if work.exists() {
            return Err("selftest-work next to the result file exists already".into());
        }
        Ok(SelfTest {
            result: result.to_path_buf(),
            downloads: work.join("downloads"),
            webview_dir: work.join("webview"),
            password: crate::protocol::random_b64::<18>(),
            started: Instant::now(),
            steps: Mutex::new(Vec::new()),
            plan: Mutex::new(None),
            download: Mutex::new(None),
            begun: AtomicBool::new(false),
            finished: AtomicBool::new(false),
        })
    }

    pub fn elapsed_ms(&self) -> u64 {
        self.started.elapsed().as_millis() as u64
    }

    pub fn step(&self, name: &str, ok: bool, ms: u64, detail: &str) {
        eprintln!("[selftest] {} {name} ({ms} ms) {detail}", if ok { "PASS" } else { "FAIL" });
        self.steps.lock().unwrap().push(Step { name: name.into(), ms, ok, detail: detail.into() });
    }

    pub fn downloaded(&self, p: PathBuf) {
        *self.download.lock().unwrap() = Some(p);
    }

    /// Ends a run that hangs.
    pub fn watchdog(self: &std::sync::Arc<Self>, app: AppHandle) {
        let st = self.clone();
        std::thread::spawn(move || {
            std::thread::sleep(TIMEOUT);
            st.step("timeout", false, st.elapsed_ms(), "self-test did not finish in time");
            st.finish(&app);
        });
    }

    /// Requests of the page script below /__shell/selftest/.
    pub fn handle(&self, app: &AppHandle, name: &str, body: &[u8]) -> Response<Vec<u8>> {
        let ok = |b: String| Response::builder().status(StatusCode::OK).header("content-type", "application/json").body(b.into_bytes()).unwrap();
        match name {
            "selftest/begin" => ok(json!({ "run": !self.begun.swap(true, Ordering::SeqCst) }).to_string()),
            "selftest/report" => {
                let v: Value = serde_json::from_slice(body).unwrap_or_default();
                let name = v["name"].as_str().unwrap_or("?");
                let mut passed = v["ok"].as_bool().unwrap_or(false);
                let mut detail = v["detail"].as_str().unwrap_or("").to_string();
                if name == "export" && passed {
                    // the file must have arrived in the download folder and be the exported plan
                    passed = self.check_download(&mut detail);
                }
                if name == "solve" && v["plan"].is_object() {
                    *self.plan.lock().unwrap() = Some(v["plan"].clone());
                }
                self.step(name, passed, v["ms"].as_u64().unwrap_or(0), &detail);
                ok("{}".into())
            }
            _ => {
                // abort: a page step failed, nothing more will come
                self.finish(app);
                ok("{}".into())
            }
        }
    }

    fn check_download(&self, detail: &mut String) -> bool {
        let Some(p) = self.download.lock().unwrap().clone() else {
            *detail = "no file in the download folder".into();
            return false;
        };
        let parsed: Option<Value> = std::fs::read(&p).ok().and_then(|b| serde_json::from_slice(&b).ok());
        match parsed {
            Some(v) if v.is_object() => {
                *detail = format!("{} saved", p.file_name().unwrap_or_default().to_string_lossy());
                true
            }
            _ => {
                *detail = "downloaded file is no JSON object".into();
                false
            }
        }
    }

    /// Writes the result once and ends the program: 0 only if every required step passed.
    pub fn finish(&self, app: &AppHandle) {
        if self.finished.swap(true, Ordering::SeqCst) {
            return;
        }
        let steps = self.steps.lock().unwrap().clone();
        let ok = verdict(&steps);
        let plan = self.plan.lock().unwrap().clone().unwrap_or(Value::Null);
        let out = json!({ "ok": ok, "steps": steps, "plan": plan });
        let written = std::fs::write(&self.result, serde_json::to_string_pretty(&out).unwrap()).is_ok();
        eprintln!("[selftest] {}", if ok && written { "OK" } else { "FAILED" });
        app.exit(if ok && written { 0 } else { 1 });
    }
}

fn verdict(steps: &[Step]) -> bool {
    steps.iter().all(|s| s.ok) && REQUIRED.iter().all(|r| steps.iter().any(|s| s.name == *r))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(name: &str, ok: bool) -> Step {
        Step { name: name.into(), ms: 1, ok, detail: String::new() }
    }

    #[test]
    fn passes_only_with_every_required_step_passed() {
        let all: Vec<Step> = REQUIRED.iter().map(|n| s(n, true)).collect();
        assert!(verdict(&all));
        let mut failed = all.clone();
        failed[3].ok = false;
        assert!(!verdict(&failed));
        assert!(!verdict(&all[..7]));
        let mut extra = all.clone();
        extra.push(s("timeout", false));
        assert!(!verdict(&extra));
    }

    #[test]
    fn refuses_a_data_folder_with_content() {
        let base = std::env::temp_dir().join(format!("dispofold-st-{}", crate::protocol::random_b64::<6>().replace(['/', '+'], "_")));
        let data = base.join("data");
        std::fs::create_dir_all(&data).unwrap();
        assert!(SelfTest::prepare(&base.join("r.json"), &data).is_ok());
        std::fs::write(data.join("x"), "1").unwrap();
        assert!(SelfTest::prepare(&base.join("r.json"), &data).is_err());
        std::fs::remove_dir_all(&base).unwrap();
    }
}
