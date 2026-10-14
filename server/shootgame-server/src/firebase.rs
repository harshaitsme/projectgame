//! Firebase Realtime Database Sync & Live Analytics Dashboard Server
//!
//! Pushes real-time room, player, and combat telemetry to Firebase Realtime DB
//! (`FIREBASE_DB_URL` / `FIREBASE_DB_SECRET` env vars) and serves a live JSON
//! API (`/api/analytics`) + interactive Analytics Dashboard (`/dashboard` or `/`)
//! on `SHOOTGAME_HTTP_PORT` (default `54556`).

use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::process::Command;
use tokio::sync::{mpsc, RwLock};

#[derive(Debug, Clone)]
pub struct RoomPlayerTelemetry {
    pub id: u32,
    pub name: String,
    pub health: f32,
    pub max_health: f32,
    pub kills: u32,
    pub deaths: u32,
    pub shots_fired: u32,
    pub hits_landed: u32,
    pub damage_dealt: f32,
    pub weapon_name: String,
    pub x: f32,
    pub y: f32,
}

#[derive(Debug, Clone)]
pub struct RoomTelemetry {
    pub room_code: String,
    pub game_mode: String,
    pub max_players: u32,
    pub current_players: u32,
    pub status: String,
    pub total_kills: u32,
    pub total_shots: u32,
    pub created_at: u64,
    pub players: Vec<RoomPlayerTelemetry>,
}

#[derive(Debug, Clone)]
pub struct CombatEventTelemetry {
    pub event_type: String,
    pub room_code: String,
    pub game_mode: String,
    pub actor: String,
    pub target: String,
    pub weapon: String,
    pub damage: f32,
    pub timestamp: u64,
}

#[derive(Debug, Clone, Default)]
pub struct ServerAnalyticsSnapshot {
    pub timestamp: u64,
    pub uptime_seconds: u64,
    pub active_rooms_count: usize,
    pub online_players_count: usize,
    pub total_kills: u32,
    pub total_shots: u32,
    pub firebase_configured: bool,
    pub rooms: Vec<RoomTelemetry>,
    pub all_players: Vec<(String, RoomPlayerTelemetry)>,
    pub recent_events: Vec<CombatEventTelemetry>,
}

pub enum FirebaseOp {
    SyncSnapshot(String),
    PushEvent(String),
}

fn escape_json(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {}
            c => out.push(c),
        }
    }
    out
}

pub fn event_to_json(ev: &CombatEventTelemetry) -> String {
    format!(
        r#"{{"event_type":"{}","room_code":"{}","game_mode":"{}","actor":"{}","target":"{}","weapon":"{}","damage":{:.1},"timestamp":{}}}"#,
        escape_json(&ev.event_type),
        escape_json(&ev.room_code),
        escape_json(&ev.game_mode),
        escape_json(&ev.actor),
        escape_json(&ev.target),
        escape_json(&ev.weapon),
        ev.damage,
        ev.timestamp
    )
}

