//! Bounded PCM buffering and a wall-clock RTP timeline. Never replay overdue timer ticks in a burst.
use crate::audio_out::SAMPLES_PER_PACKET;
use std::collections::VecDeque;

const RATE: u64 = 48_000;
pub const PERIOD_NS: u64 = SAMPLES_PER_PACKET as u64 * 1_000_000_000 / RATE;

pub struct AudioClock {
    origin: u64,
    next_slot: u64,
}
impl AudioClock {
    pub fn new(now: u64) -> Self {
        Self {
            origin: now,
            next_slot: 0,
        }
    }
    pub fn rtp_at(&self, now: u64) -> u32 {
        ((now.saturating_sub(self.origin) / PERIOD_NS) * SAMPLES_PER_PACKET as u64) as u32
    }
    pub fn due(&mut self, now: u64) -> Option<u64> {
        let slot = now.saturating_sub(self.origin) / PERIOD_NS;
        if slot < self.next_slot {
            return None;
        }
        let skipped = slot - self.next_slot;
        self.next_slot = slot + 1;
        Some(skipped * SAMPLES_PER_PACKET as u64)
    }
}

pub struct AudioBuffer {
    pub queue: VecDeque<i16>,
    prebuffer_frames: usize,
    ready: bool,
    active: bool,
    pub input_frames: u64,
    pub dropped_frames: u64,
    pub padded_frames: u64,
    pub underruns: u64,
    pub max_input_gap_ms: u64,
    last_input: Option<u64>,
}
impl AudioBuffer {
    pub fn new(prebuffer_ms: usize) -> Self {
        Self {
            queue: VecDeque::new(),
            prebuffer_frames: 48 * prebuffer_ms,
            ready: false,
            active: false,
            input_frames: 0,
            dropped_frames: 0,
            padded_frames: 0,
            underruns: 0,
            max_input_gap_ms: 0,
            last_input: None,
        }
    }
    pub fn stop(&mut self) {
        self.queue.clear();
        self.ready = false;
        self.active = false;
        self.last_input = None;
    }
    pub fn appended(&mut self, frames: usize, now: u64) {
        if frames == 0 {
            return;
        }
        self.active = true;
        self.input_frames += frames as u64;
        if let Some(last) = self.last_input {
            self.max_input_gap_ms = self
                .max_input_gap_ms
                .max(now.saturating_sub(last) / 1_000_000);
        }
        self.last_input = Some(now);
        // At most 500 ms per lane. Drop whole stereo frames, preserving channel alignment.
        let excess = self.queue.len().saturating_sub(48_000) / 2;
        self.discard(excess as u64);
    }
    pub fn discard(&mut self, frames: u64) {
        let frames = frames.min(self.queue.len() as u64 / 2) as usize;
        self.queue.drain(..frames * 2);
        self.dropped_frames += frames as u64;
        if self.queue.is_empty() {
            self.ready = false;
        }
    }
    pub fn render(&mut self, output: &mut [i16]) {
        output.fill(0);
        if !self.active {
            return;
        }
        if !self.ready && self.queue.len() >= self.prebuffer_frames * 2 {
            self.ready = true;
        }
        if !self.ready {
            self.padded_frames += (output.len() / 2) as u64;
            return;
        }
        let count = self.queue.len().min(output.len()) / 2 * 2;
        for sample in output.iter_mut().take(count) {
            *sample = self.queue.pop_front().unwrap();
        }
        if count < output.len() {
            self.underruns += 1;
            self.padded_frames += ((output.len() - count) / 2) as u64;
            self.ready = false;
        }
    }
    pub fn queued_ms(&self) -> usize {
        self.queue.len() / 96
    }
}

#[derive(Default)]
pub struct AudioActivity {
    last_nonzero: Option<u64>,
}
impl AudioActivity {
    pub fn clear(&mut self) {
        self.last_nonzero = None;
    }
    pub fn wrote(&mut self, now: u64, samples: &[i16]) {
        if samples.iter().any(|v| *v != 0) {
            self.last_nonzero = Some(now);
        }
    }
    pub fn playing(&self, now: u64) -> bool {
        self.last_nonzero
            .is_some_and(|last| now.saturating_sub(last) < 250_000_000)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn a_350ms_stall_skips_old_slots_instead_of_bursting_fifty_packets() {
        let mut clock = AudioClock::new(0);
        assert_eq!(clock.due(0), Some(0));
        let skip = clock.due(50 * PERIOD_NS).unwrap();
        assert_eq!(skip, 49 * SAMPLES_PER_PACKET as u64);
        assert_eq!(clock.due(50 * PERIOD_NS), None);
        assert_eq!(clock.rtp_at(50 * PERIOD_NS), 50 * SAMPLES_PER_PACKET as u32);
        assert_eq!(clock.due(51 * PERIOD_NS), Some(0));
    }
    #[test]
    fn uninterrupted_clock_has_no_accumulated_sample_loss() {
        let mut clock = AudioClock::new(1_000_000);
        for i in 0..10000 {
            assert_eq!(clock.due(1_000_000 + i * PERIOD_NS), Some(0));
        }
    }
    #[test]
    fn input_jitter_is_buffered_and_stop_never_replays_stale_audio() {
        let mut b = AudioBuffer::new(60);
        let mut out = vec![0; 704];
        b.queue.extend(vec![17; 960 * 2]);
        b.appended(960, 0);
        b.render(&mut out);
        assert!(out.iter().all(|x| *x == 0));
        b.queue.extend(vec![17; 1920 * 2]);
        b.appended(1920, 40_000_000);
        b.render(&mut out);
        assert!(out.iter().all(|x| *x == 17));
        b.stop();
        b.render(&mut out);
        assert!(out.iter().all(|x| *x == 0));
        assert_eq!(b.max_input_gap_ms, 40);
    }
    #[test]
    fn shortage_rebuffers_and_overflow_is_bounded_in_stereo_frames() {
        let mut b = AudioBuffer::new(1);
        let mut out = vec![0; 704];
        b.queue.extend(vec![23; 100]);
        b.appended(50, 0);
        b.render(&mut out);
        assert_eq!(b.underruns, 1);
        assert_eq!(b.padded_frames, 302);
        assert!(out[100..].iter().all(|v| *v == 0));
        b.queue
            .extend((0..48001).map(|i| if i % 2 == 0 { 100 } else { -100 }));
        b.appended(24000, 1);
        assert!(b.queue.len() <= 48001);
        assert_eq!(b.queue[0], 100);
    }
    #[test]
    fn initialized_or_stale_audio_is_never_reported_as_playing() {
        let mut a = AudioActivity::default();
        assert!(!a.playing(0));
        a.wrote(0, &[0, 0]);
        assert!(!a.playing(0));
        a.wrote(1, &[50, -50]);
        assert!(a.playing(100));
        assert!(!a.playing(300_000_001));
        a.wrote(400_000_000, &[50, -50]);
        a.clear();
        assert!(!a.playing(400_000_000));
    }
}
