@echo off
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "IP=192.168.101.6:5555"
if not exist "%ADB%" (echo ERROR: No existe adb.exe & pause & exit /b 1)
"%ADB%" connect %IP%
"%ADB%" -s %IP% get-state
if errorlevel 1 (echo ERROR: Sin conexion autorizada. & pause & exit /b 1)
echo === CLICK TV INSTALADA ===
"%ADB%" -s %IP% shell dumpsys package com.tvnetsolutions.jrplayer | findstr /i "versionName versionCode"
echo === HOME ===
"%ADB%" -s %IP% shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
echo.
echo Si no aparece ClickTV.010 y el HOME correcto, detenerse.
pause
