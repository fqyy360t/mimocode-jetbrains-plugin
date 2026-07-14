# MiMoCode JetBrains Plugin

[中文](./README.md)

MiMoCode is a JetBrains IDE plugin that embeds the MiMoCode AI coding assistant directly into your IDE. It renders a full-featured AI chat panel in the sidebar using JCEF (Java Chromium Embedded Framework), with real-time editor context awareness, code selection injection, and streaming responses.

### Preview

![Main Interface](image/主界面.png)

## Features

- **AI Chat Panel** — Chat with AI directly in the IDE sidebar
- **Real-time Context Awareness** — Automatically captures the active file, cursor position, and open tabs
- **Code Selection Injection** — Select code and press `Ctrl+Alt+A` to add it to the chat context
- **Streaming Responses** — AI replies stream in real-time with a thinking-time indicator
- **Theme Synchronization** — Automatically follows the IDE's light/dark theme
- **Git Branch Detection** — Automatically detects the current project branch
- **File Navigation** — Click file paths in chat to jump to the corresponding editor location
- **Multiple Modes** — Supports Chat / Build / Agent interaction modes

  ![3 Mode Selector](image/3种模式选择.png)

- **Model Switching** — Supports MiMo-V2-Pro, MiMo-V2-Flash, MiMo-Auto, MiMo-Coder
- **Context Modes** — Default / Full / Compact / No Context context strategies
- **Right-click Menu Integration** — Add selected code to context from the editor context menu

  ![Right-click Menu Integration](image/右键菜单集成.png)

## Requirements

- **JetBrains IDE** 2025.3 or compatible (IntelliJ IDEA, PyCharm, WebStorm, etc.)
- **JCEF Support** — Enabled by default in most modern JetBrains IDEs
- **MiMoCode CLI** — The plugin auto-extracts a bundled binary, or install manually:
  ```bash
  npm install -g @mimo-ai/cli
  ```
- **JDK 21** — Required for building the plugin

> **Verified on**: PyCharm 2025.3, IntelliJ IDEA 2025.3 — both tested and working.

## Quick Start

### Build the Plugin

```bash
# Clone the project
git clone https://github.com/your-username/mimocode-jetbrains-plugin.git
cd mimocode-jetbrains-plugin

# Build the plugin
./gradlew buildPlugin

# Verify compatibility
./gradlew runPluginVerifier

# Run in sandbox IDE
./gradlew runIde
```

The built plugin will be in `build/distributions/`.

### Install the Plugin

1. Build the plugin or download from Releases
2. In JetBrains IDE: `Settings > Plugins > Install Plugin from Disk`
3. Select the built ZIP file
4. Restart IDE

### Usage

