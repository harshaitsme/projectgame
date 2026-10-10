/// Phase 4 – ECS Simulation & Game Systems for ShootGame Rust Dedicated Server
///
/// Architecture: single-owner game loop.
/// `specs::World` is not `Send`, so it lives inside the main task exclusively.
/// All cross-task communication happens via an mpsc channel and the UDP socket
/// (wrapped in `Arc` for the registration-receiver task), plus per-client TCP
/// writer channels so state/combat events are delivered reliably even across
/// strict NATs or emulators that block inbound UDP.
use shootgame_server::network::{
    self as net,
    LoginResponsePacket, PlayerConnectedPacket, PlayerDisconnectedPacket,
    PlayerHitPacket, PlayerShootPacket, PlayerStatePacket,
    TYPE_LOGIN_RESPONSE, TYPE_PLAYER_CONNECTED, TYPE_PLAYER_DISCONNECTED,
    TYPE_PLAYER_HIT, TYPE_PLAYER_SHOOT, TYPE_PLAYER_STATE,
};
use specs::{Component, Entity, Join, VecStorage, World};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
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
const HIT_HALF_W: f32 = 0.85;
const HIT_HALF_H: f32 = 1.85;
/// Minimum ticks between shots per player (3 ticks ≈ 50 ms at 60 Hz).
const SHOOT_COOLDOWN_TICKS: u64 = 3;
/// Interest-management radius (world units). Large enough for the entire arena map.
const VIEW_RADIUS: f32 = 1000.0;
const VIEW_RADIUS_SQ: f32 = VIEW_RADIUS * VIEW_RADIUS;
/// Reconnection grace period (ticks). A disconnected session stays resumable
/// for this long — 120 s at 60 Hz.
const SESSION_GRACE_TICKS: u64 = 120 * 60;

// ---------------------------------------------------------------------------
// ECS components.
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

/// Facing direction (cos = horizontal movement, sin = facing sign) for players.
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

