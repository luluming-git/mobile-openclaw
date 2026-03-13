#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  📱 OpenClaw 手机端一键安装脚本
#  适用于：Termux (F-Droid 版)
#
#  用法：在 Termux 中粘贴以下命令并回车：
#    curl -sSL https://raw.githubusercontent.com/luluming-git/mobile-openclaw/main/install.sh | bash
# ============================================================

set -e

# ==================== 颜色和工具函数 ====================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

info()    { echo -e "${CYAN}  ▸${NC} $1"; }
ok()      { echo -e "${GREEN}  ✔${NC} $1"; }
warn()    { echo -e "${YELLOW}  ⚠${NC} $1"; }
fail()    { echo -e "${RED}  ✘${NC} $1"; exit 1; }
divider() { echo -e "${DIM}  ──────────────────────────────────────${NC}"; }

# ==================== 欢迎界面 ====================
clear
echo ""
echo -e "${BOLD}${CYAN}"
cat << 'LOGO'
   ╔═══════════════════════════════════════╗
   ║                                       ║
   ║    ◉  O p e n C l a w                 ║
   ║       Mobile Installer                ║
   ║                                       ║
   ╚═══════════════════════════════════════╝
LOGO
echo -e "${NC}"
echo -e "  ${DIM}让 AI 操控你的手机 · 无需 root${NC}"
echo ""
divider
echo ""

# ==================== 环境检测 ====================
info "正在检测运行环境..."

if [ ! -d "/data/data/com.termux" ]; then
    fail "本脚本必须在 Termux 中运行！请在手机上打开 Termux 后重试。"
fi
ok "已确认在 Termux 环境中运行"

# 检测架构
ARCH=$(uname -m)
ok "设备架构：$ARCH"
echo ""

# ==================== 用户输入 ====================
divider
echo ""
echo -e "  ${BOLD}📋 请输入以下配置信息${NC}"
echo ""

