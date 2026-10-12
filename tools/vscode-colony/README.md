# Colony для VS Code

Декларативное расширение для `.colony` и `.settings`: подсветка синтаксиса, комментарии, скобки и отступы.
Цвета берутся из выбранной темы VS Code. Грамматика следует `Colony.g4`.
Диагностику типов и компиляцию выполняет CLI; language server пока не реализован.

Из корня репозитория: `./scripts/install-colony-vscode.ps1 -Install`.
Скрипт создаёт `results/colony-language-0.2.0.vsix` и устанавливает его через `code`.
Без `-Install` только собирает VSIX; файл можно установить через «Extensions: Install from VSIX…».
После установки выполните «Developer: Reload Window»; режим файла должен называться Colony.

Для проверки токенизации: `npm ci --prefix tools/vscode-colony`, затем
`npm test --prefix tools/vscode-colony`. Тест использует тот же TextMate/Oniguruma движок, что VS Code.
