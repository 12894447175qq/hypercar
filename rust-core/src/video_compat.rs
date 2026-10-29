//! Explicit video compatibility policies; ordinary sessions remain byte preserving.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VideoPolicy {
    #[default]
    Original,
    Level40,
    InlineHeaders,
    Level40Inline,
    HardwareTranscode,
}
impl VideoPolicy {
    pub fn inline_headers(self) -> bool {
        matches!(
            self,
            Self::InlineHeaders | Self::Level40Inline | Self::HardwareTranscode
        )
    }
    pub fn normalize_sps(self, sps: &[u8], fps: u32) -> Result<Vec<u8>, String> {
        if !matches!(self, Self::Level40 | Self::Level40Inline) {
            return Ok(sps.to_vec());
        }
        let info = SpsInfo::parse(sps)?;
        if info.level >= 40 {
            return Ok(sps.to_vec());
        }
        if fps == 0
            || fps > 120
            || info.frame_mbs > 8192
            || info.frame_mbs as u64 * fps as u64 > 245_760
            || info.frame_mbs as u64 * info.max_refs.max(1) as u64 > 32_768
            || (info.coded_width / 16).pow(2) > 65_536
            || (info.coded_height / 16).pow(2) > 65_536
        {
            return Err(format!(
                "Level4.0已知约束不满足 size={}x{} fps={fps} refs={}",
                info.width, info.height, info.max_refs
            ));
        }
        let mut patched = sps.to_vec();
        patched[3] = 40;
        Ok(patched)
    }
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct SpsInfo {
    pub width: u32,
    pub height: u32,
    pub coded_width: u32,
    pub coded_height: u32,
    pub profile: u8,
    pub level: u8,
    pub max_refs: u32,
    pub frame_mbs: u32,
}
impl SpsInfo {
    pub fn parse(sps: &[u8]) -> Result<Self, String> {
        if sps.len() < 5 || sps.len() > 4096 || sps[0] & 31 != 7 {
            return Err("SPS缺失或长度非法".into());
        }
        let profile = sps[1];
        let level = sps[3];
        if !matches!(profile, 66 | 77 | 88 | 100) {
            return Err(format!("暂不适配profile={profile}"));
        }
        let mut rbsp = Vec::new();
        let mut zeros = 0;
        for &b in &sps[4..] {
            if zeros >= 2 && b == 3 {
                zeros = 0;
                continue;
            }
            rbsp.push(b);
            zeros = if b == 0 { zeros + 1 } else { 0 };
        }
        let mut bits = Bits { data: rbsp, at: 0 };
        if bits.ue()? > 31 {
            return Err("SPS id非法".into());
        }
        if profile == 100 {
            let chroma = bits.ue()?;
            if chroma != 1 || bits.ue()? != 0 || bits.ue()? != 0 {
                return Err("仅支持8位YUV420兼容变换".into());
            }
            bits.read(1)?;
            if bits.read(1)? != 0 {
                for i in 0..8 {
                    if bits.read(1)? != 0 {
                        let mut last = 8i32;
                        let mut next = 8i32;
                        for _ in 0..if i < 6 { 16 } else { 64 } {
                            if next != 0 {
                                next = (last + bits.se()?).rem_euclid(256);
                            }
                            if next != 0 {
                                last = next;
                            }
                        }
                    }
                }
            }
        }
        if bits.ue()? > 12 {
            return Err("SPS帧号位数非法".into());
        }
        match bits.ue()? {
            0 => {
                bits.ue()?;
            }
            1 => {
                bits.read(1)?;
                bits.se()?;
                bits.se()?;
                let count = bits.ue()?;
                if count > 255 {
                    return Err("SPS偏移循环过大".into());
                }
                for _ in 0..count {
                    bits.se()?;
                }
            }
            2 => {}
            _ => return Err("SPS POC非法".into()),
        }
        let max_refs = bits.ue()?;
        if max_refs > 16 {
            return Err("参考帧数量超过支持范围".into());
        }
        bits.read(1)?;
        let wm = bits.ue()?.checked_add(1).ok_or("SPS宽度溢出")?;
        let hm = bits.ue()?.checked_add(1).ok_or("SPS高度溢出")?;
        if wm > 512 || hm > 512 {
            return Err("SPS尺寸过大".into());
        }
        let frame_only = bits.read(1)?;
        if frame_only == 0 {
            bits.read(1)?;
        }
        bits.read(1)?;
        let (mut left, mut right, mut top, mut bottom) = (0, 0, 0, 0);
        if bits.read(1)? != 0 {
            left = bits.ue()?;
            right = bits.ue()?;
            top = bits.ue()?;
            bottom = bits.ue()?;
        }
        let coded_width = wm * 16;
        let coded_height = hm * 16 * (2 - frame_only);
        let crop_x = left
            .checked_add(right)
            .and_then(|v| v.checked_mul(2))
            .ok_or("裁剪溢出")?;
        let crop_y = top
            .checked_add(bottom)
            .and_then(|v| v.checked_mul(2 * (2 - frame_only)))
            .ok_or("裁剪溢出")?;
        let width = coded_width
            .checked_sub(crop_x)
            .filter(|w| *w >= 2)
            .ok_or("SPS裁剪宽度非法")?;
        let height = coded_height
            .checked_sub(crop_y)
            .filter(|h| *h >= 2)
            .ok_or("SPS裁剪高度非法")?;
        Ok(Self {
            width,
            height,
            coded_width,
            coded_height,
            profile,
            level,
            max_refs,
            frame_mbs: wm * hm * (2 - frame_only),
        })
    }
}
struct Bits {
    data: Vec<u8>,
    at: usize,
}
impl Bits {
    fn read(&mut self, n: usize) -> Result<u32, String> {
        if n > 32 || self.at + n > self.data.len() * 8 {
            return Err("SPS被截断".into());
        }
        let mut value = 0;
        for _ in 0..n {
            value = (value << 1) | ((self.data[self.at / 8] >> (7 - self.at % 8)) & 1) as u32;
            self.at += 1;
        }
        Ok(value)
    }
    fn ue(&mut self) -> Result<u32, String> {
        let mut zeros = 0;
        while self.read(1)? == 0 {
            zeros += 1;
            if zeros > 30 {
                return Err("SPS指数码过长".into());
            }
        }
        Ok(((1u32 << zeros) - 1) + self.read(zeros)?)
    }
    fn se(&mut self) -> Result<i32, String> {
        let n = self.ue()?;
        Ok(if n & 1 == 0 {
            -(n as i32 / 2)
        } else {
            (n as i32 + 1) / 2
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn actual_vivo_50009_sps_is_720p_and_passes_bounded_level4_conversion() {
        // codec_parameters from the user's 2026-10-29 21:26:13.402 log, no image content.
        let original=[0x67,0x42,0x80,0x1e,0xda,0x01,0x40,0x16,0xe9,0xa8,0x28,0x30,0x28,0x36,0x85,0x09,0xa8];
        let info=SpsInfo::parse(&original).unwrap();
        assert_eq!((info.width,info.height,info.frame_mbs),(1280,720,3600));
        let mut expected=original;expected[3]=40;
        assert_eq!(VideoPolicy::Level40Inline.normalize_sps(&original,60).unwrap(),expected);
        assert_eq!(VideoPolicy::Original.normalize_sps(&original,60).unwrap(),original);
    }
    fn source_sps() -> Vec<u8> {
        crate::screen::annexb_nals(include_bytes!("../testdata/source-1280x720.h264"))
            .into_iter()
            .find(|n| crate::screen::nal_type(n) == 7)
            .unwrap()
            .to_vec()
    }
    #[test]
    fn level_compatibility_changes_only_the_level_and_preserves_original_mode() {
        let mut original = source_sps();
        original[3] = 30;
        assert_eq!(
            VideoPolicy::Original.normalize_sps(&original, 30).unwrap(),
            original
        );
        let mut expected = original.clone();
        expected[3] = 40;
        assert_eq!(
            VideoPolicy::Level40.normalize_sps(&original, 30).unwrap(),
            expected
        );
        assert_eq!(
            VideoPolicy::Level40Inline
                .normalize_sps(&original, 30)
                .unwrap(),
            expected
        );
        original[3] = 42;
        assert_eq!(
            VideoPolicy::Level40.normalize_sps(&original, 30).unwrap(),
            original
        );
    }
    #[test]
    fn incompatible_or_truncated_stream_is_not_relabelled() {
        assert!(VideoPolicy::Level40
            .normalize_sps(&[0x67, 0x42, 0, 30], 30)
            .is_err());
        assert!(VideoPolicy::Level40
            .normalize_sps(&source_sps(), 120)
            .is_err());
        assert_eq!(
            VideoPolicy::Original.normalize_sps(&[0x67], 30).unwrap(),
            vec![0x67]
        );
    }
}
