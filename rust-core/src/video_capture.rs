//! Bounded, self-contained Annex B access-unit evidence. Never records pairing keys.
use crate::video_compat::SpsInfo;
use crate::video_pipeline::SourceVideoBuffer;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

pub const MAGIC: &[u8; 8] = b"HCV1\0\0\0\0";
pub const MAX_PACKET: usize = 4 * 1024 * 1024;
pub const MAX_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_FRAMES: usize = 90;
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct CaptureSummary {
    pub frames: usize,
    pub bytes: usize,
    pub limited: bool,
    pub error: Option<String>,
    pub codec: Option<SpsInfo>,
    pub closed: bool,
}
pub struct CaptureWriter {
    writer: BufWriter<File>,
    path: PathBuf,
    buffer: SourceVideoBuffer,
    summary: CaptureSummary,
    finished: bool,
}
impl CaptureWriter {
    pub fn create(directory: &Path, name: &str) -> Result<Self, String> {
        if !matches!(name, "source" | "outgoing" | "transport" | "transcoded") {
            return Err("非法样本名称".into());
        }
        fs::create_dir_all(directory).map_err(|e| e.to_string())?;
        let path = directory.join(format!("{name}.packets"));
        let mut writer = BufWriter::new(File::create(&path).map_err(|e| e.to_string())?);
        writer.write_all(MAGIC).map_err(|e| e.to_string())?;
        Ok(Self {
            writer,
            path,
            buffer: SourceVideoBuffer::new(1280, 720),
            summary: CaptureSummary {
                frames: 0,
                bytes: 8,
                limited: false,
                error: None,
                codec: None,
                closed: false,
            },
            finished: false,
        })
    }
    pub fn record(&mut self, data: &[u8], pts_us: u64) {
        if self.finished || self.summary.error.is_some() || self.summary.limited {
            return;
        }
        if data.len() > MAX_PACKET {
            self.summary.error = Some("来源单帧超过4MiB，停止采样".into());
            self.finish();
            return;
        }
        if !self.buffer.push(data.to_vec()) {
            return;
        }
        while let Some(packet) = self.buffer.pop() {
            if self.summary.frames >= MAX_FRAMES
                || self.summary.bytes + 12 + packet.len() > 2 * 1024 * 1024
            {
                self.summary.limited = true;
                self.finish();
                return;
            }
            if self.summary.codec.is_none() {
                self.summary.codec = crate::screen::annexb_nals(&packet)
                    .into_iter()
                    .find(|n| crate::screen::nal_type(n) == 7)
                    .and_then(|s| SpsInfo::parse(s).ok());
            }
            let result = (|| -> std::io::Result<()> {
                self.writer.write_all(&pts_us.to_be_bytes())?;
                self.writer
                    .write_all(&(packet.len() as u32).to_be_bytes())?;
                self.writer.write_all(&packet)?;
                Ok(())
            })();
            match result {
                Ok(()) => {
                    self.summary.frames += 1;
                    self.summary.bytes += 12 + packet.len();
                }
                Err(error) => {
                    self.summary.error = Some(error.to_string());
                    self.finish();
                    return;
                }
            }
        }
    }
    pub fn is_recording(&self) -> bool { !self.finished }
    pub fn frames(&self) -> usize {
        self.summary.frames
    }
    pub fn finish(&mut self) {
        if self.finished {
            return;
        }
        self.finished = true;
        if let Err(e) = self
            .writer
            .flush()
            .and_then(|_| self.writer.get_ref().sync_all())
        {
            self.summary.error = Some(e.to_string());
        }
        self.summary.closed = true;
        let metadata = self.path.with_extension("json");
        if let Ok(bytes) = serde_json::to_vec_pretty(&self.summary) {
            let temporary = metadata.with_extension("json.tmp");
            if fs::write(&temporary, bytes).is_ok() {
                let _ = fs::rename(temporary, metadata);
            }
        }
    }
}
impl Drop for CaptureWriter {
    fn drop(&mut self) {
        self.finish();
    }
}

