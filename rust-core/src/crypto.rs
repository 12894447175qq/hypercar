//! AirPlay 配对与控制通道的密码学原语（cardeck `AirPlayCrypto.kt` 的 Rust 移植）。
//!
//! 协议依据：
//! - 配对信封（pair-setup/pair-verify 的 encryptedData）与控制通道统一使用
//!   ChaCha20-Poly1305，128-bit tag，密文输出为「密文 ‖ 16B tag」；
//! - 控制通道计数 nonce = 4 字节零 + 8 字节**小端**计数（`nonce64`）；
//! - 配对消息 nonce = 4 字节零 + 8 字节 ASCII 标签**左对齐**（`nonce_label`，
//!   标签如 "PS-Msg05"/"PV-Msg02"，是 HomeKit 配对流程的固定约定）；
//! - pair-verify 的临时密钥交换用 X25519，长期身份签名用 Ed25519；
//! - 未配对屏幕流（老车机 pre-HomeKit，如 CarPlay 210.81）走 AES-128-CTR，
//!   计数器为标准 CTR128 大端（BouncyCastle SICBlockCipher 同款），
//!   **密钥流跨帧连续**——调用方必须持有同一个 `Aes128CtrStream` 逐帧
//!   `apply_keystream`，不能每帧新建（否则每帧都从计数 0 开始，解出乱码）。

use aes::Aes128;
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use ctr::cipher::{KeyIvInit, StreamCipher};
use ctr::Ctr128BE;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use hkdf::Hkdf;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha512};
use x25519_dalek::{PublicKey, StaticSecret};

/// HKDF-SHA512（RFC 5869，摘要 SHA-512）：`okm = Expand(Salt= salt, IKM= ikm, info, len)`。
/// AirPlay 配对的全部会话密钥/控制密钥都由它派生（固定取 32 字节）。
pub fn hkdf_sha512(ikm: &[u8], salt: &[u8], info: &[u8], len: usize) -> Vec<u8> {
    let hk = Hkdf::<Sha512>::new(Some(salt), ikm);
    let mut out = vec![0u8; len];
    // expand 只在 len > 255×64 = 16320 时失败；协议内固定 32B，不会触发，
    // 兜底按空输出返回（调用方拿到空密钥会在后续 AEAD 校验处暴露）。
    if hk.expand(info, &mut out).is_err() {
        out.clear();
    }
    out
}

/// SHA-512，多段拼接后一次取摘要（等价 Kotlin 里逐段 update）。
pub fn sha512(parts: &[&[u8]]) -> [u8; 64] {
    let mut digest = Sha512::new();
    for part in parts {
        digest.update(part);
    }
    digest.finalize().into()
}

/// 控制通道计数 nonce：4 字节零 + 8 字节**小端**计数（写、读两个方向各自独立计数）。
pub fn nonce64(counter: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&counter.to_le_bytes());
    nonce
}

/// 配对消息 nonce：4 字节零 + 8 字节 ASCII 标签左对齐（如 "PS-Msg05" →
/// `00 00 00 00 50 53 2D 4D 73 67 30 35`），与接收端实现逐字节一致。
pub fn nonce_label(label: &str) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    let ascii = label.as_bytes();
    let take = ascii.len().min(8);
    nonce[4..4 + take].copy_from_slice(&ascii[..take]);
    nonce
}

/// ChaCha20-Poly1305 加密：返回「密文 ‖ 16B tag」。key 必须 32 字节，
/// 长度不符（编程错误）时返回空串，避免 `Key::from_slice` 直接 panic。
pub fn chacha_seal(key: &[u8], nonce: &[u8; 12], plaintext: &[u8], aad: &[u8]) -> Vec<u8> {
    if key.len() != 32 {
        return Vec::new();
    }
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: plaintext,
                aad,
            },
        )
        .unwrap_or_default()
}

