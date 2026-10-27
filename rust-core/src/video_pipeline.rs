//! 来源视频必须以本来源的 SPS/PPS/IDR 开始，不能沿用等待页的解码状态。
use std::collections::VecDeque;
use crate::screen::{annexb_nals, nal_type};

const MAX_BUFFER_BYTES: usize = 8 * 1024 * 1024;
const MAX_BUFFER_FRAMES: usize = 240;

pub struct SourceVideoBuffer {
    pub size: (u32, u32),
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    ready: bool,
    pending: VecDeque<Vec<u8>>,
    bytes: usize,
}

impl SourceVideoBuffer {
    pub fn new(width: u32, height: u32) -> Self {
        Self { size: (width, height), sps: None, pps: None, ready: false, pending: VecDeque::new(), bytes: 0 }
    }

    pub fn set_size(&mut self, width: u32, height: u32) {
        let next = (width / 2 * 2, height / 2 * 2);
        if next.0 >= 2 && next.1 >= 2 && next != self.size {
            self.size = next;
            self.require_keyframe();
            self.sps = None;
            self.pps = None;
        }
    }

    pub fn require_keyframe(&mut self) {
        self.ready = false;
        self.pending.clear();
        self.bytes = 0;
    }

    /// 返回 false 表示本帧等待关键帧或缓冲超限，不能计为已发送帧。
    pub fn push(&mut self, data: Vec<u8>) -> bool {
        let nals = annexb_nals(&data);
        let sps = nals.iter().find(|n| nal_type(n) == 7).map(|n| n.to_vec());
        let pps = nals.iter().find(|n| nal_type(n) == 8).map(|n| n.to_vec());
        let idr = nals.iter().any(|n| nal_type(n) == 5);
        let changed = sps.as_ref().is_some_and(|n| self.sps.as_ref() != Some(n))
            || pps.as_ref().is_some_and(|n| self.pps.as_ref() != Some(n));
        if changed { self.require_keyframe(); }
        if let Some(sps) = sps { self.sps = Some(sps); }
        if let Some(pps) = pps { self.pps = Some(pps); }
        let packet = if idr {
            let (Some(sps), Some(pps)) = (&self.sps, &self.pps) else { return false; };
            // 保留最新可独立解码的 GOP，首帧早于车机就绪时也不丢关键参数。
            let mut packet = Vec::with_capacity(data.len() + sps.len() + pps.len() + 8);
            for nal in [sps.as_slice(), pps.as_slice()] {
                packet.extend_from_slice(&[0, 0, 0, 1]); packet.extend_from_slice(nal);
            }
            for nal in &nals {
                if !matches!(nal_type(nal), 7 | 8) {
                    packet.extend_from_slice(&[0, 0, 0, 1]); packet.extend_from_slice(nal);
                }
            }
            self.pending.clear(); self.bytes = 0; self.ready = true;
            packet
        } else {
            if !self.ready || !nals.iter().any(|n| nal_type(n) == 1) { return false; }
            data
        };
        if self.bytes + packet.len() > MAX_BUFFER_BYTES || self.pending.len() >= MAX_BUFFER_FRAMES {
            self.require_keyframe();
            return false;
        }
        self.bytes += packet.len(); self.pending.push_back(packet);
        true
    }

    pub fn pop(&mut self) -> Option<Vec<u8>> {
        self.pending.pop_front().inspect(|packet| self.bytes -= packet.len())
    }

    pub fn has_pending(&self) -> bool { !self.pending.is_empty() }
}

/// 所有视频（等待页与真实来源）与计时服务共用本机单调时间。
#[derive(Default)]
pub struct FrameClock { last_us: Option<u64> }

impl FrameClock {
    pub fn next(&mut self, now_us: u64) -> u64 {
        let next = self.last_us.map(|last| now_us.max(last.saturating_add(1))).unwrap_or(now_us);
        self.last_us = Some(next);
        next
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn keyframe() -> Vec<u8> { vec![0,0,0,1,0x67,0x42,0,0x1e,0,0,0,1,0x68,0xce,0,0,0,1,0x65,0x88] }
    fn pframe() -> Vec<u8> { vec![0,0,0,1,0x41,0x9a] }

    #[test]
    fn early_keyframe_and_reference_frames_survive_until_screen_ready() {
        let mut buffer = SourceVideoBuffer::new(1920,720);
        assert!(buffer.push(keyframe()));
        assert!(buffer.push(pframe()));
        let types: Vec<_> = annexb_nals(&buffer.pop().unwrap()).iter().map(|n| nal_type(n)).collect();
        assert_eq!(types, [7,8,5]);
        assert_eq!(buffer.pop().unwrap(), pframe());
        assert!(!buffer.has_pending());
    }

    #[test]
    fn returning_from_standby_requires_source_idr_and_restores_source_parameters() {
        let mut buffer = SourceVideoBuffer::new(1920,720);
        buffer.push(keyframe()); buffer.pop();
        buffer.require_keyframe();
        assert!(!buffer.push(pframe()));
        assert!(!buffer.has_pending());
        assert!(buffer.push(vec![0,0,0,1,0x65,0x99]));
        assert_eq!(buffer.size, (1920,720));
        let types: Vec<_> = annexb_nals(&buffer.pop().unwrap()).iter().map(|n| nal_type(n)).collect();
        assert_eq!(types, [7,8,5]);
    }

    #[test]
    fn source_epoch_or_repeated_timestamps_cannot_shift_the_output_clock() {
        let mut clock=FrameClock::default();
        // 21:08 来源字段 1793106514 不进入输出时钟；这里输入同一计时服务的当前值。
        assert_eq!(clock.next(100_000),100_000);
        assert_eq!(clock.next(100_000),100_001);
        assert_eq!(clock.next(99_000),100_002);
        assert_eq!(clock.next(133_333),133_333);
    }

    #[test]
    fn overflow_or_resolution_change_waits_for_an_independently_decodable_gop() {
        let mut buffer = SourceVideoBuffer::new(1920, 720);
        assert!(buffer.push(keyframe()));
        for _ in 1..MAX_BUFFER_FRAMES { assert!(buffer.push(pframe())); }
        assert!(!buffer.push(pframe()));
        assert!(!buffer.has_pending());
        assert!(!buffer.push(pframe()));
        assert!(buffer.push(keyframe()));
        buffer.set_size(1280, 720);
        assert!(!buffer.push(vec![0, 0, 0, 1, 0x65, 0x99]));
        assert!(!buffer.has_pending());
        assert!(buffer.push(keyframe()));
    }
}
