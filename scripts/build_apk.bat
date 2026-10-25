@echo off
setlocal EnableExtensions
cd /d "%~dp0.."

echo [1/2] Run unit tests with the configured JDK 17 and Android SDK...
call gradlew.bat :app:testReleaseUnitTest --no-daemon
if errorlevel 1 exit /b 1

echo [2/2] Build a debug APK...
call gradlew.bat :app:assembleDebug --no-daemon
if errorlevel 1 exit /b 1

echo APK: app\build\outputs\apk\debug\app-debug.apk
echo See docs\BUILDING.md for private release signing.
