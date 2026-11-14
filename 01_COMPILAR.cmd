@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat :app:assembleDebug
if errorlevel 1 (echo ERROR: Fallo compilacion. NO instalar. & pause & exit /b 1)
if not exist "app\build\outputs\apk\debug\app-debug.apk" (echo ERROR: Falta APK. & pause & exit /b 1)
echo APK creada: %CD%\app\build\outputs\apk\debug\app-debug.apk
pause