/// ChaCha20-Poly1305 解密（密文‖tag）；认证失败/密钥长度错返回 Err。
/// AAD 为报文头等需认证但不加密的字节（配对信封为空 AAD；视频帧为 128B 头）。
pub fn chacha_open(
    key: &[u8],
    nonce: &[u8; 12],
    ciphertext_and_tag: &[u8],
    aad: &[u8],
) -> Result<Vec<u8>, String> {
    if key.len() != 32 {
        return Err(format!(
            "ChaCha20-Poly1305 密钥长度必须 32 字节（当前 {}）",
            key.len()
        ));
    }
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .decrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: ciphertext_and_tag,
                aad,
            },
        )
        .map_err(|_| "ChaCha20-Poly1305 解密失败（认证不通过或密文损坏）".to_string())
}

/// X25519 临时密钥对（pair-verify M1 每次会话重新生成）。
pub struct X25519KeyPair {
    pub private: [u8; 32],
    pub public: [u8; 32],
}

impl X25519KeyPair {
    /// 从操作系统 CSPRNG 生成临时私钥并推导公钥。
    pub fn generate() -> Self {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret);
        X25519KeyPair {
            private: secret.to_bytes(),
            public: public.to_bytes(),
        }
    }

    /// Diffie-Hellman：返回 32 字节共享秘密（未做任何 KDF，pair-verify 里再 HKDF）。
    pub fn shared(&self, peer_public: &[u8; 32]) -> [u8; 32] {
        let secret = StaticSecret::from(self.private);
        let peer = PublicKey::from(*peer_public);
        secret.diffie_hellman(&peer).to_bytes()
    }
}

/// Ed25519 长期身份密钥对（控制器/接收端的 LTPK，配对一次、长期复用）。
pub struct Ed25519KeyPair {
    pub private: [u8; 32],
    pub public: [u8; 32],
}

impl Ed25519KeyPair {
    /// 生成新的长期身份（落盘后跨会话复用，见 `pairing::ControllerIdentity`）。
    pub fn generate() -> Self {
        let mut private = [0u8; 32];
        OsRng.fill_bytes(&mut private);
        Self::from_private_bytes(private)
    }

    /// 由 32 字节私钥（seed）恢复密钥对并推导公钥。
    pub fn from_private_bytes(bytes: [u8; 32]) -> Self {
        let signing = SigningKey::from_bytes(&bytes);
        let public = signing.verifying_key().to_bytes();
        Ed25519KeyPair {
            private: bytes,
            public,
        }
    }

    /// 由私钥推导 32 字节公钥（与 `from_private_bytes` 的推导一致）。
    pub fn public_key(private: &[u8; 32]) -> [u8; 32] {
        SigningKey::from_bytes(private).verifying_key().to_bytes()
    }

    /// Ed25519 签名，返回 64 字节签名。
    pub fn sign(&self, data: &[u8]) -> [u8; 64] {
        SigningKey::from_bytes(&self.private).sign(data).to_bytes()
    }
}

/// Ed25519 验签（标准验证，与 BouncyCastle Ed25519Signer 语义一致）。
/// 公钥/签名长度固定，非法公钥点返回 false 而不 panic。
pub fn ed25519_verify(public: &[u8; 32], data: &[u8], signature: &[u8; 64]) -> bool {
    let Ok(verifying) = VerifyingKey::from_bytes(public) else {
        return false;
    };
    // ed25519-dalek 2.2：Signature::from_bytes 直接返回 Signature（长度固定，编译期保证）
    let signature = Signature::from_bytes(signature);
    verifying.verify(data, &signature).is_ok()
}

type Aes128Ctr = Ctr128BE<Aes128>;

/// AES-128-CTR 密钥流（未配对屏幕流路径）。计数器是标准 CTR128 大端，
/// 且**状态跨帧连续**：同一个流的所有帧共享这一个实例，逐帧异或。
pub struct Aes128CtrStream {
    cipher: Aes128Ctr,
}

impl Aes128CtrStream {
    /// 以 16 字节密钥 + 16 字节 IV 建流（IV 布局由调用方约定）。
    pub fn new(key: &[u8; 16], iv: &[u8; 16]) -> Self {
        Aes128CtrStream {
            cipher: Aes128Ctr::new(key.into(), iv.into()),
        }
    }

    /// 原地异或密钥流（加密与解密是同一操作），内部计数器随字节数前进。
    pub fn apply_keystream(&mut self, data: &mut [u8]) {
        if data.is_empty() {
            return;
        }
        self.cipher.apply_keystream(data);
    }
}
