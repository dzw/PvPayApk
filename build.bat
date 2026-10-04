@echo off
rem PvPay 监控端 APK 一键构建（debug）
rem 工具链: JDK 17 + Gradle 7.5.1(wrapper) + AGP 7.2.2 + android-33
setlocal
cd /d "%~dp0"

set "JAVA_HOME=D:\jdk-17.0.18"
set "ANDROID_HOME=D:\android-sdk"
set "ANDROID_SDK_ROOT=D:\android-sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found: %JAVA_HOME%
    exit /b 1
)
if not exist "%ANDROID_HOME%" (
    echo [ERROR] Android SDK not found: %ANDROID_HOME%
    exit /b 1
)

call gradlew.bat %* assembleDebug --no-daemon
if errorlevel 1 (
    echo.
    echo [FAILED] Gradle build failed.
    exit /b 1
)

echo.
set "APK=app\build\outputs\apk\debug\app-debug.apk"
for %%F in ("%APK%") do echo [OK] %%~fF  (%%~zF bytes)
endlocal
