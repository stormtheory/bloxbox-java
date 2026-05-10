@echo off
:: ####################################################################
:: ##
:: ##              WINDOWS (7 / 10 / 11 / Server 2008+)
:: ##             Script is to make building, launching,
:: ##      and running easier with command line (CLI) arguments
:: ##
:: ##               With Love, Stormtheory
:: ##
:: ####################################################################

:: ── JavaFX path ───────────────────────────────────────────────────────
:: Points at the JavaFX Windows x64 SDK lib/ folder inside ./lib/windows/
:: Download SDK from: https://gluonhq.com/products/javafx/
:: Unzip the SDK and copy its lib/ contents into: .\lib\windows\
set "FX_PATH=lib\windows"

:: JavaFX modules required by BloxBox:
::   javafx.web   — WebView embedded browser (RequestDialogWebView)
::   javafx.swing — JFXPanel Swing/JavaFX bridge
set "FX_MODULES=javafx.web,javafx.swing"

:: Jar output file
set JAR_FILENAME=BloxBox-Java.jar

set "PROJECT_NAME=bloxbox-java"

:: ZIP goes into the parent folder, named after the project
set "ZIP_FILE=bloxbox-java.zip"
set "ZIP_PATH=%PARENT_DIR%%ZIP_FILE%"

:: Stage into a temp copy that excludes .git (mirrors tar --exclude=.git)
set "STAGE=%TEMP%\bloxbox-java-stage"

:: ── Minimum required Java version ────────────────────────────────────
:: JavaFX 21+ requires Java 21+. We keep 11 as the floor but JavaFX
:: WebView will fail to initialise below 17 in practice.
set JAVA_MIN=11

:: ── Change to the directory containing this script ───────────────────
:: Equivalent to bash's: cd "$(dirname "$0")"
:: This ensures bin\, lib\, and *.java are found correctly regardless of
:: where the script is launched from (Desktop shortcut, Explorer, CLI).
cd /d "%~dp0"

:: ── Detect double-click vs CLI launch ────────────────────────────────
:: SESSIONNAME is set by cmd.exe when launched from an existing terminal
:: session and absent when Explorer spawns a fresh console window.
:: This avoids the CMDCMDLINE approach which breaks when Explorer appends
:: a stray quote to arguments, e.g.: "run.bat" -h"
set DOUBLE_CLICKED=false
if not defined SESSIONNAME set DOUBLE_CLICKED=true

:: ── Safety: refuse to run as Administrator ───────────────────────────
:: net session succeeds only when the process has true elevation (admin
:: token). More reliable than matching SID S-1-16-12288 via whoami /groups
:: which false-positives on domain machines or certain UAC configurations.
::
:: Security rationale: a launcher should never run as admin --
:: doing so widens the blast radius of any exploit or misconfiguration.
net session >nul 2>&1
if %errorlevel% == 0 (
    echo [SECURITY] This script must NOT be run as Administrator.
    echo            Please re-run as a normal ^(non-elevated^) user.
    call :error_exit
)

:: ── Java presence and version check ──────────────────────────────────
:: Fail fast with a clear message rather than a cryptic JVM error.
:: We parse the major version out of "java -version" stderr output.
:: java -version always prints to stderr, so we redirect 2>&1.
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] java not found on PATH. Please install Java %JAVA_MIN%+ and retry.
    call :error_exit
)

:: Extract the major version number into JAVA_VER.
:: "java -version" prints e.g.:  openjdk version "17.0.2" ...
:: or legacy:                     java version "1.8.0_361" ...
:: We grab the quoted version token, strip quotes, then take the part
:: before the first dot. For 1.x releases the major is the second token.
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VER_RAW=%%~V"
)
:: Strip leading "1." for Java 8 and earlier (1.8 -> 8)
set "JAVA_VER=%JAVA_VER_RAW%"
if "%JAVA_VER_RAW:~0,2%"=="1." set "JAVA_VER=%JAVA_VER_RAW:~2%"
:: Keep only the major number (everything before the first dot)
for /f "delims=." %%M in ("%JAVA_VER%") do set "JAVA_MAJOR=%%M"

:: Numeric comparison — reject if below minimum
if %JAVA_MAJOR% LSS %JAVA_MIN% (
    echo [ERROR] Java %JAVA_MAJOR% detected. This application requires Java %JAVA_MIN% or newer.
    echo         Please upgrade your JDK: https://adoptium.net
    call :error_exit
)

:: --enable-native-access=ALL-UNNAMED is required by JavaFX's JNA bridge
:: on Java 17+. On Java 11-16 it works without it. Set conditionally.
set "NATIVE_ACCESS_FLAG="
if %JAVA_MAJOR% GEQ 17 set "NATIVE_ACCESS_FLAG=--enable-native-access=ALL-UNNAMED"

