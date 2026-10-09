@echo off
setlocal
where gradle >nul 2>&1
if errorlevel 1 (
  echo Gradle CLI not found on PATH.
  echo Open this project in Android Studio ^> Sync Project ^> Build ^> Build APK(s).
  echo Or install Gradle 8.13 and add it to PATH.
  pause
  exit /b 1
)
call gradle :app:assembleDebug
if errorlevel 1 (
  echo Build failed. Check Android SDK 36, JDK 17, and Gradle dependencies.
  pause
  exit /b 1
)
echo.
echo APK: app\build\outputs\apk\debug\app-debug.apk
pause
