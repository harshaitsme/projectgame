/// Phase 4 – ECS Simulation & Game Systems for ShootGame Rust Dedicated Server
///
/// Architecture: single-owner game loop.
/// `specs::World` is not `Send`, so it lives inside the main task exclusively.
/// All cross-task communication happens via an mpsc channel and the UDP socket
/// (wrapped in `Arc` for the registration-receiver task).
///
///   main task (owns World, tick, broadcast)
///     ├── TCP accept        → spawn client handler task
///     ├── msg channel        ← client handlers (login / state / shoot / leave)
///     ├── UDP registration  → learned from udp_receiver task
///     └── fixed tick (60 Hz) → run systems → broadcast state via UDP
///
///   udp_receiver task        → parses registration datagrams, forwards client id
///
/// Components: Position, Rotation, Velocity, Lifetime, PlayerId, Health, Owner, Cooldown
/// Systems:    Motion (projectiles), Lifetime (despawn), Bounds (players), HitDetection (combat)
use shootgame_server::network::{
    self as net,
    LoginResponsePacket, PlayerStatePacket, PlayerShootPacket, PlayerHitPacket,
    TYPE_LOGIN_RESPONSE, TYPE_PLAYER_STATE, TYPE_PLAYER_HIT,
};
use specs::{Component, Entity, Join, VecStorage, World};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use tokio::io::AsyncReadExt;
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::{mpsc, oneshot};
use tokio::time::{interval, Duration, MissedTickBehavior};

/// Fixed simulation timestep (60 Hz).
const DT: f32 = 1.0 / 60.0;
/// Server-side projectile speed (world units / second).
const PROJECTILE_SPEED: f32 = 25.0;
/// How long a projectile lives (seconds).
const PROJECTILE_LIFE: f32 = 2.0;
/// Map bounds used by the authoritative Bounds system.
const MAP_MIN: f32 = 0.0;
const MAP_MAX: f32 = 100.0;
/// Combat tuning.
const START_HEALTH: f32 = 100.0;
const HIT_RADIUS: f32 = 0.5;
const HIT_DAMAGE: f32 = 25.0;
/// Minimum ticks between shots per player (6 ticks ≈ 100 ms at 60 Hz).
const SHOOT_COOLDOWN_TICKS: u64 = 6;
/// Interest-management radius (world units). Clients only receive state for
/// entities within this distance of themselves.
const VIEW_RADIUS: f32 = 25.0;
const VIEW_RADIUS_SQ: f32 = VIEW_RADIUS * VIEW_RADIUS;
/// Reconnection grace period (ticks). A disconnected session stays resumable
/// for this long — 120 s at 60 Hz.
const SESSION_GRACE_TICKS: u64 = 120 * 60;

// ---------------------------------------------------------------------------
// ECS components.
// Component is implemented manually (specs 0.10 derive-version mismatch),
// using the default VecStorage for every component.
// ---------------------------------------------------------------------------

/// World-space 2D position.
#[derive(Debug, Clone, Copy)]
struct Position {
    x: f32,
    y: f32,
}
impl Component for Position {
    type Storage = VecStorage<Position>;
}

/// Facing direction (cos/sin of the angle) for players.
#[derive(Debug, Clone, Copy)]
struct Rotation {
    cos: f32,
    sin: f32,
}
impl Component for Rotation {
    type Storage = VecStorage<Rotation>;
}

/// Constant velocity (used by projectiles).
#[derive(Debug, Clone, Copy)]
struct Velocity {
    dx: f32,
    dy: f32,
}
impl Component for Velocity {
    type Storage = VecStorage<Velocity>;
}

/// Time-to-live (used by projectiles).
#[derive(Debug, Clone, Copy)]
struct Lifetime {
    remaining: f32,
}
impl Component for Lifetime {
    type Storage = VecStorage<Lifetime>;
}

