# 视频回归样本

`source-1280x720.h264` 为 FFmpeg 7.1 的 testsrc2 生成图案，不含手机画面或个人数据。
1280×720、H.264 Baseline/Level 3.1、9帧，使用 AUD 划分访问单元。

生成命令：

```sh
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=9:duration=1 -c:v libx264 -profile:v baseline -level:v 3.1 -pix_fmt yuv420p -x264-params aud=1:keyint=9:scenecut=0 -f h264 source-1280x720.h264
```

SHA-256：`f42baa6fe8adbbee16d2a67b7209498b51584fbd67078426c44c5d0fb8b35da7`。

Rust 测试验证真实 CarLife 包解析、视频泵、TCP 帧、认证加密和切换时序。
`python3 tools/check-video-flow.py --ffmpeg /path/to/ffmpeg --output /tmp/hypercar-video-check`
进一步用独立解码器比较来源像素。该样本不能替代当前 vivo 原厂码流和 MIB3 实车验收。