# Base URL
echo -e "  ${CYAN}1.${NC} API Base URL"
echo -e "  ${DIM}   示例: https://api.openai.com/v1${NC}"
echo -e "  ${DIM}         https://openrouter.ai/api/v1${NC}"
echo ""
while true; do
    echo -ne "  ${BOLD}→ Base URL: ${NC}"
    read -r BASE_URL
    if [ -z "$BASE_URL" ]; then
        warn "Base URL 不能为空，请重新输入"
    elif [[ ! "$BASE_URL" =~ ^https?:// ]]; then
        warn "URL 格式不对，需要以 http:// 或 https:// 开头"
    else
        ok "Base URL: $BASE_URL"
        break
    fi
done

echo ""

# API Key
echo -e "  ${CYAN}2.${NC} API Key"
echo -e "  ${DIM}   你的 LLM 服务 API 密钥${NC}"
echo ""
while true; do
    echo -ne "  ${BOLD}→ API Key: ${NC}"
    read -r API_KEY
    if [ -z "$API_KEY" ]; then
        warn "API Key 不能为空，请重新输入"
    elif [ ${#API_KEY} -lt 8 ]; then
        warn "API Key 太短了，请确认是否正确"
    else
        MASKED_KEY="${API_KEY:0:4}****${API_KEY: -4}"
        ok "API Key: $MASKED_KEY"
        break
    fi
done

echo ""

# 模型确认
MODEL="gpt-5.2"
echo -e "  ${CYAN}3.${NC} 默认模型"
ok "使用默认模型: ${BOLD}$MODEL${NC}"

echo ""
divider
echo ""

# ==================== 确认安装 ====================
echo -e "  ${BOLD}📦 即将安装以下配置：${NC}"
echo ""
echo -e "  ${DIM}├─${NC} Base URL:  ${CYAN}$BASE_URL${NC}"
echo -e "  ${DIM}├─${NC} API Key:   ${CYAN}$MASKED_KEY${NC}"
echo -e "  ${DIM}├─${NC} Model:     ${CYAN}$MODEL${NC}"
echo -e "  ${DIM}└─${NC} Gateway:   ${CYAN}localhost:18789${NC}"
echo ""

echo -ne "  ${BOLD}确认开始安装？${NC} [Y/n]: "
read -r CONFIRM
if [[ "$CONFIRM" =~ ^[Nn] ]]; then
    echo ""
    info "已取消安装"
    exit 0
fi

echo ""
divider
echo ""

# ==================== 第 1 步：更新软件包 ====================
echo -e "  ${BOLD}${MAGENTA}[1/5]${NC} ${BOLD}更新 Termux 软件包${NC}"
echo ""
pkg update -y && pkg upgrade -y
echo ""
ok "软件包更新完成"
echo ""

# ==================== 第 2 步：安装基础工具 ====================
echo -e "  ${BOLD}${MAGENTA}[2/5]${NC} ${BOLD}安装基础工具${NC}"
echo ""
pkg install -y git openssh tmux termux-api nodejs-lts
echo ""
ok "基础工具安装完成"
echo ""

# ==================== 第 3 步：获取唤醒锁 + 验证 API ====================
echo -e "  ${BOLD}${MAGENTA}[3/5]${NC} ${BOLD}系统配置${NC}"
echo ""

termux-wake-lock 2>/dev/null && ok "唤醒锁已获取" || warn "唤醒锁获取失败（不影响安装）"

if command -v termux-battery-status &>/dev/null; then
    BATTERY=$(timeout 5 termux-battery-status 2>/dev/null || echo "")
    if echo "$BATTERY" | grep -q "percentage"; then
        ok "Termux:API 通信正常"
    else
        warn "Termux:API 响应异常，部分手机操控功能可能不可用"
    fi
else
    warn "未检测到 Termux:API，部分手机操控功能可能不可用"
fi
echo ""

# ==================== 第 4 步：安装 OpenClaw ====================
echo -e "  ${BOLD}${MAGENTA}[4/5]${NC} ${BOLD}安装 OpenClaw${NC}"
echo ""

if command -v openclaw &>/dev/null; then
    CURRENT_VER=$(openclaw --version 2>/dev/null || echo "未知")
    ok "OpenClaw 已安装：$CURRENT_VER，跳过安装"
else
    info "正在全局安装 OpenClaw..."
    npm install -g openclaw
    ok "OpenClaw 安装完成"
fi
echo ""

# ==================== 第 5 步：自动配置 ====================
echo -e "  ${BOLD}${MAGENTA}[5/5]${NC} ${BOLD}自动配置 OpenClaw${NC}"
echo ""

info "正在使用以下参数配置..."
echo -e "  ${DIM}├─ mode:         local${NC}"
echo -e "  ${DIM}├─ auth:         custom-api-key${NC}"
echo -e "  ${DIM}├─ base-url:     $BASE_URL${NC}"
echo -e "  ${DIM}├─ model:        $MODEL${NC}"
echo -e "  ${DIM}└─ compatibility: openai${NC}"
echo ""

openclaw onboard \
    --non-interactive \
    --mode local \
    --auth-choice custom-api-key \
    --custom-base-url "$BASE_URL" \
    --custom-api-key "$API_KEY" \
    --custom-model-id "$MODEL" \
    --custom-compatibility openai \
    --no-install-daemon \
    --skip-channels \
    --skip-skills \
    --skip-health

echo ""
ok "OpenClaw 配置完成"
echo ""

# ==================== 启动网关 ====================
divider
echo ""
echo -e "  ${BOLD}🚀 正在启动 OpenClaw Gateway...${NC}"
echo ""

# 检查是否已有 tmux 会话
if tmux has-session -t openclaw 2>/dev/null; then
    info "检测到已有 openclaw tmux 会话，正在重启..."
    tmux kill-session -t openclaw 2>/dev/null
fi

# 在 tmux 中启动 gateway
tmux new-session -d -s openclaw "openclaw gateway"
sleep 2

if tmux has-session -t openclaw 2>/dev/null; then
    ok "OpenClaw Gateway 已在后台启动 (tmux 会话: openclaw)"
else
    warn "tmux 启动可能有问题，尝试手动启动："
    echo -e "  ${YELLOW}tmux new -s openclaw${NC}"
    echo -e "  ${YELLOW}openclaw gateway${NC}"
fi

echo ""

# ==================== 完成 ====================
echo -e "${BOLD}${GREEN}"
cat << 'DONE'
   ╔═══════════════════════════════════════╗
   ║                                       ║
   ║    ✅  安 装 完 成 ！                  ║
   ║                                       ║
   ╚═══════════════════════════════════════╝
DONE
echo -e "${NC}"

echo -e "  ${BOLD}📱 控制面板：${NC} ${CYAN}http://localhost:18789${NC}"
echo ""
echo -e "  ${BOLD}常用命令：${NC}"
echo -e "  ${DIM}├─${NC} 查看运行状态:  ${YELLOW}tmux attach -t openclaw${NC}"
echo -e "  ${DIM}├─${NC} 分离会话:      ${YELLOW}Ctrl+B 然后按 D${NC}"
echo -e "  ${DIM}├─${NC} 重启网关:      ${YELLOW}tmux kill-session -t openclaw && tmux new -d -s openclaw 'openclaw gateway'${NC}"
echo -e "  ${DIM}└─${NC} 查看日志:      ${YELLOW}openclaw logs --follow${NC}"
echo ""

divider
echo ""
echo -e "  ${DIM}打开手机浏览器访问 http://localhost:18789 即可使用${NC}"
echo ""