/// Marks an entity as a player and carries the logical client id.
#[derive(Debug, Clone, Copy)]
struct PlayerId {
    id: u32,
}
impl Component for PlayerId {
    type Storage = VecStorage<PlayerId>;
}

/// Player health.
#[derive(Debug, Clone, Copy)]
struct Health {
    current: f32,
    max: f32,
}
impl Component for Health {
    type Storage = VecStorage<Health>;
}

/// Marks an entity as a projectile and carries the shooter's client id
/// (prevents a projectile from hitting its own owner).
#[derive(Debug, Clone, Copy)]
struct Owner {
    id: u32,
}
impl Component for Owner {
    type Storage = VecStorage<Owner>;
}

/// Weapon cooldown, expressed in simulation ticks.
#[derive(Debug, Clone, Copy)]
struct Cooldown {
    next_tick: u64,
}
impl Component for Cooldown {
    type Storage = VecStorage<Cooldown>;
}

/// Default spawn position for a given client id (kept on the server).
fn spawn_pos(id: u32) -> (f32, f32) {
    (3.0f32 + (id % 5) as f32 * 1.5, 4.0f32)
}

// ---------------------------------------------------------------------------
// Messages that cross the async-task boundary into the main game loop.
// ---------------------------------------------------------------------------

/// Server-side record of a login session (supports reconnection).
struct Session {
    player_id: u32,
    connected: bool,
    last_active_tick: u64,
}

/// Reply sent back over a oneshot channel when the main loop resolves a login.
struct LoginResult {
    id: u32,
    spawn_x: f32,
    spawn_y: f32,
    session_token: String,
}

enum ServerMessage {
    /// A client requested login (or reconnection). The main loop resolves the
    /// session and replies with the assigned identity.
    Login {
        player_name: String,
        session_token: String,
        reply: oneshot::Sender<LoginResult>,
    },
    /// A client reported new input/state (player movement).
    State(PlayerStatePacket),
    /// A client fired a weapon (spawns a server-side projectile).
    Shoot(PlayerShootPacket),
    /// A client disconnected.
    Disconnected { id: u32 },
    /// A client's UDP address was learned from a registration datagram.
    RegisterUdp { id: u32, addr: SocketAddr },
}

/// Generate an opaque, hard-to-guess session token.
fn gen_token() -> String {
    let n: u64 = rand::random();
    format!("{n:016x}")
}

// ---------------------------------------------------------------------------
// Global client-ID generator (shared across handler tasks).
// ---------------------------------------------------------------------------
static NEXT_CLIENT_ID: AtomicU32 = AtomicU32::new(1);

