# 📱 在安卓手机上部署 OpenClaw — 让 AI 操控你的手机

## 概述

通过 **Termux + Termux:API**，你可以在安卓手机上直接运行 OpenClaw AI 代理，
让它操控你的手机硬件（摄像头、短信、通知、GPS、剪贴板等），**无需 root**。

---

## 前置条件

| 项目 | 要求 |
|------|------|
| 手机系统 | Android 10+ (最低 7.0) |
| 内存 | 4GB+ 推荐 |
| 存储空间 | 至少 3GB 可用空间 |
| 网络 | 稳定的 Wi-Fi 或移动数据 (**强烈建议全程开启全局科学上网环境**，否则安装依赖时易报错) |
| API Key | 需要一个 LLM API Key（推荐 Google Gemini 免费 Key） |

---

## 第一步：安装 Termux 和 Termux:API

> [!CAUTION]
> **必须从 F-Droid 安装 Termux，不要用 Google Play 版本！** Play 版已停止维护，会导致各种问题。

1. **安装 F-Droid 应用商店**
   - 手机浏览器访问 https://f-droid.org
   - 下载并安装 F-Droid APK

2. **在 F-Droid 中安装两个应用**
   - 搜索并安装 `Termux`
   - 搜索并安装 `Termux:API`

3. **授予 Termux:API 权限**
   - 进入手机 `设置` → `应用管理` → 找到 `Termux:API`
   - 开启以下权限：**摄像头、麦克风、位置信息、存储、短信、通讯录、通知**
   - 建议全部设为"始终允许"

---

## 第二步：配置 Termux 基础环境

打开 Termux 应用，依次执行以下命令：

```bash
# 1. 更新软件包
pkg update && pkg upgrade -y

# 2. 安装基础工具
pkg install -y git openssh tmux termux-api proot-distro

# 3. 获取 CPU 唤醒锁（防止后台被系统杀掉）
termux-wake-lock
```

---

## 第三步：验证 Termux:API 通信

在进行后续安装前，先验证 Termux:API 是否配置成功：

```bash
termux-battery-status
```

> 如果输出了电池信息的 JSON 数据，说明 API 通信正常；如果卡住或报错，请检查 Termux:API 版本是否与 Termux (F-Droid版) 一致并重试授权。

---

## 第四步：安装 Node.js (原生环境)

> [!CAUTION]
> **不建议使用 proot-distro (Ubuntu) 子系统！** 在 Ubuntu 子系统内无法直接调用 `termux-camera-photo` 等底层 API，会导致 OpenClaw 无法正常操控手机。

在 Termux 原生环境中直接执行：

```bash
# 1. 安装 Node.js LTS 版本
pkg install -y nodejs-lts

# 2. 验证安装
node -v    # 应显示 v22.x.x 或更高
npm -v     # 应显示 10.x.x 或更高
```

---

## 第五步：安装 OpenClaw

```bash
# 全局安装 OpenClaw
npm install -g openclaw

# 验证安装
openclaw --version
```

---

## 第六步：初始化和配置 OpenClaw

```bash
# 运行配置向导
openclaw setup --wizard
```

> 也可以使用 `openclaw onboard` 进入更详细的引导流程。

初始化过程中会要求你配置：

1. **LLM Provider（大模型提供商）** — 推荐选择以下之一：
   - `Google Gemini`（有免费额度，推荐新手）
   - `OpenRouter`（聚合多个模型）
   - `智谱 (Zhipu)`（国内免翻墙）

2. **API Key** — 粘贴你的 API Key
   - Gemini Key 获取地址：https://aistudio.google.com/apikey

3. **SOUL.md**（可选）— 定义 AI 的身份和性格

---

## 第七步：启动 OpenClaw

```bash
# 启动 OpenClaw 网关
openclaw gateway
```

> 如需以后台服务方式运行，可以使用 `openclaw gateway install` 安装为系统服务，然后用 `openclaw gateway start` 启动。

启动成功后，你会看到控制面板 URL（通常是 `http://localhost:18789`）。

---

## 第八步：接入消息平台（远程操控）

启动后，你可以通过多种方式与手机上的 OpenClaw 交互：

### 方式一：手机浏览器直接访问
打开手机浏览器，访问 `http://localhost:18789`

### 方式二：接入即时通讯工具（推荐）
在 OpenClaw 控制面板中配置消息渠道，支持：
- **Telegram**（推荐，配置最简单）
- **Discord**
- **微信**（通过第三方桥接）
- **飞书 / 钉钉**

配置后，你可以在另一台手机或电脑上通过聊天发消息来操控这台手机。

