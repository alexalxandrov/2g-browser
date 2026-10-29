@echo off
setlocal
set VERSION=9.6.0
set ROOT_DIR=%~dp0
if not "%GRADLE_HOME%"=="" if exist "%GRADLE_HOME%\bin\gradle.bat" (
  call "%GRADLE_HOME%\bin\gradle.bat" -p "%ROOT_DIR%" %*
  exit /b %ERRORLEVEL%
)
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle --version | findstr /C:"Gradle %VERSION%" >nul
  if %ERRORLEVEL%==0 (
    gradle -p "%ROOT_DIR%" %*
    exit /b %ERRORLEVEL%
  )
)
set DEST=%USERPROFILE%\.gradle\slowline-bootstrap
set GRADLE_BIN=%DEST%\gradle-%VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%DEST%" mkdir "%DEST%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%DEST%\gradle-bin.zip'; Expand-Archive -Force '%DEST%\gradle-bin.zip' '%DEST%'; Remove-Item '%DEST%\gradle-bin.zip'"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_BIN%" -p "%ROOT_DIR%" %*
exit /b %ERRORLEVEL%
