#!/bin/bash

echo "🚀 启动 Kadb 测试应用..."

# 检查是否存在 gradlew
if [ ! -f "./gradlew" ]; then
    echo "❌ 错误: 找不到 gradlew 文件，请确保在项目根目录执行此脚本"
    exit 1
fi

# 给 gradlew 执行权限
chmod +x ./gradlew

echo "📦 构建并运行测试应用..."

# 运行测试应用
./gradlew :kadb-test-app:run

echo "✅ 测试应用已退出" 