echo [Java] Detected version %JAVA_MAJOR% — native-access flag: %NATIVE_ACCESS_FLAG%
echo [JavaFX] Module path: %FX_PATH%

:: ── Default flag values ──────────────────────────────────────────────
set DOWNLOADS=false
set DO_BUILD=false
set DO_RUN=false
set DO_TAR=false
set DO_JAR=false
set DO_HELP=false
set HELP=true

:: ── Jump past subroutine definitions to the argument parser ──────────
goto :parse_args

:: ====================================================================
:: FUNCTION: error_exit
:: ====================================================================
:error_exit
echo.
echo [ERROR] Script aborted. See message above for details.
if "%DOUBLE_CLICKED%"=="true" (
    echo.
    pause
)
exit 1

:: ====================================================================
:: FUNCTION: show_help
:: ====================================================================
:show_help
echo.
echo Usage: %~nx0 [OPTIONS]
echo.
echo   (no args)      First run: auto-builds then launches the program.
echo                  Subsequent runs: skips build and launches directly.
echo                  Use -b or -i to force a rebuild at any time.
echo Options:
echo   -d             Copy the zip to the Downloads directory
echo   -i             Force rebuild
echo   -b             Force rebuild
echo   -r             Run only (skips build even if bin\ is empty)
echo   -j             Create fat Jar file
echo   -h             Show this help message
echo.
echo Examples:
echo   %~nx0           -- smart default: build once, then just run
echo   %~nx0 -b        -- force rebuild only
echo   %~nx0 -br       -- force rebuild then run
echo   %~nx0 -r        -- run only (no build check)
echo.
goto :eof

:: ====================================================================
:: FUNCTION: parse_args
:: ====================================================================
:parse_args
if "%~1"=="" goto end_parse

set "arg=%~1"
if "%arg:~0,1%"=="-" goto :do_parse_flags
echo [ERROR] Unknown argument: %arg% >&2
call :show_help
call :error_exit

:do_parse_flags
set "flags=%arg:~1%"
call :parse_flags "%flags%"
shift
goto parse_args
:end_parse

:: ── Dispatch ─────────────────────────────────────────────────────────
if "%DO_HELP%"=="true" (
    call :show_help
    exit /b 0
)

if "%DO_JAR%"=="true" (
    call :JAR
    exit /b 0
)

if "%DO_BUILD%"=="true" call :BUILD

if "%DO_TAR%"=="true" call :TAR_UP

:: ── Default behaviour when no flags were passed ──────────────────────
if "%HELP%"=="true" (
    if exist bin\*.class (
        echo [Auto] Classes found -- launching program...
        call :RUN
    ) else (
        echo [Auto] First run -- building before launch...
        call :BUILD
        echo [Auto] Build complete -- launching program...
        call :RUN
    )
    exit /b 0
)

if "%DO_RUN%"=="true" call :RUN

exit /b 0

:: ====================================================================
:: FUNCTION: parse_flags
:: ====================================================================
:parse_flags
set "str=%~1"
:flag_loop
if "%str%"=="" goto :eof
set "char=%str:~0,1%"
set "str=%str:~1%"

if "%char%"=="d" (
    set DO_TAR=true
    set DOWNLOADS=true
    set HELP=false
)
if "%char%"=="i" (
    set DO_BUILD=true
    set HELP=false
)
if "%char%"=="b" (
    set DO_BUILD=true
    set HELP=false
)
if "%char%"=="r" (
    set DO_RUN=true
    set HELP=false
)
if "%char%"=="j" (
    set DO_JAR=true
    set HELP=false
)
if "%char%"=="h" (
    set DO_HELP=true
    set HELP=false
)
goto flag_loop

:: ====================================================================
:: FUNCTION: BUILD
::   Compiles all .java sources with JavaFX on the module path.
::   --module-path makes the JavaFX jars visible to javac so that
::   imports like javafx.scene.web.* resolve without "package not found".
::   -encoding UTF-8 prevents "unmappable character" errors on Windows
::   where the default codepage cannot represent emoji in source files.
:: ====================================================================
:BUILD
if exist bin\* del /q bin\*

echo javac --module-path %FX_PATH% --add-modules %FX_MODULES% -encoding UTF-8 -cp ".;bin" -d bin *.java
javac --module-path "%FX_PATH%" --add-modules "%FX_MODULES%" ^
      -encoding UTF-8 -cp ".;bin" -d bin *.java

if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed -- see errors above. Launch aborted.
    call :error_exit
)
goto :eof

