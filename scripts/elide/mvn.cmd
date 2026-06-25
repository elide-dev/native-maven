@echo off
@REM ----------------------------------------------------------------------------
@REM Native Maven (Elide) launcher for Windows.
@REM Windows equivalent of scripts/elide/mvn. Gets copied to <dist>\bin\mvn.cmd.
@REM ----------------------------------------------------------------------------

setlocal

@REM --- Resolve script / home directories ---
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

@REM bin -> MAVEN_HOME
for %%i in ("%SCRIPT_DIR%\..") do set "MAVEN_HOME=%%~fi"
@REM bin -> ..\..\.. -> ELIDE_HOME
for %%i in ("%SCRIPT_DIR%\..\..\..") do set "ELIDE_HOME=%%~fi"

if not exist "%MAVEN_HOME%\lib" (
  echo Error: Maven home not found at %MAVEN_HOME%
  echo Expected lib\ directory under MAVEN_HOME.
  exit /b 1
)

@REM --- Locate elide, javac and the native binary (probe known extensions) ---
set "ELIDE_OK="
if exist "%ELIDE_HOME%\bin\elide.exe" set "ELIDE_OK=1"
if not defined ELIDE_OK if exist "%ELIDE_HOME%\bin\elide.bat" set "ELIDE_OK=1"
if not defined ELIDE_OK if exist "%ELIDE_HOME%\bin\elide" set "ELIDE_OK=1"
if not defined ELIDE_OK (
  echo Error: Not a valid Elide home: %ELIDE_HOME% ^(bin\elide not found^)
  exit /b 1
)

set "JAVAC="
if exist "%ELIDE_HOME%\bin\javac.exe" set "JAVAC=%ELIDE_HOME%\bin\javac.exe"
if not defined JAVAC if exist "%ELIDE_HOME%\bin\javac.cmd" set "JAVAC=%ELIDE_HOME%\bin\javac.cmd"
if not defined JAVAC if exist "%ELIDE_HOME%\bin\javac.bat" set "JAVAC=%ELIDE_HOME%\bin\javac.bat"
if not defined JAVAC if exist "%ELIDE_HOME%\bin\javac" set "JAVAC=%ELIDE_HOME%\bin\javac"
if not defined JAVAC (
  echo Error: javac not found under %ELIDE_HOME%\bin
  exit /b 1
)

set "NATIVE="
if exist "%MAVEN_HOME%\bin\nmvn-native.exe" set "NATIVE=%MAVEN_HOME%\bin\nmvn-native.exe"
if not defined NATIVE if exist "%MAVEN_HOME%\bin\nmvn-native" set "NATIVE=%MAVEN_HOME%\bin\nmvn-native"
if not defined NATIVE (
  echo Error: native binary not found under %MAVEN_HOME%\bin
  exit /b 1
)

@REM --- Find project base directory (.mvn marker), walking up from CWD ---
set "EXEC_DIR=%CD%"
set "WDIR=%EXEC_DIR%"
:findBaseDir
if exist "%WDIR%\.mvn" goto baseDirFound
cd ..
if "%WDIR%"=="%CD%" goto baseDirNotFound
set "WDIR=%CD%"
goto findBaseDir
:baseDirFound
set "MAVEN_PROJECTBASEDIR=%WDIR%"
cd "%EXEC_DIR%"
goto endDetectBaseDir
:baseDirNotFound
set "MAVEN_PROJECTBASEDIR=%EXEC_DIR%"
cd "%EXEC_DIR%"
:endDetectBaseDir

"%NATIVE%" ^
  -Dguice_bytecode_gen_option=DISABLED ^
  -Djava.home="%ELIDE_HOME%" ^
  -Dmaven.home="%MAVEN_HOME%" ^
  -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" ^
  -Dmaven.compiler.fork=true ^
  -Dmaven.compiler.executable="%JAVAC%" ^
  %*

set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%