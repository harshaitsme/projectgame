/// Phase 6 integration test: combat + session reconnection, over the new
/// Protobuf-framed wire protocol (prost).
use std::io::Write;
use std::net::{TcpStream, UdpSocket};
use std::time::Duration;
use shootgame_server::network as net;
use net::{LoginPacket, LoginResponsePacket, PlayerShootPacket};

fn connect_and_login(name: &str, token: &str) -> (TcpStream, u32, String) {
    let mut stream = TcpStream::connect("127.0.0.1:54555").expect("connect failed");
    stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();

    // Send framed LoginPacket over TCP.
    let login = LoginPacket {
        player_name: name.into(),
        session_token: token.into(),
    };
    let frame = net::tcp_frame(net::TYPE_LOGIN, &login);
    stream.write_all(&frame).unwrap();

    // Read framed LoginResponsePacket.
    let resp: LoginResponsePacket = net::read_login_response(&mut stream).expect("login response");
    println!(
        "{name}: id={}, spawn=({}, {}), token={}",
        resp.assigned_id, resp.spawn_x, resp.spawn_y, resp.session_token
    );
    (stream, resp.assigned_id, resp.session_token)
}

fn register_udp(id: u32) -> UdpSocket {
    let udp = UdpSocket::bind("0.0.0.0:0").expect("udp bind failed");
    udp.send_to(&net::register_datagram(id), "127.0.0.1:54777")
        .expect("reg failed");
    udp.set_read_timeout(Some(Duration::from_millis(500))).unwrap();
    udp
}

fn main() {
    // 1. Client A logs in (id=1, spawn 4.5,4).
    let (mut a_stream, a_id, _a_token) = connect_and_login("Alpha", "");
    let _a_udp = register_udp(a_id);

    // 2. Client B logs in (id=2, spawn 6,4).
    let (b_stream, b_id, b_token) = connect_and_login("Bravo", "");
    let b_udp = register_udp(b_id);

    // 3. A fires toward +x at B. Send on A's stream so the server stamps owner=A.
    let shoot = PlayerShootPacket {
        id: a_id,
        weapon_index: 0,
        origin_x: 4.5,
        origin_y: 4.0,
        direction_x: 1.0,
        direction_y: 0.0,
    };
    let frame = net::tcp_frame(net::TYPE_PLAYER_SHOOT, &shoot);
    a_stream.write_all(&frame).unwrap();
    println!("A fired at B (origin 4.5,4 dir +x)");

    // 4. B listens on UDP for a PlayerHitPacket addressed to it.
    let mut ubuf = [0u8; 4096];
    let mut got_hit = false;
    for _ in 0..20 {
        match b_udp.recv_from(&mut ubuf) {
            Ok((n, _)) => match net::parse_udp_server(&ubuf[..n]) {
                Some(net::UdpPacket::Hit(hit)) => {
                    println!(
                        "B received HIT: from={} to={} dmg={} at ({},{})",
                        hit.from_id, hit.to_id, hit.damage, hit.x, hit.y
                    );
                    if hit.to_id == b_id && hit.from_id == a_id {
                        got_hit = true;
                        break;
                    }
                }
                _ => continue,
            },
            _ => continue,
        }
    }
    assert!(got_hit, "B did not receive the expected PlayerHitPacket");
    println!("✅ combat test PASSED");

    // 5. Reconnection: B drops, then rejoins with its session token.
    drop(b_stream);
    std::thread::sleep(Duration::from_millis(200));
    let (re_stream, re_id, re_token) = connect_and_login("Bravo-reconnect", &b_token);
    println!("B reconnected: id={re_id}");
    assert_eq!(re_id, b_id, "reconnect should resume the same id");
    assert_eq!(re_token, b_token, "reconnect should echo the same token");
    println!("✅ reconnect test PASSED (resumed id={re_id})");

    // Keep streams alive briefly so the server logs the disconnects.
    drop(_a_udp);
    drop(re_stream);
    drop(a_stream);
}