### 方式三：SSH 远程管理
```bash
# 1. 在 Termux 中启动 SSH 服务
sshd

# 2. 修改当前用户的密码（必须设置密码后才能连接）
passwd

# 3. 默认端口 8022，从其他电脑连接：
# Termux 对用户名校验宽松，可使用任意字符作为用户名，主要是密码校验
# ssh -p 8022 任意名字@手机IP地址
```

---

## 📲 OpenClaw 能操控手机的哪些功能？

通过 **Termux:API**，OpenClaw 可以调用以下手机硬件和功能：

| 功能 | 说明 | Termux:API 命令 |
|------|------|-----------------|
| 📸 拍照 | 调用摄像头拍照 | `termux-camera-photo` |
| 🔔 通知 | 发送/读取通知 | `termux-notification` |
| 📱 短信 | 发送和读取短信 | `termux-sms-send / list` |
| 📞 通话 | 拨打电话 | `termux-telephony-call` |
| 📋 剪贴板 | 读写剪贴板 | `termux-clipboard-get / set` |
| 📍 定位 | 获取 GPS 位置 | `termux-location` |
| 🔦 手电筒 | 开关手电筒 | `termux-torch` |
| 🔊 语音 | 文字转语音 | `termux-tts-speak` |
| 📳 振动 | 控制手机振动 | `termux-vibrate` |
| 🔋 电池 | 查看电池状态 | `termux-battery-status` |
| 📡 WiFi | 查看 WiFi 信息 | `termux-wifi-connectioninfo` |
| 👆 触屏操控 | 通过云端安卓设备控制 | ClawHub Mobilerun 技能 |

---

## ⚡ 保持后台运行的技巧

安卓系统会积极杀后台进程，以下方法可以防止 OpenClaw 被杀：

> [!CAUTION]
> **Android 12+ 幽灵进程杀手机制：**
> 如果你是 Android 12 及更高版本系统，即使获取了唤醒锁，系统也可能随时杀掉衍生超过 32 个子进程的 Termux。
> 彻底解决需要用电脑通过 ADB 连接手机执行以下命令解除限制：
> `adb shell device_config put activity_manager max_phantom_processes 2147483647`

```bash
# 1. 在 Termux 中获取唤醒锁
termux-wake-lock

# 2. 使用 tmux 保持会话
tmux new -s openclaw
openclaw gateway
# 按 Ctrl+B 然后按 D 分离会话，OpenClaw 继续运行
# 重新连接：tmux attach -t openclaw
```

其他建议：
- 在手机 `设置` → `电池` 中，将 Termux 设为"无限制"（不受电池优化影响）
- 开启 `开发者选项` → `保持唤醒状态`（充电时不灭屏）
- 将 Termux 锁定在最近任务列表中（长按最近任务卡片，点击锁定🔒）

---

## ⚠️ 安全注意事项

> [!WARNING]
> - **保持 OpenClaw 更新**：2026年2月曾爆出远程代码执行漏洞 (CVE-2026-25253)，务必及时更新
> - **审查技能代码**：安装第三方技能（Skills）时，先审查其代码
> - **API Key 安全**：不要泄露你的 API Key
> - **权限最小化**：只给 Termux:API 授予你确实需要的权限

更新 OpenClaw：
```bash
npm update -g openclaw
```

---

## 🔧 常见问题

### Q: 手机太卡了怎么办？
A: 可以选择轻量级 LLM 模型，或使用远程 API 而不在本地跑模型。OpenClaw 本身只是代理，模型推理在云端完成。

### Q: 想使用 Ubuntu 子系统可以吗？
A: 基础功能可以通过 Ubuntu，但 OpenClaw 操控手机硬件强依赖 `Termux:API` 原生命令。如果不做特别的环境变量穿透和 proxy，Ubuntu 中将无法调用这些命令，因此**强烈建议直接在 Termux 原生环境中运行**。

### Q: iOS 可以吗？
A: iOS 无法运行 Termux。但可以在电脑/服务器上部署 OpenClaw，然后用 iOS 上的 OpenClaw 伴侣 App 连接，让手机作为节点加入代理网络。

### Q: 我想让 OpenClaw 操控其他 App 的界面（比如自动回复微信消息）？
A: 直接通过 Termux:API 控制其他 App 的 UI 仍有局限。可以尝试：
  - 使用 ClawHub 上的 **Mobilerun 技能**（通过云端安卓设备实现屏幕操控）
  - 结合 ADB 调试（需要开启 USB 调试）
  - 使用 Accessibility Service（需要额外配置）
