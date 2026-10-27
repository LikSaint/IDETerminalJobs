# Job Bar

Job Bar is a JetBrains IDE plugin for reusable project commands.

## Features

- One-shot jobs with a temporary success state and retained console output.
- Tracked processes with run, stop, restart, console, and edit actions.
- Configurable error and recovery patterns for live status detection.
- Built-in presets for Angular, Vite, Node.js, TypeScript, Jest, Docker Compose, and Spring Boot.
- Parameterized jobs with a native searchable popup of command variants.
- Project-scoped job definitions and reusable consoles.
- Native IntelliJ Platform actions, icons, popups, controls, colors, and theme support.

Use `$PROJECT_DIR$` in the working-directory field to refer to the current project root. In a parameterized command, use a placeholder such as `${file}`. When the placeholder is absent, the selected value is appended to the command.

## Build

```bash
./gradlew buildPlugin -PlocalIdePath=/Applications/WebStorm.app
```
