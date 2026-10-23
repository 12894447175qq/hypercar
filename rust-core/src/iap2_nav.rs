//! 宝马 HUD 抬头显示导航报文（iAP2 0x5200 TLV）构造器。
//!
//! 报文经 Kotlin 侧 iAP2 会话（事件通道 iAPSendMessage / 蓝牙控制会话）发给车机，
//! 车机把导航引导渲染到 HUD 与仪表。格式：[u16 BE 总长][u16 BE msgId=0x5200][TLV 参数]，
//! 每个参数 = [u16 LE 参数长(含4B头)][u16 LE 参数ID][数据]。
//!
//! 参数 ID：1= maneuver 类型、2= 距转向点米、3= 单位(0=公制)、4= 当前街道、
//! 5= 下一街道、6= 剩余秒、7= 剩余米。

pub fn build_route_guidance_packet(
    maneuver_type: u8,
    current_street: &str,
    next_street: &str,
    dist_to_maneuver_m: u32,
    remain_time_s: u32,
    remain_dist_m: u32,
) -> Vec<u8> {
    let mut params = Vec::with_capacity(128);

    write_tlv(&mut params, 1, &[maneuver_type]);
    write_tlv(&mut params, 2, &dist_to_maneuver_m.to_le_bytes());
    write_tlv(&mut params, 3, &[0u8]); // 0 = 公制（米/公里）
    write_tlv(&mut params, 4, current_street.as_bytes());
    write_tlv(&mut params, 5, next_street.as_bytes());
    write_tlv(&mut params, 6, &remain_time_s.to_le_bytes());
    write_tlv(&mut params, 7, &remain_dist_m.to_le_bytes());

    let total_len = (4 + params.len()) as u16;
    let msg_id: u16 = 0x5200;

    let mut packet = Vec::with_capacity(total_len as usize);
    packet.extend_from_slice(&total_len.to_be_bytes());
    packet.extend_from_slice(&msg_id.to_be_bytes());
    packet.extend_from_slice(&params);
    packet
}

#[inline]
fn write_tlv(out: &mut Vec<u8>, param_id: u16, data: &[u8]) {
    let len = (4 + data.len()) as u16;
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(&param_id.to_le_bytes());
    out.extend_from_slice(data);
}
