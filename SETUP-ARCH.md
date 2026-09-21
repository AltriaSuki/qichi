# 在 Arch Linux 上准备开发环境

> 写于 2026-09-21，按这台电脑实际执行过的步骤整理。带 `sudo` 的命令由人类执行，其余 AI 可以代劳。

## 1. 需要什么

| 项 | 版本 / 位置 | 用途 |
|---|---|---|
| JDK | OpenJDK 21（`jdk21-openjdk`） | Gradle、服务端、sdkmanager |
| Docker + Compose + Buildx | 仓库最新版 | 本地数据库、服务端测试（Testcontainers）、构建镜像 |
| Android SDK | `~/Android/Sdk` | 编译 App |
| 模拟器 | AVD `qichi_api37`（Android 17，API 37.2，Google APIs，x86_64，16KB 页） | 运行与截图 |
| KVM | `/dev/kvm` 可读写 | 模拟器硬件加速 |
| git | 远程 `git@github.com:AltriaSuki/qichi.git`（SSH 密钥登录） | 版本管理 |

## 2. 系统软件（人类执行）

```fish
sudo pacman -S --needed jdk21-openjdk docker docker-compose docker-buildx android-udev
sudo systemctl enable --now docker.socket
sudo usermod -aG docker,adbusers $USER
```

然后**注销并重新登录**（或重启），让用户组生效；已经开着的 Claude Code 会话也要重开（`claude --resume`）。

> 加入 `docker` 组等于拥有 root 权限，这是 Docker 的常规用法。`android-udev` 和 `adbusers` 组用于以后连接真机。

检查：

```fish
java -version                 # openjdk version "21…"
docker run --rm hello-world   # 不加 sudo 能运行
docker compose version
```

## 3. Android SDK（不需要 sudo）

命令行工具 23.0 起，`sdkmanager` 被新的 `android` 命令取代（`sdkmanager` 仍可用，但会提示已弃用），包名用 `/` 分隔。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
mkdir -p $ANDROID_HOME/cmdline-tools
curl -fLo /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest

# 安装组件（系统镜像约 2.3GB）
yes | $ANDROID_HOME/cmdline-tools/latest/bin/android sdk install \
    platform-tools emulator platforms/android-37.2 build-tools/37.0.0 \
    system-images/android-37.2/google_apis_ps16k/x86_64
```

查看可用版本：`android sdk list --all | grep system-images/android-37`。

## 4. 环境变量（fish）

`~/.config/fish/conf.d/android.fish`：

```fish
# Android SDK（栖迟开发用，见 ~/data/qichi/SETUP-ARCH.md）
set -gx ANDROID_HOME $HOME/Android/Sdk
fish_add_path -g $ANDROID_HOME/cmdline-tools/latest/bin $ANDROID_HOME/platform-tools $ANDROID_HOME/emulator
```

`fish_add_path` 只添加已存在的目录，所以要在第 3 步装完之后开一个新终端才会生效。
这台电脑以前在 `~/.local/share/android-sdk` 放过一份单独的 platform-tools，已经从 `fish_user_paths` 里移除；那个目录可以删掉。

## 5. 模拟器

```bash
avdmanager create avd -n qichi_api37 \
    -k "system-images;android-37.2;google_apis_ps16k;x86_64" -d pixel_9
emulator -avd qichi_api37 -gpu host -no-snapshot-save &
adb wait-for-device
adb shell getprop sys.boot_completed   # 输出 1 表示启动完成
```

已验证：Pixel 9 机型，Android 17（API 37），内存页 16KB，KVM 加速，冷启动约 20 秒。

- 模拟器连本机服务端：`http://10.0.2.2:8080`。
- 截图：`adb exec-out screencap -p > shot.png`。
- 没有图形界面时（例如只在后台跑测试）：加 `-no-window`。

## 6. 常见问题

| 现象 | 处理 |
|---|---|
| `permission denied … docker.sock` | 还没重新登录，用户组没生效。注销重登；临时可以用 `newgrp docker` |
| 日志里有 `ERROR … Your GPU cannot be used for hardware rendering` | 这台电脑上是正常现象：模拟器随后改用 AMD 核显（Vulkan）渲染，画面正常，开机约 20 秒 |
| 模拟器窗口打不开或黑屏（Wayland + NVIDIA 双显卡） | 依次尝试 `-gpu host`、`-gpu swiftshader_indirect`；或设 `QT_QPA_PLATFORM=xcb` |
| 模拟器提示没有加速 | `ls -l /dev/kvm` 应可读写；`lsmod \| grep kvm` 应有 `kvm_amd` 或 `kvm_intel` |
| `adb devices` 显示 `no permissions`（真机） | 确认装了 `android-udev`、在 `adbusers` 组，重新插拔数据线 |
| Gradle 找不到 SDK | 确认 `ANDROID_HOME`，或在 `android/local.properties` 写 `sdk.dir=/home/<用户名>/Android/Sdk` |
