//! 宝马 HUD 抬头显示导航报文（iAP2 0x5200 TLV）构造器。
//!
//! 报文经 Kotlin 侧 iAP2 会话（事件通道 iAPSendMessage / 蓝牙控制会话）发给车机，
//! 车机把导航引导渲染到 HUD 与仪表。
//!
//! **格式必须与 Kotlin 端 `Iap2Csm` 严格一致**（2026-10-25 修正）：
//!   - CSM 帧头 6 字节：`0x40 0x40 | u16 BE 总长(含6字节头) | u16 BE msgId`
//!     （旧实现只有 4 字节 `[总长][msgId]`，漏了 0x4040 同步字，车机会当垃圾丢弃）；
//!   - 每个 TLV 参数：`u16 BE 参数总长(含4字节头) | u16 BE 参数ID | 数据`
//!     （旧实现用了 **Little-Endian**，与 Iap2Csm 的大端相反）；
//!   - 整数一律 **大端**（`to_be_bytes`）；字符串按 iAP2 惯例 **NUL 结尾**。
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
    write_tlv(&mut params, 2, &dist_to_maneuver_m.to_be_bytes());
    write_tlv(&mut params, 3, &[0u8]); // 0 = 公制（米/公里）
    write_tlv(&mut params, 4, &nul_terminated(current_street));
    write_tlv(&mut params, 5, &nul_terminated(next_street));
    write_tlv(&mut params, 6, &remain_time_s.to_be_bytes());
    write_tlv(&mut params, 7, &remain_dist_m.to_be_bytes());

    // iAP2 CSM 标准 6 字节头：0x4040 | u16 BE total_len | u16 BE msg_id
    let total_len = (6 + params.len()) as u16;
    let msg_id: u16 = 0x5200;

    let mut packet = Vec::with_capacity(total_len as usize);
    packet.extend_from_slice(&0x4040u16.to_be_bytes());
    packet.extend_from_slice(&total_len.to_be_bytes());
    packet.extend_from_slice(&msg_id.to_be_bytes());
    packet.extend_from_slice(&params);
    packet
}

#[inline]
fn nul_terminated(s: &str) -> Vec<u8> {
    let mut b = s.as_bytes().to_vec();
    b.push(0);
    b
}

#[inline]
fn write_tlv(out: &mut Vec<u8>, param_id: u16, data: &[u8]) {
    let len = (4 + data.len()) as u16;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(&param_id.to_be_bytes());
    out.extend_from_slice(data);
}
