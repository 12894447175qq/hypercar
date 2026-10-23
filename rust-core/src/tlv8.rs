//! HomeKit/AirPlay 风格 TLV8 编解码（cardeck `Tlv8.kt` 的 Rust 移植）。
//!
//! 协议依据：pair-setup / pair-verify 的请求与应答体都是 TLV8——
//! 每个条目为 `[type:1][length:1][value:length]`，单个条目长度上限 255，
//! 超长值按 255 字节分片连续输出；**连续两个同 type 条目之间必须插一个
//! 0xFF 分隔符**（接收端靠它区分「分片延续」和「新的同类型条目」）。
//! 解码端做分片重组：上一条目同 type 且长度恰为 255 时，本条值追加到
//! 上一条之后；否则直接覆盖该 type 的旧值。

/// 条目类型：Method。
pub const TYPE_METHOD: u8 = 0x00;
/// 条目类型：Identifier（配对 ID，UTF-8）。
pub const TYPE_IDENTIFIER: u8 = 0x01;
/// 条目类型：Salt（SRP 盐）。
pub const TYPE_SALT: u8 = 0x02;
/// 条目类型：PublicKey（SRP B/A、X25519 临时公钥或长期 ed25519 公钥）。
pub const TYPE_PUBLIC_KEY: u8 = 0x03;
/// 条目类型：Proof（SRP M1/HAMK）。
pub const TYPE_PROOF: u8 = 0x04;
/// 条目类型：EncryptedData（ChaCha20-Poly1305 信封）。
pub const TYPE_ENCRYPTED_DATA: u8 = 0x05;
/// 条目类型：State（消息序号，M1=1、M2=2 …）。
pub const TYPE_STATE: u8 = 0x06;
/// 条目类型：Error（错误码）。
pub const TYPE_ERROR: u8 = 0x07;
/// 条目类型：Signature（ed25519 签名）。
pub const TYPE_SIGNATURE: u8 = 0x0A;

const SEPARATOR_TYPE: u8 = 0xFF;
const MAX_FRAGMENT: usize = 255;

/// 一个 TLV8 条目（类型 + 原始值）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Item {
    pub t: u8,
    pub v: Vec<u8>,
}

/// TLV8 编码：语义同 `Tlv8.kt.encode`——值超过 255 字节按 255 分片，
/// 相邻同 type 条目前插 `[0xFF, 0x00]` 分隔符。
pub fn encode(items: &[Item]) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::new();
    let mut previous_type: Option<u8> = None;
    for item in items {
        if previous_type == Some(item.t) {
            out.push(SEPARATOR_TYPE);
            out.push(0);
        }
        let mut offset = 0usize;
        // do-while：空值也要输出一条 `[type, 0]`（与 Kotlin 版逐字节一致）
        loop {
            let length = (item.v.len() - offset).min(MAX_FRAGMENT);
            out.push(item.t);
            out.push(length as u8);
            out.extend_from_slice(&item.v[offset..offset + length]);
            offset += length;
            if offset >= item.v.len() {
                break;
            }
        }
        previous_type = Some(item.t);
    }
    out
}

/// TLV8 解码：语义同 `Tlv8.kt.decode`——上一条目同 type 且长度为 255 时
/// 视为同一值的分片，做拼接重组；尾部残缺条目（不足 2 字节或值越界）直接丢弃。
pub fn decode(bytes: &[u8]) -> std::collections::HashMap<u8, Vec<u8>> {
    let mut output: std::collections::HashMap<u8, Vec<u8>> = std::collections::HashMap::new();
    let mut position = 0usize;
    let mut last_type: Option<u8> = None;
    let mut last_length = 0usize;
    while position + 2 <= bytes.len() {
        let item_type = bytes[position];
        let length = bytes[position + 1] as usize;
        position += 2;
        if position + length > bytes.len() {
            break;
        }
        let value = bytes[position..position + length].to_vec();
        position += length;
        if last_type == Some(item_type) && last_length == MAX_FRAGMENT {
            output.entry(item_type).or_default().extend_from_slice(&value);
        } else {
            output.insert(item_type, value);
        }
        last_type = Some(item_type);
        last_length = length;
    }
    output
}
