#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  OpenClaw 手机端一键安装脚本
#  适用于：Termux (F-Droid 版)
#  用法：在 Termux 中运行以下命令：
#    curl -sSL <你的脚本托管地址>/install.sh | bash
#    或直接粘贴本脚本全部内容到 Termux 中回车执行
# ============================================================

set -e

# ---------- 颜色输出 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[信息]${NC} $1"; }
ok()    { echo -e "${GREEN}[完成]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
fail()  { echo -e "${RED}[错误]${NC} $1"; exit 1; }

# ---------- 检测环境 ----------
info "正在检测运行环境..."

if [ ! -d "/data/data/com.termux" ]; then
    fail "本脚本必须在 Termux 中运行！请在手机上打开 Termux 后重试。"
fi

ok "已确认在 Termux 环境中运行"

# ---------- 第 1 步：更新软件包 ----------
echo ""
info "===== 第 1/5 步：更新 Termux 软件包 ====="
pkg update -y && pkg upgrade -y
ok "软件包更新完成"

# ---------- 第 2 步：安装基础工具 ----------
echo ""
info "===== 第 2/5 步：安装基础工具 ====="
pkg install -y git openssh tmux termux-api
ok "基础工具安装完成"

# ---------- 第 3 步：获取唤醒锁 ----------
echo ""
info "===== 第 3/5 步：获取 CPU 唤醒锁 ====="
termux-wake-lock 2>/dev/null && ok "唤醒锁已获取" || warn "唤醒锁获取失败（可能需要通知权限），不影响后续安装"

# ---------- 第 4 步：验证 Termux:API ----------
echo ""
info "===== 第 4/5 步：验证 Termux:API 通信 ====="
if command -v termux-battery-status &>/dev/null; then
    BATTERY=$(termux-battery-status 2>/dev/null || echo "")
    if echo "$BATTERY" | grep -q "percentage"; then
        ok "Termux:API 通信正常"
    else
        warn "Termux:API 响应异常，请确认已安装 Termux:API 应用并授予权限"
        warn "安装继续，但部分手机操控功能可能不可用"
    fi
else
    warn "未检测到 termux-api 命令，请确认已安装 Termux:API 应用"
    warn "安装继续，但部分手机操控功能可能不可用"
fi

# ---------- 第 5 步：安装 Node.js ----------
echo ""
info "===== 第 5/5 步：安装 Node.js 和 OpenClaw ====="

if command -v node &>/dev/null; then
    NODE_VER=$(node -v)
    ok "Node.js 已安装：$NODE_VER，跳过安装"
else
    info "正在安装 Node.js LTS..."
    pkg install -y nodejs-lts
    ok "Node.js 安装完成：$(node -v)"
fi

ok "npm 版本：$(npm -v)"

# ---------- 安装 OpenClaw ----------
echo ""
info "正在全局安装 OpenClaw..."
npm install -g openclaw
ok "OpenClaw 安装完成：$(openclaw --version 2>/dev/null || echo '版本获取失败')"

# ---------- 完成 ----------
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  ✅  OpenClaw 安装成功！${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "接下来请执行以下命令完成配置："
echo ""
echo -e "  ${CYAN}1.${NC} 初始化 OpenClaw（配置 API Key 等）："
echo -e "     ${YELLOW}openclaw init${NC}"
echo ""
echo -e "  ${CYAN}2.${NC} 启动 OpenClaw："
echo -e "     ${YELLOW}openclaw start${NC}"
echo ""
echo -e "  ${CYAN}3.${NC} 如需后台运行，建议使用 tmux："
echo -e "     ${YELLOW}tmux new -s openclaw${NC}"
echo -e "     ${YELLOW}openclaw start${NC}"
echo -e "     然后按 ${YELLOW}Ctrl+B${NC} 再按 ${YELLOW}D${NC} 分离会话"
echo ""
echo -e "  ${CYAN}4.${NC} 如需远程 SSH 管理："
echo -e "     ${YELLOW}sshd${NC}"
echo -e "     ${YELLOW}passwd${NC}  （设置密码）"
echo ""
echo -e "详细文档请参考 README.md"
echo ""