pub fn snapshot_to_json(snap: &ServerAnalyticsSnapshot) -> String {
    let mut rooms_json = String::from("[");
    let mut rooms_map_json = String::from("{");

    for (idx, r) in snap.rooms.iter().enumerate() {
        if idx > 0 {
            rooms_json.push(',');
            rooms_map_json.push(',');
        }
        let mut p_arr = String::from("[");
        for (pidx, p) in r.players.iter().enumerate() {
            if pidx > 0 {
                p_arr.push(',');
            }
            let kd = if p.deaths == 0 {
                p.kills as f32
            } else {
                p.kills as f32 / p.deaths as f32
            };
            let acc = if p.shots_fired == 0 {
                0.0
            } else {
                (p.hits_landed as f32 / p.shots_fired as f32) * 100.0
            };
            p_arr.push_str(&format!(
                r#"{{"id":{},"name":"{}","health":{:.0},"max_health":{:.0},"kills":{},"deaths":{},"kd_ratio":{:.2},"shots_fired":{},"hits_landed":{},"accuracy_pct":{:.1},"damage_dealt":{:.0},"weapon":"{}","x":{:.2},"y":{:.2}}}"#,
                p.id,
                escape_json(&p.name),
                p.health,
                p.max_health,
                p.kills,
                p.deaths,
                kd,
                p.shots_fired,
                p.hits_landed,
                acc,
                p.damage_dealt,
                escape_json(&p.weapon_name),
                p.x,
                p.y
            ));
        }
        p_arr.push(']');

        let room_obj = format!(
            r#"{{"room_code":"{}","game_mode":"{}","max_players":{},"current_players":{},"status":"{}","total_kills":{},"total_shots":{},"created_at":{},"players":{}}}"#,
            escape_json(&r.room_code),
            escape_json(&r.game_mode),
            r.max_players,
            r.current_players,
            escape_json(&r.status),
            r.total_kills,
            r.total_shots,
            r.created_at,
            p_arr
        );
        rooms_json.push_str(&room_obj);
        rooms_map_json.push_str(&format!(r#""{}":{}"#, escape_json(&r.room_code), room_obj));
    }
    rooms_json.push(']');
    rooms_map_json.push('}');

    let mut players_json = String::from("[");
    for (idx, (room_code, p)) in snap.all_players.iter().enumerate() {
        if idx > 0 {
            players_json.push(',');
        }
        let kd = if p.deaths == 0 {
            p.kills as f32
        } else {
            p.kills as f32 / p.deaths as f32
        };
        let acc = if p.shots_fired == 0 {
            0.0
        } else {
            (p.hits_landed as f32 / p.shots_fired as f32) * 100.0
        };
        players_json.push_str(&format!(
            r#"{{"id":{},"name":"{}","room_code":"{}","health":{:.0},"max_health":{:.0},"kills":{},"deaths":{},"kd_ratio":{:.2},"shots_fired":{},"hits_landed":{},"accuracy_pct":{:.1},"damage_dealt":{:.0},"weapon":"{}","x":{:.2},"y":{:.2}}}"#,
            p.id,
            escape_json(&p.name),
            escape_json(room_code),
            p.health,
            p.max_health,
            p.kills,
            p.deaths,
            kd,
            p.shots_fired,
            p.hits_landed,
            acc,
            p.damage_dealt,
            escape_json(&p.weapon_name),
            p.x,
            p.y
        ));
    }
    players_json.push(']');

    let mut events_json = String::from("[");
    for (idx, ev) in snap.recent_events.iter().enumerate() {
        if idx > 0 {
            events_json.push(',');
        }
        events_json.push_str(&event_to_json(ev));
    }
    events_json.push(']');

    format!(
        r#"{{"timestamp":{},"uptime_seconds":{},"active_rooms_count":{},"online_players_count":{},"total_kills":{},"total_shots":{},"firebase_configured":{},"rooms":{},"rooms_by_code":{},"players":{},"recent_events":{}}}"#,
        snap.timestamp,
        snap.uptime_seconds,
        snap.active_rooms_count,
        snap.online_players_count,
        snap.total_kills,
        snap.total_shots,
        if snap.firebase_configured { "true" } else { "false" },
        rooms_json,
        rooms_map_json,
        players_json,
        events_json
    )
}

/// Spawns the background Firebase Realtime Database sync worker.
/// Uses `FIREBASE_DB_URL` (e.g. `https://my-project-default-rtdb.firebaseio.com`)
/// and optional `FIREBASE_DB_SECRET` for authentication.
pub fn spawn_firebase_worker() -> (mpsc::UnboundedSender<FirebaseOp>, bool) {
    let db_url = std::env::var("FIREBASE_DB_URL")
        .unwrap_or_default()
        .trim_end_matches('/')
        .to_string();
    let db_secret = std::env::var("FIREBASE_DB_SECRET").unwrap_or_default();
    let configured = !db_url.is_empty();

    let (tx, mut rx) = mpsc::unbounded_channel::<FirebaseOp>();

    if configured {
        eprintln!("Firebase Realtime DB sync enabled -> {db_url}");
    } else {
        eprintln!(
            "Firebase Realtime DB URL not set (set FIREBASE_DB_URL to enable cloud sync). Live HTTP analytics endpoint still active."
        );
    }

    tokio::spawn(async move {
        while let Some(op) = rx.recv().await {
            if !configured {
                continue;
            }
            let auth_query = if db_secret.is_empty() {
                String::new()
            } else {
                format!("?auth={db_secret}")
            };

            match op {
                FirebaseOp::SyncSnapshot(json_body) => {
                    let url = format!("{db_url}/shootgame/live.json{auth_query}");
                    let _ = Command::new("curl")
                        .args([
                            "-sS",
                            "-m",
                            "4",
                            "-X",
                            "PUT",
                            "-H",
                            "Content-Type: application/json",
                            "-d",
                            &json_body,
                            &url,
                        ])
                        .output()
                        .await;
                }
                FirebaseOp::PushEvent(event_json) => {
                    let url = format!("{db_url}/shootgame/events.json{auth_query}");
                    let _ = Command::new("curl")
                        .args([
                            "-sS",
                            "-m",
                            "4",
                            "-X",
                            "POST",
                            "-H",
                            "Content-Type: application/json",
                            "-d",
                            &event_json,
                            &url,
                        ])
                        .output()
                        .await;
                }
            }
        }
    });

    (tx, configured)
}

/// Spawns the built-in HTTP Analytics API & Real-Time Dashboard Server.
pub fn spawn_analytics_http_server(
    port: u16,
    shared_json: Arc<RwLock<String>>,
) {
    tokio::spawn(async move {
        let listener = match TcpListener::bind(("0.0.0.0", port)).await {
            Ok(l) => {
                eprintln!(
                    "Real-Time Analytics Dashboard & API listening on http://0.0.0.0:{port}/dashboard (JSON: /api/analytics)"
                );
                l
            }
            Err(e) => {
                eprintln!("Analytics HTTP server bind on port {port} failed: {e}");
                return;
            }
        };

        loop {
            if let Ok((mut stream, _)) = listener.accept().await {
                let json_ref = shared_json.clone();
                tokio::spawn(async move {
                    let mut buf = [0u8; 2048];
                    let n = stream.read(&mut buf).await.unwrap_or(0);
                    if n == 0 {
                        return;
                    }
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let first_line = req.lines().next().unwrap_or("");

                    if first_line.starts_with("GET /api/analytics")
                        || first_line.starts_with("GET /analytics.json")
                    {
                        let body = json_ref.read().await.clone();
                        let resp = format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                            body.len(),
                            body
                        );
                        let _ = stream.write_all(resp.as_bytes()).await;
                    } else {
                        let html = DASHBOARD_HTML;
                        let resp = format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                            html.len(),
                            html
                        );
                        let _ = stream.write_all(resp.as_bytes()).await;
                    }
                });
            }
        }
    });
}

