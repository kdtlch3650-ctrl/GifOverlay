@echo off
cd /d "%~dp0"
java --enable-preview --source 21 --enable-native-access=ALL-UNNAMED GifOverlay.java
if errorlevel 1 pause
