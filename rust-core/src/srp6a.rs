//! SRP-6a 客户端（RFC 5054 3072-bit 组，SHA-512）——AirPlay pair-setup 变体，
//! 逐行移植自 cardeck `Srp6aClient.kt`。与参考接收端约定逐项对齐：
//!  - 用户名 I = "Pair-Setup"，固定配对码 P = "3939"（与 Srp6aClient.kt 常量一致）
//!  - x = SHA512(salt, SHA512(I || ":" || P))
//!  - k = SHA512(pad(N) || pad(g))，g = 5，N = RFC 5054 3072-bit 素数
//!  - A = pad(g^a mod N)（a 为 32 字节随机私有指数）
//!  - u = SHA512(pad(A) || pad(B))；S = (B - k·g^x)^(a + u·x) mod N
//!  - 会话密钥 K = SHA512(bytes(S))（**未 pad** 的最小大端字节）
//!  - 客户端证明 M1 = SHA512(H(N)⊕H(g), H(I), salt, pad(A), pad(B), K)
//!  - 服务器证明 HAMK = SHA512(pad(A), M1, K)
//!
//! 与 Kotlin 版的唯一结构性差异：Kotlin 把 K/M1 惰性算在 `clientProof()` 里
//! （`&this` 可变字段写 sessionKey）；Rust 的 `client_proof(&self)` 是不可变借用，
//! 故把 K/M1 在 `start()` 里一次算齐——B 非法时由 `start` 直接返回 Err
//! （Kotlin 是 clientProof() 返回 null，调用点同样在发 M3 之前失败，等价）。

use num_bigint::BigUint;
use num_traits::Zero;
use once_cell::sync::Lazy;
use rand_core::{OsRng, RngCore};

use crate::crypto;

/// SRP 用户名（AirPlay pair-setup 固定值）。
const USERNAME: &str = "Pair-Setup";
/// 固定配对码（AirPlay pair-setup 固定值，真机与车机都印着 "3939"）。
const SETUP_CODE: &str = "3939";
/// RFC 5054 3072-bit 组的 N（与参考实现一致）。
const N_HEX: &str = concat!(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74",
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437",
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED",
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05",
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB",
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B",
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718",
    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33",
    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7",
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864",
    "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2",
    "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
);
/// N 的字节宽度（3072 bit = 384 字节），pad 按它对齐。
const PAD_LEN: usize = 384;

static N: Lazy<BigUint> = Lazy::new(|| {
    BigUint::parse_bytes(N_HEX.as_bytes(), 16).expect("RFC5054 3072-bit 素数常量必须是合法十六进制")
});
static G: Lazy<BigUint> = Lazy::new(|| BigUint::from(5u32));
/// k = SHA512(pad(N) || pad(g))，RFC 5054 的乘法因子。
static K: Lazy<BigUint> =
    Lazy::new(|| BigUint::from_bytes_be(&crypto::sha512(&[&pad(&N), &pad(&G)])));

/// SRP-6a 客户端会话：`start` 时生成 A 并算齐 K/M1，之后只读。
pub struct Srp6aClient {
    /// 客户端公钥 A（已 pad 到 384 字节），随 M3 发给接收端。
    pub public_key_a: Vec<u8>,
    /// 会话密钥 K = SHA512(bytes(S))；`start` 成功后恒为 Some。
    pub session_key: Option<Vec<u8>>,
    /// 预计算的客户端证明 M1（Kotlin 的 clientProof() 返回值）。
    m1: Option<Vec<u8>>,
}

impl Srp6aClient {
    /// 用接收端 M2 里的 salt 与 SRP 公钥 B 开始一轮 SRP。
    /// username = "Pair-Setup"、password = "3939"（与 Srp6aClient.kt 常量一致）。
    /// B 非法（B mod N == 0 或 (B - k·v) mod N == 0）时报错。
    pub fn start(salt: &[u8], server_public: &[u8]) -> Result<Self, String> {
        let n = &*N;
        let g = &*G;

        let big_b = BigUint::from_bytes_be(server_public);
        if (&big_b % n).is_zero() {
            return Err("SRP B 非法：B mod N == 0".to_string());
        }

        let mut secret = [0u8; 32];
        OsRng.fill_bytes(&mut secret);
        let a = BigUint::from_bytes_be(&secret);
        let public_key_a = pad(&g.mod_pow(&a, n));

        let u = BigUint::from_bytes_be(&crypto::sha512(&[&public_key_a, &pad(&big_b)]));
        let inner = crypto::sha512(&[USERNAME.as_bytes(), b":", SETUP_CODE.as_bytes()]);
        let x = BigUint::from_bytes_be(&crypto::sha512(&[salt, &inner]));
        let verifier = g.mod_pow(&x, n);
        // S = (B - k·v) mod N；BigUint 减法下溢会 panic，需先判断大小再补 N
        let kv = (&*K * &verifier) % n;
        let base = if big_b >= kv {
            (&big_b - &kv) % n
        } else {
            (n + &big_b - &kv) % n
        };
        if base.is_zero() {
            return Err("SRP B 非法：(B - k·v) mod N == 0".to_string());
        }

        let exponent = &a + &u * &x;
        let shared = base.mod_pow(&exponent, n);
        // K 取**未 pad** 的最小大端字节再 SHA512（接收端同款，pad 会导致密钥不一致）
        let session_key = crypto::sha512(&[&to_bytes(&shared)]);

        let hash_n = crypto::sha512(&[&to_bytes(n)]);
        let hash_g = crypto::sha512(&[&to_bytes(g)]);
        let hash_xor: Vec<u8> = hash_n
            .iter()
            .zip(hash_g.iter())
            .map(|(left, right)| left ^ right)
            .collect();
        let identifier = USERNAME.as_bytes();
        let m1 = crypto::sha512(&[
            &hash_xor,
            &crypto::sha512(&[identifier]),
            salt,
            &public_key_a,
            &pad(&big_b),
            &session_key,
        ]);

        Ok(Srp6aClient {
            public_key_a,
            session_key: Some(session_key.to_vec()),
            m1: Some(m1.to_vec()),
        })
    }

    /// 客户端证明 M1；若会话非法（start 已拦截，防御性兜底）返回 None。
    pub fn client_proof(&self) -> Option<Vec<u8>> {
        self.m1.clone()
    }

    /// 校验服务器证明 HAMK = SHA512(pad(A), M1, K)。
    pub fn server_proof_valid(&self, server_proof: &[u8]) -> bool {
        let (Some(m1), Some(key)) = (self.m1.as_deref(), self.session_key.as_deref()) else {
            return false;
        };
        crypto::sha512(&[&self.public_key_a, m1, key]).as_slice() == server_proof
    }
}

/// 最小大端字节（无符号；零返回单个 0x00，等价 BigInteger.toByteArray 去符号位）。
fn to_bytes(value: &BigUint) -> Vec<u8> {
    value.to_bytes_be()
}

/// pad 到 384 字节（RFC 5054 的定宽编码，H(N)/H(g)/A/B/S 全部走它）。
fn pad(value: &BigUint) -> Vec<u8> {
    let bytes = to_bytes(value);
    if bytes.len() >= PAD_LEN {
        return bytes;
    }
    let mut out = vec![0u8; PAD_LEN];
    out[PAD_LEN - bytes.len()..].copy_from_slice(&bytes);
    out
}