const DASHBOARD_HTML: &str = r#"<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>SHOOTGAME SERVER — Real-Time Player & Room Analytics Dashboard</title>
  <style>
    :root {
      --bg: #080d18;
      --card: #0f172a;
      --border: #1e293b;
      --cyan: #22d3ee;
      --green: #4ade80;
      --amber: #fbbf24;
      --red: #f87171;
      --text: #e2e8f0;
      --muted: #94a3b8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Segoe UI', system-ui, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 24px; }
    header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 22px; }
    .brand h1 { color: var(--cyan); font-size: 22px; letter-spacing: 1px; }
    .brand p { color: var(--muted); font-size: 13px; margin-top: 4px; }
    .badge { padding: 6px 12px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #132a1f; color: var(--green); border: 1px solid #22543d; }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin-bottom: 22px; }
    .kpi { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 16px; }
    .kpi .label { color: var(--muted); font-size: 12px; text-transform: uppercase; }
    .kpi .val { font-size: 28px; font-weight: 700; color: var(--cyan); margin-top: 6px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 18px; }
    @media (max-width: 960px) { .grid { grid-template-columns: 1fr; } }
    .panel { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 18px; }
    .panel h2 { font-size: 15px; color: var(--amber); margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.5px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border); }
    th { color: var(--muted); font-weight: 600; }
    .code-pill { background: #112233; color: var(--cyan); padding: 3px 8px; border-radius: 5px; font-family: monospace; font-weight: 700; letter-spacing: 1px; }
    .event-list { max-height: 260px; overflow-y: auto; font-size: 13px; }
    .event-item { padding: 7px 0; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; }
  </style>
</head>
<body>
  <header>
    <div class="brand">
      <h1>SHOOTGAME SERVER — REAL-TIME TELEMETRY DASHBOARD</h1>
      <p>Live Match Rooms (Duo & Squad) • Player Combat Analytics • Firebase Realtime DB Sync</p>
    </div>
    <div id="statusBadge" class="badge">● LIVE TELEMETRY CONNECTED</div>
  </header>

  <div class="kpis">
    <div class="kpi"><div class="label">Active Game Rooms</div><div class="val" id="kpiRooms">0</div></div>
    <div class="kpi"><div class="label">Online Operatives</div><div class="val" id="kpiPlayers">0</div></div>
    <div class="kpi"><div class="label">Total Eliminations</div><div class="val" id="kpiKills">0</div></div>
    <div class="kpi"><div class="label">Total Shots Fired</div><div class="val" id="kpiShots">0</div></div>
    <div class="kpi"><div class="label">Firebase DB Sync</div><div class="val" id="kpiFirebase" style="font-size:18px;">ACTIVE</div></div>
  </div>

  <div class="grid">
    <div class="panel">
      <h2>Active Match Rooms (Duo & Squad)</h2>
      <table>
        <thead><tr><th>Room Code</th><th>Mode</th><th>Players</th><th>Kills</th><th>Shots</th><th>Status</th></tr></thead>
        <tbody id="roomsBody"><tr><td colspan="6">No active rooms</td></tr></tbody>
      </table>
    </div>

    <div class="panel">
      <h2>Live Operative Leaderboard & Combat Telemetry</h2>
      <table>
        <thead><tr><th>Operative</th><th>Room</th><th>HP</th><th>K / D (Ratio)</th><th>Shots / Hits (Acc%)</th><th>Dmg</th><th>Weapon</th></tr></thead>
        <tbody id="playersBody"><tr><td colspan="7">No players online</td></tr></tbody>
      </table>
    </div>
  </div>

  <div class="panel">
    <h2>Real-Time Combat & Room Event Stream</h2>
    <div class="event-list" id="eventsList">Waiting for events...</div>
  </div>

  <script>
    async function refresh() {
      try {
        const res = await fetch('/api/analytics');
        const data = await res.json();
        document.getElementById('kpiRooms').textContent = data.active_rooms_count;
        document.getElementById('kpiPlayers').textContent = data.online_players_count;
        document.getElementById('kpiKills').textContent = data.total_kills;
        document.getElementById('kpiShots').textContent = data.total_shots;
        document.getElementById('kpiFirebase').textContent = data.firebase_configured ? 'CLOUD SYNC ON' : 'LOCAL API READY';

        const rBody = document.getElementById('roomsBody');
        if (!data.rooms || data.rooms.length === 0) {
          rBody.innerHTML = '<tr><td colspan="6" style="color:#94a3b8;">No active rooms</td></tr>';
        } else {
          rBody.innerHTML = data.rooms.map(r => `
            <tr>
              <td><span class="code-pill">${r.room_code}</span></td>
              <td>${r.game_mode}</td>
              <td>${r.current_players} / ${r.max_players}</td>
              <td>${r.total_kills}</td>
              <td>${r.total_shots}</td>
              <td>${r.status}</td>
            </tr>
          `).join('');
        }

        const pBody = document.getElementById('playersBody');
        if (!data.players || data.players.length === 0) {
          pBody.innerHTML = '<tr><td colspan="7" style="color:#94a3b8;">No operatives currently in match</td></tr>';
        } else {
          pBody.innerHTML = data.players.map(p => `
            <tr>
              <td><strong>${p.name}</strong> (#${p.id})</td>
              <td><span class="code-pill">${p.room_code}</span></td>
              <td>${p.health} / ${p.max_health}</td>
              <td>${p.kills}K / ${p.deaths}D (${p.kd_ratio})</td>
              <td>${p.shots_fired} / ${p.hits_landed} (${p.accuracy_pct}%)</td>
              <td>${p.damage_dealt}</td>
              <td>${p.weapon}</td>
            </tr>
          `).join('');
        }

        const evList = document.getElementById('eventsList');
        if (data.recent_events && data.recent_events.length > 0) {
          evList.innerHTML = data.recent_events.slice().reverse().map(e => `
            <div class="event-item">
              <span><strong>[${e.event_type}]</strong> Room <span class="code-pill">${e.room_code}</span> (${e.game_mode}) — ${e.actor} ${e.target ? '→ ' + e.target : ''} ${e.weapon ? '(' + e.weapon + ')' : ''}</span>
              <span style="color:#94a3b8;">${new Date(e.timestamp * 1000).toLocaleTimeString()}</span>
            </div>
          `).join('');
        }
      } catch (e) {}
    }
    refresh();
    setInterval(refresh, 1500);
  </script>
</body>
</html>
"#;