pub fn read_packets(path: &Path) -> Result<Vec<(u64, Vec<u8>)>, String> {
    let file = File::open(path).map_err(|e| e.to_string())?;
    if file.metadata().map_err(|e| e.to_string())?.len() > MAX_BYTES as u64 {
        return Err("样本超过容量上限".into());
    }
    let mut reader = BufReader::new(file);
    let mut magic = [0; 8];
    reader.read_exact(&mut magic).map_err(|e| e.to_string())?;
    if &magic != MAGIC {
        return Err("样本格式不正确".into());
    }
    let mut packets = Vec::new();
    loop {
        let mut head = [0; 12];
        let count = reader.read(&mut head[..1]).map_err(|e| e.to_string())?;
        if count == 0 {
            break;
        }
        reader
            .read_exact(&mut head[1..])
            .map_err(|_| "样本记录头被截断".to_string())?;
        let len = u32::from_be_bytes(head[8..12].try_into().unwrap()) as usize;
        if len == 0 || len > MAX_PACKET || packets.len() >= MAX_FRAMES {
            return Err("样本记录长度/数量非法".into());
        }
        let mut packet = vec![0; len];
        reader
            .read_exact(&mut packet)
            .map_err(|_| "样本帧被截断".to_string())?;
        packets.push((u64::from_be_bytes(head[..8].try_into().unwrap()), packet));
    }
    if packets.is_empty() {
        return Err("样本没有可解码帧".into());
    }
    Ok(packets)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn dir() -> PathBuf {
        let p = std::env::temp_dir().join(format!("hc-capture-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&p).unwrap();
        p
    }
    fn key() -> Vec<u8> {
        let nals = crate::screen::annexb_nals(include_bytes!("../testdata/source-1280x720.h264"));
        let mut b = vec![];
        for n in nals {
            b.extend_from_slice(&[0, 0, 0, 1]);
            b.extend_from_slice(n);
            if crate::screen::nal_type(n) == 5 {
                break;
            }
        }
        b
    }
    #[test]
    fn samples_start_at_a_decodable_keyframe_and_roundtrip_without_loss() {
        let d = dir();
        let mut c = CaptureWriter::create(&d, "source").unwrap();
        c.record(&[0, 0, 0, 1, 0x41, 4], 0);
        assert_eq!(c.frames(), 0);
        c.record(&key(), 10);
        c.record(&[0, 0, 0, 1, 0x41, 4], 20);
        c.finish();
        let p = read_packets(&d.join("source.packets")).unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(p[0].0, 10);
        assert_eq!(
            crate::screen::annexb_nals(&p[0].1)
                .iter()
                .take(2)
                .map(|n| crate::screen::nal_type(n))
                .collect::<Vec<_>>(),
            vec![7, 8]
        );
        let summary: CaptureSummary =
            serde_json::from_slice(&fs::read(d.join("source.json")).unwrap()).unwrap();
        assert!(summary.closed);
        assert_eq!(summary.codec.unwrap().width, 1280);
        fs::remove_dir_all(d).unwrap();
    }
    #[test]
    fn capture_caps_are_finite_and_partial_records_are_rejected() {
        let d = dir();
        let mut c = CaptureWriter::create(&d, "outgoing").unwrap();
        c.record(&key(), 0);
        for i in 1..150 {
            c.record(&[0, 0, 0, 1, 0x41, 4], i);
        }
        c.finish();
        assert_eq!(
            read_packets(&d.join("outgoing.packets")).unwrap().len(),
            MAX_FRAMES
        );
        fs::write(
            d.join("broken.packets"),
            [MAGIC.as_slice(), &[1, 2]].concat(),
        )
        .unwrap();
        assert!(read_packets(&d.join("broken.packets")).is_err());
        assert!(CaptureWriter::create(&d, "../controller_identity").is_err());
        fs::remove_dir_all(d).unwrap();
    }
}
