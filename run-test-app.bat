@echo off
echo 🚀 启动 Kadb 测试应用...

REM 检查是否存在 gradlew.bat
if not exist "gradlew.bat" (
    echo ❌ 错误: 找不到 gradlew.bat 文件，请确保在项目根目录执行此脚本
    pause
    exit /b 1
)

echo 📦 构建并运行测试应用...

REM 运行测试应用
call gradlew.bat :kadb-test-app:run

echo ✅ 测试应用已退出
pause 