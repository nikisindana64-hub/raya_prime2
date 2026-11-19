@echo off
chcp 65001 >nul
echo === Сборка мода Raya Prime (NeoForge 1.21.1) ===
where java >nul 2>nul
if errorlevel 1 (
  echo [ОШИБКА] Java не найдена. Установи JDK 21: https://adoptium.net/ и запусти снова.
  pause
  exit /b 1
)
call gradlew.bat build --no-daemon
if errorlevel 1 (
  echo [ОШИБКА] Сборка не удалась. Пришли лог выше.
  pause
  exit /b 1
)
echo.
echo ГОТОВО! Файл мода лежит в папке build\libs\ ^(rayaprime-*.jar^) - положи его в mods.
explorer build\libs
pause