// ---------------------------------------------------------------------------
// Per-connection handler. Reads framed packets, forwards them to the main loop.
// TCP framing: [u32 len][u8 type][protobuf body].
// ---------------------------------------------------------------------------
async fn client_handler(
    mut tcp: tokio::net::TcpStream,
    msg_tx: mpsc::Sender<ServerMessage>,
) {
    let remote = tcp.peer_addr().unwrap_or_else(|_| "0.0.0.0:0".parse().unwrap());
    let mut stream_buf: Vec<u8> = Vec::new();
    let mut tmp = [0u8; 4096];
    let mut client_id: Option<u32> = None;

    loop {
        let n = match tcp.read(&mut tmp).await {
            Ok(0) => break,                       // peer closed
            Ok(n) => n,
            Err(e) => {
                eprintln!("Client {remote} TCP read error: {e}");
                break;
            }
        };
        stream_buf.extend_from_slice(&tmp[..n]);

        // Decode every complete frame we have so far; keep any remainder.
        let (packets, consumed) = net::parse_tcp(&stream_buf);
        if consumed > 0 {
            stream_buf.drain(..consumed);
        }

        for pkt in packets {
            match pkt {
                net::ClientPacket::Login(login) => {
                    if client_id.is_some() {
                        continue; // already logged in on this connection
                    }
                    let (reply_tx, reply_rx) = oneshot::channel();
                    let is_reconnect = !login.session_token.is_empty();
                    if msg_tx
                        .send(ServerMessage::Login {
                            player_name: login.player_name,
                            session_token: login.session_token,
                            reply: reply_tx,
                        })
                        .await
                        .is_err()
                    {
                        return;
                    }
                    let result: LoginResult = match reply_rx.await {
                        Ok(r) => r,
                        Err(_) => {
                            eprintln!("Main loop dropped login reply for {remote}");
                            return;
                        }
                    };
                    client_id = Some(result.id);
                    eprintln!(
                        "Client {} ready: id={}, addr={remote}",
                        if is_reconnect { "reconnected" } else { "connected" },
                        result.id
                    );
                    let resp = LoginResponsePacket {
                        assigned_id: result.id,
                        spawn_x: result.spawn_x,
                        spawn_y: result.spawn_y,
                        session_token: result.session_token,
                    };
                    let _ = tcp.write_all(&net::tcp_frame(TYPE_LOGIN_RESPONSE, &resp)).await;
                }
                net::ClientPacket::State(mut p) => {
                    if let Some(id) = client_id {
                        p.id = id; // authoritative: ignore client-supplied id
                        if msg_tx.send(ServerMessage::State(p)).await.is_err() {
                            break;
                        }
                    }
                }
                net::ClientPacket::Shoot(mut p) => {
                    if let Some(id) = client_id {
                        p.id = id;
                        if msg_tx.send(ServerMessage::Shoot(p)).await.is_err() {
                            break;
                        }
                    }
                }
                net::ClientPacket::Register(_) => {} // registration is UDP-only
            }
        }
    }

    // Cleanup — start the reconnection grace window.
    if let Some(id) = client_id {
        eprintln!("Client disconnected: id={id}");
        let _ = msg_tx.send(ServerMessage::Disconnected { id }).await;
    }
}