/// Marks an entity as a projectile and carries the shooter's client id and weapon damage.
#[derive(Debug, Clone, Copy)]
struct Owner {
    id: u32,
    damage: f32,
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

/// Player action/animation state.
#[derive(Debug, Clone, Copy, Default)]
struct ActionState {
    is_attacking: bool,
    is_reloading: bool,
    is_throwing: bool,
}
impl Component for ActionState {
    type Storage = VecStorage<ActionState>;
}

/// Player metadata (username, kills, deaths, selected weapon).
#[derive(Debug, Clone, Default)]
struct PlayerMeta {
    name: String,
    kills: u32,
    deaths: u32,
    weapon_index: u32,
}
impl Component for PlayerMeta {
    type Storage = VecStorage<PlayerMeta>;
}

/// Default spawn position on map1.tmx platforms for a given client id.
fn spawn_pos(id: u32) -> (f32, f32) {
    match (id.saturating_sub(1)) % 4 {
        0 => (12.5, 16.0),
        1 => (18.0, 16.0),
        2 => (7.5, 11.5),
        _ => (22.5, 10.5),
    }
}

fn weapon_damage(weapon_index: u32) -> f32 {
    match weapon_index {
        0 => 20.0, // Pistol
        1 => 18.0, // Shotgun
        2 => 12.0, // Machine Gun
        3 => 55.0, // Sniper
        _ => 20.0,
    }
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
    /// A client requested login (or reconnection).
    Login {
        player_name: String,
        session_token: String,
        tcp_tx: mpsc::UnboundedSender<Vec<u8>>,
        reply: oneshot::Sender<LoginResult>,
    },
    /// A client reported new input/state (player movement & actions).
    State(PlayerStatePacket),
    /// A client fired a weapon (spawns a server-side projectile & broadcasts shot).
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
    tcp: tokio::net::TcpStream,
    msg_tx: mpsc::Sender<ServerMessage>,
) {
    let _ = tcp.set_nodelay(true);
    let remote = tcp.peer_addr().unwrap_or_else(|_| "0.0.0.0:0".parse().unwrap());
    let (mut reader, mut writer) = tcp.into_split();

    let (tcp_tx, mut tcp_rx) = mpsc::unbounded_channel::<Vec<u8>>();

    // Spawn dedicated TCP writer task for this client
    let writer_handle = tokio::spawn(async move {
        while let Some(frame) = tcp_rx.recv().await {
            if writer.write_all(&frame).await.is_err() {
                break;
            }
        }
    });

    let mut stream_buf: Vec<u8> = Vec::new();
    let mut tmp = [0u8; 4096];
    let mut client_id: Option<u32> = None;

    loop {
        let n = match reader.read(&mut tmp).await {
            Ok(0) => break, // peer closed
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
                            tcp_tx: tcp_tx.clone(),
                            reply: reply_tx,
                        })
                        .await
                        .is_err()
                    {
                        writer_handle.abort();
                        return;
                    }
                    let result: LoginResult = match reply_rx.await {
                        Ok(r) => r,
                        Err(_) => {
                            eprintln!("Main loop dropped login reply for {remote}");
                            writer_handle.abort();
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
                    let _ = tcp_tx.send(net::tcp_frame(TYPE_LOGIN_RESPONSE, &resp));
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

    writer_handle.abort();

    // Cleanup — start the reconnection grace window.
    if let Some(id) = client_id {
        eprintln!("Client disconnected: id={id}");
        let _ = msg_tx.send(ServerMessage::Disconnected { id }).await;
    }
}

// ---------------------------------------------------------------------------
// UDP receiver. Learns each client's UDP address and also accepts UDP state/shoot.
// ---------------------------------------------------------------------------
async fn udp_receiver(udp: Arc<UdpSocket>, msg_tx: mpsc::Sender<ServerMessage>) {
    let mut buf = [0u8; 4096];
    loop {
        match udp.recv_from(&mut buf).await {
            Ok((n, addr)) => match net::parse_udp_client(&buf[..n]) {
                Some(net::ClientPacket::Register(client_id)) => {
                    let _ = msg_tx
                        .send(ServerMessage::RegisterUdp {
                            id: client_id,
                            addr,
                        })
                        .await;
                }
                Some(net::ClientPacket::State(pkt)) => {
                    let _ = msg_tx
                        .send(ServerMessage::RegisterUdp {
                            id: pkt.id,
                            addr,
                        })
                        .await;
                    let _ = msg_tx.send(ServerMessage::State(pkt)).await;
                }
                Some(net::ClientPacket::Shoot(pkt)) => {
                    let _ = msg_tx.send(ServerMessage::Shoot(pkt)).await;
                }
                _ => {}
            },
            Err(e) => {
                eprintln!("UDP recv error: {e}");
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Message application to the World.
// ---------------------------------------------------------------------------
#[allow(clippy::too_many_arguments)]
fn handle_message(
    world: &mut World,
    entities: &mut HashMap<u32, Entity>,
    addrs: &mut HashMap<u32, SocketAddr>,
    tcp_senders: &mut HashMap<u32, mpsc::UnboundedSender<Vec<u8>>>,
    projectiles: &mut Vec<Entity>,
    sessions: &mut HashMap<String, Session>,
    token_by_player: &mut HashMap<u32, String>,
    udp: &Arc<UdpSocket>,
    msg: ServerMessage,
    tick: u64,
) {
    match msg {
        ServerMessage::Login {
            player_name,
            session_token,
            tcp_tx,
            reply,
        } => {
            let now = tick;
            // Resume an existing session only if the token is valid AND not currently connected
            // by another active client window.
            let (player_id, token) = if !session_token.is_empty()
                && sessions
                    .get(&session_token)
                    .map(|s| !s.connected)
                    .unwrap_or(false)
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

            let (sx, sy) = spawn_pos(player_id);

            // Create or update the player entity.
            if let Some(&entity) = entities.get(&player_id) {
                let mut metas = world.write::<PlayerMeta>();
                if let Some(meta) = metas.get_mut(entity) {
                    if !player_name.is_empty() {
                        meta.name = player_name.clone();
                    }
                }
            } else {
                let entity = world
                    .create_entity()
                    .with(Position { x: sx, y: sy })
                    .with(Rotation { cos: 0.0, sin: 1.0 })
                    .with(PlayerId { id: player_id })
                    .with(Health {
                        current: START_HEALTH,
                        max: START_HEALTH,
                    })
                    .with(Cooldown { next_tick: 0 })
                    .with(ActionState::default())
                    .with(PlayerMeta {
                        name: if player_name.is_empty() {
                            format!("Player_{player_id}")
                        } else {
                            player_name.clone()
                        },
                        kills: 0,
                        deaths: 0,
                        weapon_index: 0,
                    })
                    .build();
                entities.insert(player_id, entity);
            }

            tcp_senders.insert(player_id, tcp_tx);

            let _ = reply.send(LoginResult {
                id: player_id,
                spawn_x: sx,
                spawn_y: sy,
                session_token: token,
            });

            // Notify all other connected players over TCP that this player joined
            let conn_pkt = PlayerConnectedPacket {
                id: player_id,
                x: sx,
                y: sy,
            };
            let conn_frame = net::tcp_frame(TYPE_PLAYER_CONNECTED, &conn_pkt);
            for (&other_id, tx) in tcp_senders.iter() {
                if other_id != player_id {
                    let _ = tx.send(conn_frame.clone());
                }
            }
        }

        ServerMessage::State(pkt) => {
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
            {
                let mut actions = world.write::<ActionState>();
                if let Some(act) = actions.get_mut(entity) {
                    act.is_attacking = pkt.is_attacking;
                    act.is_reloading = pkt.is_reloading;
                    act.is_throwing = pkt.is_throwing;
                }
            }
            {
                let mut metas = world.write::<PlayerMeta>();
                if let Some(meta) = metas.get_mut(entity) {
                    if !pkt.player_name.is_empty() {
                        meta.name = pkt.player_name;
                    }
                    meta.weapon_index = pkt.weapon_index;
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
                        return;
                    }
                    cd.next_tick = tick + SHOOT_COOLDOWN_TICKS;
                }
                let mut metas = world.write::<PlayerMeta>();
                if let Some(meta) = metas.get_mut(entity) {
                    meta.weapon_index = pkt.weapon_index;
                }
            }

            let (nx, ny) = (pkt.direction_x / mag, pkt.direction_y / mag);
            let dmg = weapon_damage(pkt.weapon_index);

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
                .with(Owner {
                    id: pkt.id,
                    damage: dmg,
                })
                .build();
            projectiles.push(entity);
            eprintln!("Spawned projectile from id={} (weapon={})", pkt.id, pkt.weapon_index);

            // Broadcast PlayerShootPacket to all other clients over TCP (and UDP) so they render the shot!
            let shoot_frame = net::tcp_frame(TYPE_PLAYER_SHOOT, &pkt);
            for (&other_id, tx) in tcp_senders.iter() {
                if other_id != pkt.id {
                    let _ = tx.send(shoot_frame.clone());
                }
            }
            let shoot_udp = net::udp_datagram(TYPE_PLAYER_SHOOT, &pkt);
            for (&other_id, &addr) in addrs.iter() {
                if other_id != pkt.id {
                    let udp_clone = udp.clone();
                    let data = shoot_udp.clone();
                    tokio::spawn(async move {
                        let _ = udp_clone.send_to(&data, addr).await;
                    });
                }
            }
        }

        ServerMessage::Disconnected { id } => {
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
            tcp_senders.remove(&id);

            // Broadcast disconnect to all remaining clients
            let disc_pkt = PlayerDisconnectedPacket { id };
            let disc_frame = net::tcp_frame(TYPE_PLAYER_DISCONNECTED, &disc_pkt);
            for tx in tcp_senders.values() {
                let _ = tx.send(disc_frame.clone());
            }
        }

        ServerMessage::RegisterUdp { id, addr } => {
            if addrs.insert(id, addr) != Some(addr) {
                eprintln!("UDP registration: id={id} addr={addr}");
            }
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
                None => {}
            }
        }
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
    let mut hits: Vec<PlayerHitPacket> = Vec::new();

    let proj_data: Vec<(Entity, u32, f32, f32, f32)> = {
        let positions = world.read::<Position>();
        let owners = world.read::<Owner>();
        let mut out = Vec::new();
        for &e in projectiles.iter() {
            if let (Some(pos), Some(own)) = (positions.get(e), owners.get(e)) {
                out.push((e, own.id, own.damage, pos.x, pos.y));
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

    let mut projectiles_to_delete: Vec<Entity> = Vec::new();
    for &(pe, owner_id, damage, px, py) in proj_data.iter() {
        for &(le, pid, lx, ly) in player_data.iter() {
            if pid == owner_id {
                continue; // a player cannot hit themselves
            }
            let dx = (px - lx).abs();
            let dy = (py - ly).abs();
            if dx <= HIT_HALF_W && dy <= HIT_HALF_H {
                projectiles_to_delete.push(pe);

                let mut is_kill = false;
                let mut new_health = 0.0;
                {
                    let mut healths = world.write::<Health>();
                    if let Some(h) = healths.get_mut(le) {
                        h.current = (h.current - damage).max(0.0);
                        new_health = h.current;
                        if h.current <= 0.0 {
                            is_kill = true;
                            h.current = h.max;
                        }
                    }
                }

                let mut attacker_kills = 0;
                let mut victim_deaths = 0;
                {
                    let mut metas = world.write::<PlayerMeta>();
                    if is_kill {
                        if let Some(&attacker_entity) = entities.get(&owner_id) {
                            if let Some(am) = metas.get_mut(attacker_entity) {
                                am.kills += 1;
                                attacker_kills = am.kills;
                            }
                        }
                        if let Some(vm) = metas.get_mut(le) {
                            vm.deaths += 1;
                            victim_deaths = vm.deaths;
                        }
                    } else {
                        if let Some(&attacker_entity) = entities.get(&owner_id) {
                            if let Some(am) = metas.get(attacker_entity) {
                                attacker_kills = am.kills;
                            }
                        }
                        if let Some(vm) = metas.get(le) {
                            victim_deaths = vm.deaths;
                        }
                    }
                }

                if is_kill {
                    let (sx, sy) = spawn_pos(pid);
                    let mut positions = world.write::<Position>();
                    if let Some(pos) = positions.get_mut(le) {
                        pos.x = sx;
                        pos.y = sy;
                    }
                    eprintln!(
                        "KILL: id={} eliminated id={} (kills={}, deaths={}); respawned at ({}, {})",
                        owner_id, pid, attacker_kills, victim_deaths, sx, sy
                    );
                } else {
                    eprintln!(
                        "HIT: id={} hit id={} for {} dmg (health now {})",
                        owner_id, pid, damage, new_health
                    );
                }

                hits.push(PlayerHitPacket {
                    from_id: owner_id,
                    to_id: pid,
                    damage,
                    x: lx,
                    y: ly,
                    target_health: new_health,
                    is_kill,
                    attacker_kills,
                    victim_deaths,
                });
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
    is_attacking: bool,
    is_reloading: bool,
    is_throwing: bool,
    health: f32,
    kills: u32,
    deaths: u32,
    player_name: String,
    weapon_index: u32,
}

/// Collect every player's authoritative state for this tick.
fn build_player_snapshot(world: &World) -> Vec<PlayerSnapshot> {
    let player_ids = world.read::<PlayerId>();
    let positions = world.read::<Position>();
    let rotations = world.read::<Rotation>();
    let actions = world.read::<ActionState>();
    let healths = world.read::<Health>();
    let metas = world.read::<PlayerMeta>();
    (&player_ids, &positions, &rotations, &actions, &healths, &metas)
        .join()
        .map(|(pid, pos, rot, act, h, meta)| PlayerSnapshot {
            id: pid.id,
            x: pos.x,
            y: pos.y,
            cos: rot.cos,
            sin: rot.sin,
            is_attacking: act.is_attacking,
            is_reloading: act.is_reloading,
            is_throwing: act.is_throwing,
            health: h.current,
            kills: meta.kills,
            deaths: meta.deaths,
            player_name: meta.name.clone(),
            weapon_index: meta.weapon_index,
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
// Broadcast: send each client the state of entities within VIEW_RADIUS.
// Uses UDP every tick (60 Hz) and TCP every 2nd tick (30 Hz) so clients behind
// strict NAT/firewalls/emulators still receive smooth state updates.
// ---------------------------------------------------------------------------
async fn broadcast(
    snapshot: &[PlayerSnapshot],
    addrs: &HashMap<u32, SocketAddr>,
    tcp_senders: &HashMap<u32, mpsc::UnboundedSender<Vec<u8>>>,
    udp: &UdpSocket,
    tick: u64,
) {
    let packets: Vec<PlayerStatePacket> = snapshot
        .iter()
        .map(|p| PlayerStatePacket {
            id: p.id,
            x: p.x,
            y: p.y,
            cos: p.cos,
            sin: p.sin,
            is_attacking: p.is_attacking,
            is_reloading: p.is_reloading,
            is_throwing: p.is_throwing,
            health: p.health,
            kills: p.kills,
            deaths: p.deaths,
            player_name: p.player_name.clone(),
            weapon_index: p.weapon_index,
        })
        .collect();

    // 1. UDP broadcast (60 Hz)
    for (&client_id, &addr) in addrs.iter() {
        let (ox, oy) = snapshot_pos(snapshot, client_id).unwrap_or((0.0, 0.0));
        for pkt in packets.iter() {
            let dx = pkt.x - ox;
            let dy = pkt.y - oy;
            if dx * dx + dy * dy > VIEW_RADIUS_SQ {
                continue;
            }
            let bytes = net::udp_datagram(TYPE_PLAYER_STATE, pkt);
            let _ = udp.send_to(&bytes, addr).await;
        }
    }

    // 2. TCP broadcast (30 Hz - guarantees delivery even if UDP is blocked by NAT/Emulator)
    if tick % 2 == 0 {
        for (&client_id, tx) in tcp_senders.iter() {
            let (ox, oy) = snapshot_pos(snapshot, client_id).unwrap_or((0.0, 0.0));
            for pkt in packets.iter() {
                let dx = pkt.x - ox;
                let dy = pkt.y - oy;
                if dx * dx + dy * dy > VIEW_RADIUS_SQ {
                    continue;
                }
                let frame = net::tcp_frame(TYPE_PLAYER_STATE, pkt);
                let _ = tx.send(frame);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Broadcast hit events over both TCP (reliable) and UDP (low-latency).
// ---------------------------------------------------------------------------
async fn broadcast_hits(
    hits: &[PlayerHitPacket],
    snapshot: &[PlayerSnapshot],
    addrs: &HashMap<u32, SocketAddr>,
    tcp_senders: &HashMap<u32, mpsc::UnboundedSender<Vec<u8>>>,
    udp: &UdpSocket,
) {
    for hit in hits {
        let udp_bytes = net::udp_datagram(TYPE_PLAYER_HIT, hit);
        let tcp_bytes = net::tcp_frame(TYPE_PLAYER_HIT, hit);

        for (&client_id, &addr) in addrs.iter() {
            let involved = client_id == hit.from_id || client_id == hit.to_id;
            let nearby = snapshot_pos(snapshot, client_id)
                .map(|(px, py)| {
                    let dx = px - hit.x;
                    let dy = py - hit.y;
                    dx * dx + dy * dy <= VIEW_RADIUS_SQ
                })
                .unwrap_or(true);
            if involved || nearby {
                let _ = udp.send_to(&udp_bytes, addr).await;
            }
        }

        for tx in tcp_senders.values() {
            let _ = tx.send(tcp_bytes.clone());
        }
    }
}

// ---------------------------------------------------------------------------
// Main game loop.
// ---------------------------------------------------------------------------
#[tokio::main]
async fn main() {
    let tcp_port: u16 = std::env::var("SHOOTGAME_TCP_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(54555);
    let udp_port: u16 = std::env::var("SHOOTGAME_UDP_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(54777);

    let tcp_listener = TcpListener::bind(("0.0.0.0", tcp_port))
        .await
        .expect("TCP listener bind failed");
    let udp_socket = Arc::new(
        UdpSocket::bind(("0.0.0.0", udp_port))
            .await
            .expect("UDP socket bind failed"),
    );
    eprintln!("ShootGame Server (hardened) listening on TCP:{tcp_port}, UDP:{udp_port}");

    let mut world = World::new();
    world.register::<Position>();
    world.register::<Rotation>();
    world.register::<Velocity>();
    world.register::<Lifetime>();
    world.register::<PlayerId>();
    world.register::<Health>();
    world.register::<Owner>();
    world.register::<Cooldown>();
    world.register::<ActionState>();
    world.register::<PlayerMeta>();

    let mut entities: HashMap<u32, Entity> = HashMap::new();
    let mut addrs: HashMap<u32, SocketAddr> = HashMap::new();
    let mut tcp_senders: HashMap<u32, mpsc::UnboundedSender<Vec<u8>>> = HashMap::new();
    let mut projectiles: Vec<Entity> = Vec::new();
    let mut sessions: HashMap<String, Session> = HashMap::new();
    let mut token_by_player: HashMap<u32, String> = HashMap::new();
    let mut tick: u64 = 0;

    let (msg_tx, mut msg_rx) = mpsc::channel::<ServerMessage>(512);

    {
        let udp = udp_socket.clone();
        let msg_tx = msg_tx.clone();
        tokio::spawn(async move {
            udp_receiver(udp, msg_tx).await;
        });
    }

    let mut ticker = interval(Duration::from_millis(16));
    ticker.set_missed_tick_behavior(MissedTickBehavior::Skip);

    loop {
        tokio::select! {
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

            maybe = msg_rx.recv() => {
                match maybe {
                    Some(msg) => handle_message(
                        &mut world,
                        &mut entities,
                        &mut addrs,
                        &mut tcp_senders,
                        &mut projectiles,
                        &mut sessions,
                        &mut token_by_player,
                        &udp_socket,
                        msg,
                        tick,
                    ),
                    None => {
                        eprintln!("All channel senders dropped; shutting down.");
                        break;
                    }
                }
            }

            _ = ticker.tick() => {
                tick += 1;
                prune_sessions(&mut sessions, &mut token_by_player, tick);
                let hits = run_systems(&mut world, &entities, &mut projectiles);
                let snapshot = build_player_snapshot(&world);
                broadcast(&snapshot, &addrs, &tcp_senders, &udp_socket, tick).await;
                broadcast_hits(&hits, &snapshot, &addrs, &tcp_senders, &udp_socket).await;
            }
        }
    }
}
