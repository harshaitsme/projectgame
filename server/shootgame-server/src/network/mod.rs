// Wire protocol codec for ShootGame.
//
// Uses prost-generated types (see generated.rs, built from proto/shootgame.proto).
//
// Framing:
//   TCP:  [u32 len LE][u8 type][protobuf body]   (len = 1 + body.len())
//   UDP:  [u8 type][protobuf body]               (datagram boundary delimits)
//
// The 1-byte `type` tag lets a receiver disambiguate packet kinds on a single
// TCP stream / single UDP socket.
pub mod generated;
pub use generated::*;

use prost::Message;

// ---------------------------------------------------------------------------
// Wire type tags.
// ---------------------------------------------------------------------------
pub const TYPE_LOGIN: u8 = 1;
pub const TYPE_LOGIN_RESPONSE: u8 = 2;
pub const TYPE_PLAYER_STATE: u8 = 3;
pub const TYPE_PLAYER_SHOOT: u8 = 4;
pub const TYPE_PLAYER_HIT: u8 = 5;
pub const TYPE_PLAYER_CONNECTED: u8 = 6;
pub const TYPE_PLAYER_DISCONNECTED: u8 = 7;
pub const TYPE_REGISTER: u8 = 8;

// ---------------------------------------------------------------------------
// Parsed packet kinds.
// ---------------------------------------------------------------------------

/// A message received from a client (over TCP frames or UDP datagrams).
#[derive(Debug)]
pub enum ClientPacket {
    Login(LoginPacket),
    State(PlayerStatePacket),
    Shoot(PlayerShootPacket),
    /// UDP registration: the client's logical id (teaches us its UDP address).
    Register(u32),
}

/// A datagram the server sends to a client over UDP.
#[derive(Debug)]
pub enum UdpPacket {
    State(PlayerStatePacket),
    Hit(PlayerHitPacket),
    Connected(PlayerConnectedPacket),
    Disconnected(PlayerDisconnectedPacket),
}

// ---------------------------------------------------------------------------
// Encoding helpers.
// ---------------------------------------------------------------------------

/// Encode just the protobuf body (no framing).
pub fn body<M: Message>(m: &M) -> Vec<u8> {
    let mut v = Vec::with_capacity(m.encoded_len());
    m.encode(&mut v).expect("prost encode");
    v
}

/// TCP frame: [len][type][body], len = 1 + body.len().
pub fn tcp_frame<M: Message>(t: u8, m: &M) -> Vec<u8> {
    let b = body(m);
    let len = (1 + b.len()) as u32;
    let mut out = Vec::with_capacity(4 + 1 + b.len());
    out.extend_from_slice(&len.to_le_bytes());
    out.push(t);
    out.extend_from_slice(&b);
    out
}

/// UDP datagram: [type][body].
pub fn udp_datagram<M: Message>(t: u8, m: &M) -> Vec<u8> {
    let b = body(m);
    let mut out = Vec::with_capacity(1 + b.len());
    out.push(t);
    out.extend_from_slice(&b);
    out
}

/// UDP registration datagram the client sends to teach the server its addr.
pub fn register_datagram(id: u32) -> Vec<u8> {
    let mut v = Vec::with_capacity(5);
    v.push(TYPE_REGISTER);
    v.extend_from_slice(&id.to_le_bytes());
    v
}

// ---------------------------------------------------------------------------
// Decoding helpers.
// ---------------------------------------------------------------------------

/// Decode a typed frame body `[type][body]` into a client packet.
fn decode_client_typed(buf: &[u8]) -> Option<ClientPacket> {
    let (&t, payload) = buf.split_first()?;
    match t {
        TYPE_LOGIN => LoginPacket::decode(payload).ok().map(ClientPacket::Login),
        TYPE_PLAYER_STATE => PlayerStatePacket::decode(payload).ok().map(ClientPacket::State),
        TYPE_PLAYER_SHOOT => PlayerShootPacket::decode(payload).ok().map(ClientPacket::Shoot),
        _ => None,
    }
}

/// Parse all complete TCP frames in `buf`.
/// Returns the packets and the number of bytes consumed; keep `buf[consumed..]`.
pub fn parse_tcp(buf: &[u8]) -> (Vec<ClientPacket>, usize) {
    let mut out = Vec::new();
    let mut off = 0usize;
    while buf.len() - off >= 4 {
        let len = u32::from_le_bytes([buf[off], buf[off + 1], buf[off + 2], buf[off + 3]])
            as usize;
        if buf.len() - off < 4 + len {
            break; // incomplete frame; wait for more bytes
        }
        let frame = &buf[off + 4..off + 4 + len];
        if let Some(p) = decode_client_typed(frame) {
            out.push(p);
        }
        off += 4 + len;
    }
    (out, off)
}

/// Parse a single UDP datagram sent by a client.
pub fn parse_udp_client(buf: &[u8]) -> Option<ClientPacket> {
    let (&t, payload) = buf.split_first()?;
    match t {
        TYPE_REGISTER => {
            if payload.len() >= 4 {
                let id =
                    u32::from_le_bytes([payload[0], payload[1], payload[2], payload[3]]);
                Some(ClientPacket::Register(id))
            } else {
                None
            }
        }
        TYPE_PLAYER_STATE => PlayerStatePacket::decode(payload).ok().map(ClientPacket::State),
        TYPE_PLAYER_SHOOT => PlayerShootPacket::decode(payload).ok().map(ClientPacket::Shoot),
        _ => None,
    }
}

/// Decode a server->client TCP frame (`[type][body]`) — only LoginResponse.
pub fn decode_login_response(frame_body: &[u8]) -> Option<LoginResponsePacket> {
    let (&t, payload) = frame_body.split_first()?;
    if t == TYPE_LOGIN_RESPONSE {
        LoginResponsePacket::decode(payload).ok()
    } else {
        None
    }
}

/// Read one length-prefixed TCP frame from `reader` and parse it as a
/// LoginResponse. Used by the client to complete the login hand-shake.
pub fn read_login_response<R: std::io::Read>(reader: &mut R) -> Option<LoginResponsePacket> {
    let mut len_buf = [0u8; 4];
    reader.read_exact(&mut len_buf).ok()?;
    let len = u32::from_le_bytes(len_buf) as usize;
    let mut frame = vec![0u8; len];
    reader.read_exact(&mut frame).ok()?;
    decode_login_response(&frame)
}

/// Parse a single UDP datagram sent by the server.
pub fn parse_udp_server(buf: &[u8]) -> Option<UdpPacket> {
    let (&t, payload) = buf.split_first()?;
    match t {
        TYPE_PLAYER_STATE => PlayerStatePacket::decode(payload).ok().map(UdpPacket::State),
        TYPE_PLAYER_HIT => PlayerHitPacket::decode(payload).ok().map(UdpPacket::Hit),
        TYPE_PLAYER_CONNECTED => {
            PlayerConnectedPacket::decode(payload).ok().map(UdpPacket::Connected)
        }
        TYPE_PLAYER_DISCONNECTED => {
            PlayerDisconnectedPacket::decode(payload).ok().map(UdpPacket::Disconnected)
        }
        _ => None,
    }
}
