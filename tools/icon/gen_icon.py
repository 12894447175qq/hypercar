"""HyperCar 应用图标生成器。

设计：深海军蓝渐变底 + 冷白 H 字标（修长比例、小圆角机加工端子）。
品牌青只以极低饱和形式存在于底色与字标底部，不出现大面积色块 / 霓虹 / 描边。

用法：
    python tools/icon/gen_icon.py

产物（直接写入 app/src/main/res）：
    drawable/ic_launcher_glyph.png       432px 自适应前景（全出血，含背景与光影）
    mipmap-xxxhdpi/ic_launcher.png       192px 旧版回退（圆角方形）
    drawable/ic_launcher_monochrome.png  432px 主题图标层（白色字标 + 透明底）
另有 tools/icon/out/ 下的多尺寸预览（已 gitignore）。
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SIZE = 432
DEEP_SPACE = (5, 7, 12)
CYAN = (0, 229, 255)
VIOLET = (124, 77, 255)
WHITE = (255, 255, 255)

# 修长比例：宽 150 / 高 180 / 笔画 34
LEFT_X, RIGHT_X = 141, 291
TOP_Y, BOTTOM_Y = 126, 306
BAR_Y = 216
STROKE = 34
CAP_R = 7
HALF = STROKE / 2

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
PREV = os.path.join(HERE, "out")


def h_mask(cap_r: int = CAP_R) -> Image.Image:
    m = Image.new("L", (SIZE, SIZE), 0)
    d = ImageDraw.Draw(m)
    for x in (LEFT_X, RIGHT_X):
        d.rounded_rectangle((x - HALF, TOP_Y - HALF, x + HALF, BOTTOM_Y + HALF), radius=cap_r, fill=255)
    d.rounded_rectangle((LEFT_X - HALF, BAR_Y - HALF, RIGHT_X + HALF, BAR_Y + HALF), radius=cap_r, fill=255)
    return m


def radial(color, cx, cy, r, alpha, blur, ry=0.95) -> Image.Image:
    layer = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(layer).ellipse((cx - r, cy - r * ry, cx + r, cy + r * ry), fill=255)
    layer = layer.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * alpha))
    tint = Image.new("RGBA", (SIZE, SIZE), color + (255,))
    tint.putalpha(layer)
    return tint


def vgrad(stops, mask) -> Image.Image:
    ys = np.arange(SIZE)[:, None].repeat(SIZE, 1).astype(np.float32) / (SIZE - 1)
    arr = np.zeros((SIZE, SIZE, 3), np.float32)
    arr[:] = np.array(stops[0][1], np.float32)
    for i in range(1, len(stops)):
        p0, c0 = stops[i - 1][0], np.array(stops[i - 1][1], np.float32)
        p1, c1 = stops[i][0], np.array(stops[i][1], np.float32)
        seg = np.clip((ys - p0) / max(p1 - p0, 1e-6), 0, 1)[..., None]
        arr = np.where((ys >= p0)[..., None], c0 * (1 - seg) + c1 * seg, arr)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def background() -> Image.Image:
    """深海军蓝：中心略亮、边缘沉入深空；右下极弱青、左上极弱紫。"""
    yy, xx = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32)
    cx, cy = SIZE * 0.5, SIZE * 0.46
    d = np.clip(np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (SIZE * 0.84), 0, 1)
    t = d * d * (3 - 2 * d)  # smoothstep：过渡柔和，避免线性渐变的廉价感
    arr = np.zeros((SIZE, SIZE, 3), np.float32)
    for i in range(3):
        arr[..., i] = (12, 19, 31)[i] * (1 - t) + DEEP_SPACE[i] * t
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    img = Image.alpha_composite(img, radial(CYAN, SIZE * 0.80, SIZE * 0.80, SIZE * 0.44, 0.075, 120))
    img = Image.alpha_composite(img, radial(VIOLET, SIZE * 0.16, SIZE * 0.14, SIZE * 0.42, 0.065, 120))
    return img


def render() -> Image.Image:
    img = background()
    mask = h_mask()

    # 接触阴影：极轻，只为把字标从底面上托起来
    shadow = Image.new("L", (SIZE, SIZE), 0)
    shadow.paste(Image.new("L", (SIZE, SIZE), 95), (0, 11), mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    img = Image.composite(Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 255)), img, shadow)

    # 字标：冷白竖向渐变，底部收一点青色（品牌色唯一出现处，且极克制）
    body = vgrad(
        [(0.0, (251, 253, 255)), (0.52, (233, 241, 248)), (0.86, (206, 224, 235)), (1.0, (188, 210, 224))],
        mask,
    )
    return Image.alpha_composite(img, body)


def rounded(img: Image.Image, ratio: float = 0.225) -> Image.Image:
    r = int(SIZE * ratio)
    m = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=r, fill=255)
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def monochrome() -> Image.Image:
    """Android 13+ 主题图标：纯白字标 + 透明底，系统按壁纸取色。"""
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(Image.new("RGBA", (SIZE, SIZE), WHITE + (255,)), (0, 0), h_mask())
    return out


def main():
    art = render()
    art.convert("RGB").save(os.path.join(RES, "drawable", "ic_launcher_glyph.png"))
    rounded(art).resize((192, 192), Image.LANCZOS).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    monochrome().save(os.path.join(RES, "drawable", "ic_launcher_monochrome.png"))

    os.makedirs(PREV, exist_ok=True)
    for s in (432, 216, 144, 96, 48):
        art.resize((s, s), Image.LANCZOS).convert("RGB").save(os.path.join(PREV, f"icon-{s}.png"))

    # 圆遮罩 / 圆角遮罩各一张，模拟真机启动器
    for name, shape in (("circle", "circle"), ("squircle", "round")):
        m = Image.new("L", (SIZE, SIZE), 0)
        if shape == "circle":
            ImageDraw.Draw(m).ellipse((6, 6, 425, 425), fill=255)
        else:
            ImageDraw.Draw(m).rounded_rectangle((6, 6, 425, 425), radius=96, fill=255)
        canvas = Image.new("RGB", (SIZE, SIZE), (20, 20, 24))
        canvas.paste(art.convert("RGB"), (0, 0), m)
        canvas.resize((216, 216), Image.LANCZOS).save(os.path.join(PREV, f"icon-{name}-216.png"))
    print("icon generated ->", RES)


if __name__ == "__main__":
    main()