:: ====================================================================
:: FUNCTION: RUN
::   Launches BloxBox with JavaFX on the module path.
::   --module-path at runtime is required so the JVM can load the
::   JavaFX native libraries (.dll on Windows) for WebView rendering.
::   Without it you get UnsatisfiedLinkError when the browser opens.
:: ====================================================================
:RUN
echo java %NATIVE_ACCESS_FLAG% --module-path %FX_PATH% --add-modules %FX_MODULES% -cp ".;bin" launcher
java %NATIVE_ACCESS_FLAG% --module-path "%FX_PATH%" --add-modules "%FX_MODULES%" ^
     -cp ".;bin" launcher
goto :eof

:: ====================================================================
:: FUNCTION: JAR
::   Builds a fat jar. JavaFX native libs (.dll) cannot be embedded —
::   they must ship alongside the jar in lib\windows\.
::   The run line at the end shows the correct invocation.
:: ====================================================================
:JAR
:: Resolve jar.exe using the same shim-aware strategy as before
set "JAR_EXE="

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jar.exe" set "JAR_EXE=%JAVA_HOME%\bin\jar.exe"
)

if not defined JAR_EXE (
    for /f "delims=" %%I in ('where java 2^>nul') do (
        if not defined JAR_EXE (
            if exist "%%~dpIjar.exe" set "JAR_EXE=%%~dpIjar.exe"
        )
    )
)

if not defined JAR_EXE (
    for %%R in (
        "C:\Program Files\Java"
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Microsoft"
        "C:\Program Files\Amazon Corretto"
        "C:\Program Files\BellSoft"
    ) do (
        if not defined JAR_EXE (
            if exist "%%~R\" (
                for /d %%D in ("%%~R\jdk*") do (
                    if not defined JAR_EXE (
                        if exist "%%~D\bin\jar.exe" set "JAR_EXE=%%~D\bin\jar.exe"
                    )
                )
            )
        )
    )
)

if not defined JAR_EXE (
    echo [ERROR] jar.exe not found. Tried PATH and common install locations.
    echo         Fix: set JAVA_HOME to your JDK root or add JDK bin to PATH.
    call :error_exit
)
echo [Jar] Using: %JAR_EXE%

:: Step 1 — clean
echo [1/8] Cleaning old build artifacts...
if exist bin\* del /q bin\*
if exist "%JAR_FILENAME%" del /q "%JAR_FILENAME%"
if exist fatjar rmdir /s /q fatjar

:: Step 2 — compile
echo [2/8] Compiling sources...
call :BUILD

:: Step 3 — stage class files
echo [3/8] Staging class files...
mkdir fatjar
xcopy /e /i bin fatjar >nul

:: Step 4 — no dependency jars to explode for BloxBox (pure JDK + JavaFX)
:: JavaFX jars are NOT embedded — they must remain on the module path at runtime.
echo [4/8] Skipping dep jar explosion ^(JavaFX stays on module path^)...

:: Step 5 — strip any stale signature files
echo [5/8] Stripping signature files...
if exist fatjar\META-INF\*.SF  del /q fatjar\META-INF\*.SF
if exist fatjar\META-INF\*.RSA del /q fatjar\META-INF\*.RSA
if exist fatjar\META-INF\*.DSA del /q fatjar\META-INF\*.DSA

:: Step 6 — write manifest
:: Main-Class must be "launcher" — that is the entry-point class
echo [6/8] Writing manifest...
if not exist fatjar\META-INF mkdir fatjar\META-INF
(
echo Manifest-Version: 1.0
echo Main-Class: launcher
echo.
) > fatjar\META-INF\MANIFEST.MF

:: Step 7 — package jar
echo [7/8] Packaging jar...
cd fatjar
"%JAR_EXE%" cfm "..\%JAR_FILENAME%" META-INF\MANIFEST.MF .
cd ..
echo [7/8] Done -- %JAR_FILENAME% created.

:: Step 8 — generate .vbs launcher and fix file association
echo [8/8] Writing launcher and registering file association...
call :MAKE_LAUNCHER
call :FIX_JAR_ASSOC

echo.
echo #### All done ####
echo   Run with:  java %NATIVE_ACCESS_FLAG% --module-path %FX_PATH% --add-modules %FX_MODULES% -jar %JAR_FILENAME%
echo   Or use the generated launcher: %JAR_FILENAME:.jar=.vbs%
goto :eof

