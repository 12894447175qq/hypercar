// 把 icon.rc 里的图标资源编进 exe：Explorer / 任务栏 / 窗口都能拿到。
// 用 GNU 工具链时 embed-resource 会自动去找 windres（本机在 D:\devtools\mingw64）。
fn main() {
    #[cfg(windows)]
    {
        embed_resource::compile("icon.rc", embed_resource::NONE);
    }
}
