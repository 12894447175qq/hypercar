use super::*;

#[tokio::test]
async fn late_port_conflict_rolls_back_every_earlier_listener() {
    let mut reservations: Vec<_> = (0..6).map(|_| std::net::TcpListener::bind("0.0.0.0:0").unwrap()).collect();
    let ports: [u16; 6] = reservations.iter().map(|l| l.local_addr().unwrap().port()).collect::<Vec<_>>().try_into().unwrap();
    let occupied = reservations.pop().unwrap();
    drop(reservations);
    let error = reserve_listener_set(ports).err().expect("foreign listener must not be adopted");
    assert!(error.contains(&ports[5].to_string()));
    for port in &ports[..5] {
        let rebound = std::net::TcpListener::bind(("0.0.0.0", *port));
        assert!(rebound.is_ok(), "partial gateway leaked port {port}");
    }
    assert_eq!(occupied.local_addr().unwrap().port(), ports[5]);
}

#[tokio::test]
async fn complete_reservation_closes_cleanly_and_can_restart() {
    let listeners = reserve_listener_set([0; 6]).unwrap();
    let ports: [u16; 6] = listeners.iter().map(|l| l.local_addr().unwrap().port()).collect::<Vec<_>>().try_into().unwrap();
    drop(listeners);
    let restarted = reserve_listener_set(ports).unwrap();
    drop(restarted);
}

#[test]
fn older_configs_keep_source_enabled_and_transport_only_is_explicit() {
    let json = r#"{"device_id":"test","car_ip":"127.0.0.1","airplay_port":7000,"width":640,"height":480,"fps":30,"identity_path":"test"}"#;
    let previous: crate::engine::EngineConfig = serde_json::from_str(json).unwrap();
    assert!(previous.carlife_enabled);
    let explicit = json.replace("\"fps\":30", "\"fps\":30,\"carlife_enabled\":false");
    let only_transport: crate::engine::EngineConfig = serde_json::from_str(&explicit).unwrap();
    assert!(!only_transport.carlife_enabled);
}
