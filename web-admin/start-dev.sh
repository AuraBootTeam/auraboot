#!/bin/bash

# AuraBoot 开发环境启动脚本
# 用于解决 ERR_ALPN_NEGOTIATION_FAILED 错误

set -e

echo "🚀 Starting AuraBoot Development Environment..."

# 检查Node.js版本
NODE_VERSION=$(node --version)
echo "📦 Node.js version: $NODE_VERSION"

# 检查端口占用情况
check_port() {
    local port=$1
    local service=$2
    
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        echo "⚠️  Port $port is already in use by $service"
        echo "   Killing existing process..."
        lsof -ti:$port | xargs kill -9 2>/dev/null || true
        sleep 2
    fi
}

# 检查关键端口
check_port 5173 "Vite Dev Server"
check_port 3500 "BFF Server"
# Do not kill backend here; backend is managed separately.

# 设置环境变量
export BFF_PORT=3500
export SPRING_BOOT_URL=http://localhost:6443
export NODE_ENV=development
export LOG_LEVEL=debug
export BFF_VERBOSE_LOGGING=true

echo "🔧 Environment variables set:"
echo "   BFF_PORT=$BFF_PORT"
echo "   SPRING_BOOT_URL=$SPRING_BOOT_URL"
echo "   NODE_ENV=$NODE_ENV"

# 检查依赖
if [ ! -d "node_modules" ]; then
    echo "📦 Installing dependencies..."
    pnpm install
fi

# 启动BFF服务器（后台运行）
echo "🚀 Starting BFF Server on port $BFF_PORT..."
tsx app/server/bff.server.ts &
BFF_PID=$!

# 等待BFF服务器启动
echo "⏳ Waiting for BFF server to start..."
sleep 3

# 检查BFF服务器是否启动成功
if ! curl -s http://localhost:$BFF_PORT/health >/dev/null 2>&1; then
    echo "❌ BFF Server failed to start"
    kill $BFF_PID 2>/dev/null || true
    exit 1
fi

echo "✅ BFF Server started successfully"

# 清理函数
cleanup() {
    echo "🛑 Shutting down services..."
    kill $BFF_PID 2>/dev/null || true
    echo "✅ Services stopped"
}

# 注册清理函数（必须在启动主进程前）
trap cleanup EXIT INT TERM

# 启动Vite开发服务器（前台）
echo "🚀 Starting Vite Dev Server..."
pnpm dev