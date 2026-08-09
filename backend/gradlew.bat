@echo off
setlocal
set "GRADLE_VERSION=9.7.0"
set "GRADLE_SHA256=84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"
if "%GRADLE_USER_HOME%"=="" (
  set "BASE_DIR=%USERPROFILE%\.gradle\deskseed-bootstrap"
) else (
  set "BASE_DIR=%GRADLE_USER_HOME%\deskseed-bootstrap"
)
set "DIST_DIR=%BASE_DIR%\gradle-%GRADLE_VERSION%"
set "ZIP_PATH=%BASE_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "DOWNLOAD_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%DIST_DIR%\bin\gradle.bat" (
  if not exist "%BASE_DIR%" mkdir "%BASE_DIR%"
  if not exist "%ZIP_PATH%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%DOWNLOAD_URL%' -OutFile '%ZIP_PATH%.tmp'"
    if errorlevel 1 exit /b 1
    move /Y "%ZIP_PATH%.tmp" "%ZIP_PATH%" >NUL
  )
  for /f "tokens=*" %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP_PATH%').Hash.ToLower()"') do set "ACTUAL_SHA256=%%H"
  if /I not "%ACTUAL_SHA256%"=="%GRADLE_SHA256%" (
    echo Gradle distribution checksum mismatch.
    del /Q "%ZIP_PATH%"
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP_PATH%' '%BASE_DIR%'"
  if errorlevel 1 exit /b 1
)

call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