// ---------------------------------------------------------------------------
// UDP registration receiver. Learns each client's real UDP address.
// ---------------------------------------------------------------------------
async fn udp_receiver(udp: Arc<UdpSocket>, msg_tx: mpsc::Sender<ServerMessage>) {
    let mut buf = [0u8; 4096];
    loop {
        match udp.recv_from(&mut buf).await {
            Ok((n, addr)) => {
                if let Some(net::ClientPacket::Register(client_id)) =
                    net::parse_udp_client(&buf[..n])
                {
                    eprintln!("UDP registration: id={client_id} addr={addr}");
                    let _ = msg_tx
                        .send(ServerMessage::RegisterUdp {
                            id: client_id,
                            addr,
                        })
                        .await;
                }
            }
            Err(e) => {
                eprintln!("UDP recv error: {e}");
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Message application to the World.
// ---------------------------------------------------------------------------
fn handle_message(
    world: &mut World,
    entities: &mut HashMap<u32, Entity>,
    addrs: &mut HashMap<u32, SocketAddr>,
    projectiles: &mut Vec<Entity>,
    sessions: &mut HashMap<String, Session>,
    token_by_player: &mut HashMap<u32, String>,
    msg: ServerMessage,
    tick: u64,
) {
    match msg {
        ServerMessage::Login {
            player_name,
            session_token,
            reply,
        } => {
            let now = tick;
            // Resume an existing session if the token is still valid, else mint
            // a fresh player id + token.
            let (player_id, token) = if !session_token.is_empty()
                && sessions.contains_key(&session_token)
            {
                let sess = sessions.get_mut(&session_token).unwrap();
                sess.connected = true;
                sess.last_active_tick = now;
                let pid = sess.player_id;
                (pid, session_token)
            } else {
                let id = NEXT_CLIENT_ID.fetch_add(1, Ordering::Relaxed);
                let token = gen_token();
                sessions.insert(
                    token.clone(),
                    Session {
                        player_id: id,
                        connected: true,
                        last_active_tick: now,
                    },
                );
                token_by_player.insert(id, token.clone());
                (id, token)
            };
            eprintln!("Login resolved: player={player_name:?} id={player_id}");

            // Create the player entity if one isn't already present.
            if !entities.contains_key(&player_id) {
                let (x, y) = spawn_pos(player_id);
                let entity = world
                    .create_entity()
                    .with(Position { x, y })
                    .with(Rotation { cos: 1.0, sin: 0.0 })
                    .with(PlayerId { id: player_id })
                    .with(Health {
                        current: START_HEALTH,
                        max: START_HEALTH,
                    })
                    .with(Cooldown { next_tick: 0 })
                    .build();
                entities.insert(player_id, entity);
            }

            let (sx, sy) = spawn_pos(player_id);
            let _ = reply.send(LoginResult {
                id: player_id,
                spawn_x: sx,
                spawn_y: sy,
                session_token: token,
            });
        }

        ServerMessage::State(pkt) => {
            // Update the matching player's Position/Rotation.
            let entity = match entities.get(&pkt.id) {
                Some(&e) => e,
                None => return,
            };
            {
                let mut positions = world.write::<Position>();
                if let Some(pos) = positions.get_mut(entity) {
                    pos.x = pkt.x;
                    pos.y = pkt.y;
                }
            }
            {
                let mut rotations = world.write::<Rotation>();
                if let Some(rot) = rotations.get_mut(entity) {
                    rot.cos = pkt.cos;
                    rot.sin = pkt.sin;
                }
            }
        }

        ServerMessage::Shoot(pkt) => {
            // Validate direction is non-zero.
            let mag = (pkt.direction_x * pkt.direction_x
                + pkt.direction_y * pkt.direction_y)
                .sqrt();
            if mag <= 0.0 {
                return;
            }

            // Enforce per-player weapon cooldown (server authority).
            if let Some(&entity) = entities.get(&pkt.id) {
                let mut cooldowns = world.write::<Cooldown>();
                if let Some(cd) = cooldowns.get_mut(entity) {
                    if tick < cd.next_tick {
                        eprintln!(
                            "Shoot rejected (cooldown) for id={} (ready at tick {})",
                            pkt.id, cd.next_tick
                        );
                        return;
                    }
                    cd.next_tick = tick + SHOOT_COOLDOWN_TICKS;
                }
            }

            let (nx, ny) = (pkt.direction_x / mag, pkt.direction_y / mag);
            // Spawn a server-authoritative projectile entity (owned by the shooter).
            let entity = world
                .create_entity()
                .with(Position {
                    x: pkt.origin_x,
                    y: pkt.origin_y,
                })
                .with(Velocity {
                    dx: nx * PROJECTILE_SPEED,
                    dy: ny * PROJECTILE_SPEED,
                })
                .with(Lifetime {
                    remaining: PROJECTILE_LIFE,
                })
                .with(Owner { id: pkt.id })
                .build();
            projectiles.push(entity);
            eprintln!("Spawned projectile from id={}", pkt.id);
        }

        ServerMessage::Disconnected { id } => {
            // Start a grace window; keep the session resumable for SESSION_GRACE_TICKS.
            if let Some(token) = token_by_player.get(&id) {
                if let Some(sess) = sessions.get_mut(token) {
                    sess.connected = false;
                    sess.last_active_tick = tick;
                }
            }
            if let Some(entity) = entities.remove(&id) {
                let _ = world.delete_entity(entity);
            }
            addrs.remove(&id);
        }

        ServerMessage::RegisterUdp { id, addr } => {
            addrs.insert(id, addr);
        }
    }
}

/// Remove sessions whose reconnection grace window has expired.
fn prune_sessions(
    sessions: &mut HashMap<String, Session>,
    token_by_player: &mut HashMap<u32, String>,
    tick: u64,
) {
    let expired: Vec<String> = sessions
        .iter()
        .filter(|(_, s)| {
            !s.connected && tick.saturating_sub(s.last_active_tick) > SESSION_GRACE_TICKS
        })
        .map(|(t, _)| t.clone())
        .collect();
    for token in expired {
        if let Some(sess) = sessions.remove(&token) {
            token_by_player.remove(&sess.player_id);
            eprintln!("Session expired for player id={}", sess.player_id);
        }
    }
}

// ---------------------------------------------------------------------------
// Simulation systems. Run every tick on the World, in order.
// ---------------------------------------------------------------------------
fn run_systems(
    world: &mut World,
    entities: &HashMap<u32, Entity>,
    projectiles: &mut Vec<Entity>,
) -> Vec<PlayerHitPacket> {
    // --- Motion system (projectiles): Position += Velocity * dt ------------
    {
        let mut positions = world.write::<Position>();
        let velocities = world.read::<Velocity>();
        for (pos, vel) in (&mut positions, &velocities).join() {
            pos.x += vel.dx * DT;
            pos.y += vel.dy * DT;
        }
    }

    // --- Lifetime system: tick down and despawn expired projectiles --------
    {
        let mut lifetimes = world.write::<Lifetime>();
        let mut survivors: Vec<Entity> = Vec::with_capacity(projectiles.len());
        for &entity in projectiles.iter() {
            match lifetimes.get_mut(entity) {
                Some(lt) => {
                    lt.remaining -= DT;
                    if lt.remaining > 0.0 {
                        survivors.push(entity);
                    }
                }
                None => {} // component already gone; drop from tracking
            }
        }
        // Drop the storage borrow before deleting entities.
        drop(lifetimes);
        for &entity in projectiles.iter() {
            if !survivors.iter().any(|&e| e == entity) {
                let _ = world.delete_entity(entity);
            }
        }
        *projectiles = survivors;
    }

    // --- Bounds system (players): clamp position to the map -----------------
    {
        let mut positions = world.write::<Position>();
        let player_ids = world.read::<PlayerId>();
        for (_pid, pos) in (&player_ids, &mut positions).join() {
            pos.x = pos.x.clamp(MAP_MIN, MAP_MAX);
            pos.y = pos.y.clamp(MAP_MIN, MAP_MAX);
        }
    }

    // --- Hit detection system (projectile vs player) ------------------------
    // Snapshot projectile (entity, owner, x, y) and player (entity, id, x, y),
    // then resolve collisions outside of borrows to apply damage / despawn.
    let mut hits: Vec<PlayerHitPacket> = Vec::new();

    let proj_data: Vec<(Entity, u32, f32, f32)> = {
        let positions = world.read::<Position>();
        let owners = world.read::<Owner>();
        let mut out = Vec::new();
        for &e in projectiles.iter() {
            if let (Some(pos), Some(own)) = (positions.get(e), owners.get(e)) {
                out.push((e, own.id, pos.x, pos.y));
            }
        }
        out
    };

    let player_data: Vec<(Entity, u32, f32, f32)> = {
        let positions = world.read::<Position>();
        let mut out = Vec::new();
        for (&id, &e) in entities.iter() {
            if let Some(pos) = positions.get(e) {
                out.push((e, id, pos.x, pos.y));
            }
        }
        out
    };

    let r2 = HIT_RADIUS * HIT_RADIUS;
    let mut projectiles_to_delete: Vec<Entity> = Vec::new();
    for &(pe, owner_id, px, py) in proj_data.iter() {
        for &(le, pid, lx, ly) in player_data.iter() {
            if pid == owner_id {
                continue; // a player cannot hit themselves
            }
            let dx = px - lx;
            let dy = py - ly;
            if dx * dx + dy * dy <= r2 {
                projectiles_to_delete.push(pe);

                // Apply damage (and respawn if the victim died).
                let mut healths = world.write::<Health>();
                if let Some(h) = healths.get_mut(le) {
                    h.current = (h.current - HIT_DAMAGE).max(0.0);
                    hits.push(PlayerHitPacket {
                        from_id: owner_id,
                        to_id: pid,
                        damage: HIT_DAMAGE,
                        x: lx,
                        y: ly,
                    });
                    eprintln!(
                        "HIT: id={} hit id={} (health now {})",
                        owner_id, pid, h.current
                    );
                    if h.current <= 0.0 {
                        h.current = h.max;
                        drop(healths);
                        let (sx, sy) = spawn_pos(pid);
                        let mut positions = world.write::<Position>();
                        if let Some(pos) = positions.get_mut(le) {
                            pos.x = sx;
                            pos.y = sy;
                        }
                        eprintln!("id={} died and respawned at ({}, {})", pid, sx, sy);
                    }
                }
                break; // a projectile can only hit one player
            }
        }
    }

    // Despawn consumed projectiles and drop them from tracking.
    if !projectiles_to_delete.is_empty() {
        for &e in projectiles_to_delete.iter() {
            let _ = world.delete_entity(e);
        }
        projectiles.retain(|e| !projectiles_to_delete.contains(e));
    }

    hits
}

/// Lightweight, ownership-friendly copy of each player's broadcastable state.
struct PlayerSnapshot {
    id: u32,
    x: f32,
    y: f32,
    cos: f32,
    sin: f32,
}

/// Collect every player's authoritative state for this tick.
fn build_player_snapshot(world: &World) -> Vec<PlayerSnapshot> {
    let player_ids = world.read::<PlayerId>();
    let positions = world.read::<Position>();
    let rotations = world.read::<Rotation>();
    (&player_ids, &positions, &rotations)
        .join()
        .map(|(pid, pos, rot)| PlayerSnapshot {
            id: pid.id,
            x: pos.x,
            y: pos.y,
            cos: rot.cos,
            sin: rot.sin,
        })
        .collect()
}

fn snapshot_pos(snapshot: &[PlayerSnapshot], id: u32) -> Option<(f32, f32)> {
    snapshot
        .iter()
        .find(|p| p.id == id)
        .map(|p| (p.x, p.y))
}

// ---------------------------------------------------------------------------
// Broadcast: send each client only the state of entities within VIEW_RADIUS.
// ---------------------------------------------------------------------------
async fn broadcast(
    snapshot: &[PlayerSnapshot],
    addrs: &HashMap<u32, SocketAddr>,
    udp: &UdpSocket,
) {
    for (&client_id, &addr) in addrs.iter() {
        // Interest origin is this client's own player position (0,0 if unknown).
        let (ox, oy) = snapshot_pos(snapshot, client_id).unwrap_or((0.0, 0.0));
        for p in snapshot.iter() {
            let dx = p.x - ox;
            let dy = p.y - oy;
            if dx * dx + dy * dy > VIEW_RADIUS_SQ {
                continue; // outside this client's view
            }
            let pkt = PlayerStatePacket {
                id: p.id,
                x: p.x,
                y: p.y,
                cos: p.cos,
                sin: p.sin,
                is_attacking: false,
                is_reloading: false,
                is_throwing: false,
            };
            let bytes = net::udp_datagram(TYPE_PLAYER_STATE, &pkt);
            if let Err(e) = udp.send_to(&bytes, addr).await {
                eprintln!("UDP broadcast to id={client_id} ({addr}) failed: {e}");
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Broadcast hit events, filtered by interest plus always to attacker/victim.
// ---------------------------------------------------------------------------
async fn broadcast_hits(
    hits: &[PlayerHitPacket],
    snapshot: &[PlayerSnapshot],
    addrs: &HashMap<u32, SocketAddr>,
    udp: &UdpSocket,
) {
    for hit in hits {
        let bytes = net::udp_datagram(TYPE_PLAYER_HIT, hit);
        for (&client_id, &addr) in addrs.iter() {
            let involved = client_id == hit.from_id || client_id == hit.to_id;
            let nearby = snapshot_pos(snapshot, client_id)
                .map(|(px, py)| {
                    let dx = px - hit.x;
                    let dy = py - hit.y;
                    dx * dx + dy * dy <= VIEW_RADIUS_SQ
                })
                .unwrap_or(false);
            if involved || nearby {
                if let Err(e) = udp.send_to(&bytes, addr).await {
                    eprintln!("UDP hit broadcast to {addr} failed: {e}");
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main game loop.
// ---------------------------------------------------------------------------
#[tokio::main]
async fn main() {
    // --- Configurable ports (env vars, safe defaults) ----------------------
    let tcp_port: u16 = std::env::var("SHOOTGAME_TCP_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(54555);
    let udp_port: u16 = std::env::var("SHOOTGAME_UDP_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(54777);

    // --- Bound sockets -----------------------------------------------------
    let tcp_listener = TcpListener::bind(("0.0.0.0", tcp_port))
        .await
        .expect("TCP listener bind failed");
    let udp_socket = Arc::new(
        UdpSocket::bind(("0.0.0.0", udp_port))
            .await
            .expect("UDP socket bind failed"),
    );
    eprintln!("ShootGame Server (hardened) listening on TCP:{tcp_port}, UDP:{udp_port}");

    // --- Shared bookkeeping -------------------------------------------------
    // (World is owned by this task; the maps track entity/address lookups.)
    let mut world = World::new();
    world.register::<Position>();
    world.register::<Rotation>();
    world.register::<Velocity>();
    world.register::<Lifetime>();
    world.register::<PlayerId>();
    world.register::<Health>();
    world.register::<Owner>();
    world.register::<Cooldown>();

    let mut entities: HashMap<u32, Entity> = HashMap::new();
    let mut addrs: HashMap<u32, SocketAddr> = HashMap::new();
    let mut projectiles: Vec<Entity> = Vec::new();
    let mut sessions: HashMap<String, Session> = HashMap::new();
    let mut token_by_player: HashMap<u32, String> = HashMap::new();
    let mut tick: u64 = 0;

    // --- Message channel ---------------------------------------------------
    let (msg_tx, mut msg_rx) = mpsc::channel::<ServerMessage>(256);

    // --- UDP registration receiver task ------------------------------------
    {
        let udp = udp_socket.clone();
        let msg_tx = msg_tx.clone();
        tokio::spawn(async move {
            udp_receiver(udp, msg_tx).await;
        });
    }

    // --- Fixed-tick timer ---------------------------------------------------
    let mut ticker = interval(Duration::from_millis(16));
    ticker.set_missed_tick_behavior(MissedTickBehavior::Skip);

    // --- Main select loop ---------------------------------------------------
    loop {
        tokio::select! {
            // New TCP connection → spawn a handler task.
            accept = tcp_listener.accept() => {
                match accept {
                    Ok((stream, addr)) => {
                        eprintln!("New TCP connection from {addr}");
                        let msg_tx = msg_tx.clone();
                        tokio::spawn(client_handler(stream, msg_tx));
                    }
                    Err(e) => eprintln!("TCP accept failed: {e}"),
                }
            }

            // Message from a handler / registration receiver.
            maybe = msg_rx.recv() => {
                match maybe {
                    Some(msg) => handle_message(
                        &mut world,
                        &mut entities,
                        &mut addrs,
                        &mut projectiles,
                        &mut sessions,
                        &mut token_by_player,
                        msg,
                        tick,
                    ),
                    None => {
                        // All senders dropped — no clients can ever return.
                        eprintln!("All channel senders dropped; shutting down.");
                        break;
                    }
                }
            }

            // Fixed tick: run simulation, maintenance, then broadcast.
            _ = ticker.tick() => {
                tick += 1;
                prune_sessions(&mut sessions, &mut token_by_player, tick);
                let hits = run_systems(&mut world, &entities, &mut projectiles);
                let snapshot = build_player_snapshot(&world);
                broadcast(&snapshot, &addrs, &udp_socket).await;
                broadcast_hits(&hits, &snapshot, &addrs, &udp_socket).await;
            }
        }
    }
}