| Action | Shortcut | Description |
|--------|----------|-------------|
| Toggle Panel | `Ctrl+\` (Windows/Linux) / `Cmd+\` (macOS) | Toggle the MiMoCode sidebar panel |
| Add Code to Context | `Ctrl+Alt+A` | Add selected code to the chat context |
| Send Message | `Enter` | Send the input content |
| New Line | `Shift+Enter` | Insert a new line in the input box |

## Project Structure

```
src/main/
├── java/ai/mimo/plugin/
│   ├── actions/
│   │   ├── AddToContextAction.kt    # Action to add selected code to context
│   │   └── OpenPanelAction.kt       # Action to open the panel
│   ├── bridge/
│   │   ├── BrowserBridge.kt         # JCEF browser bridge, handles IDE ↔ WebView communication
│   │   └── IdeContextService.kt     # IDE context service, listens to editor events
│   ├── server/
│   │   └── ServerManager.kt         # MiMoCode CLI server process manager
│   ├── settings/
│   │   ├── MiMoConfigurable.kt      # Settings panel UI
│   │   └── MiMoSettings.kt          # Persistent configuration
│   └── toolwindow/
│       └── MiMoToolWindowFactory.kt # ToolWindow factory, initializes the panel
└── resources/
    ├── META-INF/plugin.xml          # Plugin descriptor
    ├── icons/mimo.svg               # Plugin icon
    └── webview/
        └── index.html               # Chat panel frontend (HTML/CSS/JS)
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  JetBrains IDE                                              │
│  ┌──────────────────┐  ┌──────────────────────────────────┐ │
│  │  ToolWindow       │  │  IdeContextService               │ │
│  │  (JCEF Browser)   │  │  - File editor event listener   │ │
│  │                   │  │  - Cursor position tracking     │ │
│  │  ┌──────────────┐│  │  - Theme change listener        │ │
│  │  │  index.html   ││  │  - Git branch detection         │ │
│  │  │  (WebView)    ││  └───────────┬──────────────────────┘ │
│  │  └──────┬───────┘│              │                         │
│  └─────────┼────────┘              │                         │
│            │ JBCefJSQuery          │ sendActiveEditor()      │
│            │ (JS ↔ Kotlin Bridge)  │ sendTabs()              │
│            │                       │ sendTheme()             │
│  ┌─────────▼───────────────────────▼──────────────────────┐ │
│  │  BrowserBridge                                          │ │
│  │  - Built-in HTTP file server                            │ │
│  │  - JS ↔ IDE message routing                             │ │
│  │  - SSE streaming event listener                         │ │
│  └─────────────────┬───────────────────────────────────────┘ │
│                    │ HTTP                                     │
│  ┌─────────────────▼───────────────────────────────────────┐ │
│  │  ServerManager                                          │ │
│  │  - Manages mimo serve subprocess                        │ │
│  │  - Port allocation & health checks                      │ │
│  │  - Process lock file management                         │ │
│  └─────────────────┬───────────────────────────────────────┘ │
└────────────────────┼────────────────────────────────────────┘
                     │
              ┌──────▼──────┐
              │  MiMoCode   │
              │  CLI Server │
              │  (AI Backend)│
              └─────────────┘
```

### Communication Flow

1. **Startup**: `ServerManager` launches the `mimo serve` process, assigns a port, and waits for a health check to pass
2. **Panel Loading**: `BrowserBridge` starts a local HTTP file server, injects configuration scripts, and loads `index.html`
3. **Context Sync**: `IdeContextService` listens to IDE events (file switching, cursor movement, theme changes) and pushes them to the WebView via `BrowserBridge`
4. **Message Sending**: The WebView sends user messages to `BrowserBridge` via `JBCefJSQuery` bridge, which forwards them to the MiMoCode CLI HTTP API
5. **Streaming Response**: `BrowserBridge` receives AI responses via SSE (Server-Sent Events) and pushes them to the WebView in real-time

## Configuration

The plugin uses a bundled MiMoCode binary — no additional configuration is needed. To customize the executable path, go to `Settings > Tools > MiMoCode`.

MiMoCode CLI server configuration files:
- Process lock file: `~/.mimocode-plugin/server.json`
- Binary file: `~/.mimocode-plugin/bin/mimo` (auto-extracted from plugin resources)

## Development

### Tech Stack

- **Language**: Kotlin
- **Build Tool**: Gradle (Kotlin DSL)
- **Plugin Framework**: IntelliJ Platform Plugin SDK 2.1.0
- **UI Framework**: JCEF (Java Chromium Embedded Framework)
- **Minimum JDK**: 21

### Local Development

```bash
# Launch the plugin in a sandbox IDE (auto-downloads IDE instance)
./gradlew runIde

# Build a distributable plugin package
./gradlew buildPlugin

# Run plugin compatibility verification
./gradlew runPluginVerifier
```

### Modifying the Frontend

The chat panel frontend code is located at `src/main/resources/webview/index.html` — a single-file application (HTML + CSS + JavaScript). Rebuild after changes to see them take effect.

## License

[MIT License](LICENSE)

## Links

- [MiMoCode Official Site](https://mimo.xiaomi.com)
- [MiMoCode CLI](https://www.npmjs.com/package/@mimo-ai/cli)
- [JetBrains Plugin SDK Docs](https://plugins.jetbrains.com/docs/intellij/)