:: ====================================================================
:: FUNCTION: MAKE_LAUNCHER
::   Writes a .vbs launcher that starts the jar via javaw (no console).
::   Now includes --module-path and --add-modules so the double-click
::   launcher correctly loads JavaFX native libs at runtime.
:: ====================================================================
:MAKE_LAUNCHER
set "VBS_FILE=%JAR_FILENAME:.jar=.vbs%"
if exist "%VBS_FILE%" del /q "%VBS_FILE%"
echo ' BloxBox - windowless launcher>> "%VBS_FILE%"
echo ' Double-click this file to start the app with no console window.>> "%VBS_FILE%"
echo ' Requires Java (javaw.exe) and the lib\windows\ JavaFX SDK folder.>> "%VBS_FILE%"
echo Set sh = CreateObject("WScript.Shell")>> "%VBS_FILE%"
echo ' Resolve the directory this .vbs lives in>> "%VBS_FILE%"
echo scriptDir = Left(WScript.ScriptFullName, InStrRev(WScript.ScriptFullName, "\"))>> "%VBS_FILE%"
echo jarPath = scriptDir ^& "%JAR_FILENAME%">> "%VBS_FILE%"
echo ' --module-path must point at the JavaFX lib folder relative to the jar>> "%VBS_FILE%"
echo fxPath = scriptDir ^& "lib\windows">> "%VBS_FILE%"
echo cmd = "javaw %NATIVE_ACCESS_FLAG% --module-path """ ^& fxPath ^& """ --add-modules %FX_MODULES% -jar """ ^& jarPath ^& """">> "%VBS_FILE%"
echo ' WindowStyle 0 = hidden, bWaitOnReturn False = non-blocking>> "%VBS_FILE%"
echo sh.Run cmd, 0, False>> "%VBS_FILE%"
echo [Launcher] Created: %VBS_FILE%
goto :eof

:: ====================================================================
:: FUNCTION: FIX_JAR_ASSOC
::   Registers .jar files to open with javaw.exe system-wide.
::   Requires Administrator elevation — skips gracefully if not elevated.
:: ====================================================================
:FIX_JAR_ASSOC
set "JAVAW_EXE="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW_EXE=%JAVA_HOME%\bin\javaw.exe"
)
if not defined JAVAW_EXE (
    for /f "delims=" %%I in ('where java 2^>nul') do (
        if not defined JAVAW_EXE (
            if exist "%%~dpIjavaw.exe" set "JAVAW_EXE=%%~dpIjavaw.exe"
        )
    )
)
if not defined JAVAW_EXE (
    for %%R in (
        "C:\Program Files\Java"
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Microsoft"
        "C:\Program Files\Amazon Corretto"
        "C:\Program Files\BellSoft"
    ) do (
        if not defined JAVAW_EXE (
            if exist "%%~R\" (
                for /d %%D in ("%%~R\jdk*") do (
                    if not defined JAVAW_EXE (
                        if exist "%%~D\bin\javaw.exe" set "JAVAW_EXE=%%~D\bin\javaw.exe"
                    )
                )
            )
        )
    )
)
if not defined JAVAW_EXE (
    echo [Assoc] javaw.exe not found -- skipping file association.
    goto :eof
)

ftype jarfile="%JAVAW_EXE%" %NATIVE_ACCESS_FLAG% --module-path "%FX_PATH%" --add-modules %FX_MODULES% -jar "%%1" %%* >nul 2>&1
assoc .jar=jarfile >nul 2>&1

assoc .jar 2>nul | find "jarfile" >nul 2>&1
if %errorlevel% == 0 (
    echo [Assoc] .jar now opens with: %JAVAW_EXE%
) else (
    echo [Assoc] Could not set file association ^(not elevated^).
    echo         To fix: right-click this .bat, Run as Administrator, then -j again.
    echo         The .vbs launcher works regardless -- no admin needed.
)
goto :eof

:: ====================================================================
:: FUNCTION: TAR_UP
::   Creates a .zip archive, optionally copies to Downloads.
::   Unchanged from original — .git excluded via robocopy /xd.
:: ====================================================================
:TAR_UP
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

for %%I in ("%SCRIPT_DIR%") do set "PARENT_DIR=%%~dpI"

if exist "%ZIP_PATH%" del /q "%ZIP_PATH%"

if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\%PROJECT_NAME%"

robocopy "%SCRIPT_DIR%" "%STAGE%\%PROJECT_NAME%" /e /xd ".git" >nul

powershell -NoProfile -Command ^
    "Compress-Archive -Path '%STAGE%\%PROJECT_NAME%' -DestinationPath '%ZIP_PATH%' -Force"

rmdir /s /q "%STAGE%"

echo Archive created: %ZIP_PATH%

if "%DOWNLOADS%"=="true" (
    copy /y "%ZIP_PATH%" "%USERPROFILE%\Downloads\" >nul
    echo Copied to: %USERPROFILE%\Downloads\%PROJECT_NAME%.zip
)
goto :eof

:end_of_script
exit /b 0
