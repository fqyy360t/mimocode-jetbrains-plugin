# MiMoCode JetBrains Plugin - Bottom Control Bar Design

## Objective
Add a bottom control bar to the MiMoCode chat panel, similar to Cursor's interface. The bar provides quick access to mode selection, model switching, context settings, and additional options.

## Product Context
- JetBrains IDE plugin (IntelliJ IDEA, WebStorm, PyCharm, etc.)
- Dark theme by default (follows IDE theme)
- Compact sidebar layout
- The control bar sits below the input area

## Visual Foundations

### Layout
```
┌─────────────────────────────────────────┐
│              Chat Messages              │
├─────────────────────────────────────────┤
│  [Mode ▾]  [Model ▾]  [Context ▾]  [⚙] │  ← Bottom Control Bar
├─────────────────────────────────────────┤
│  [Input Area]              [Send]       │
└─────────────────────────────────────────┘
```

### Color Palette
- Bar background: `#252525` (slightly darker than input area)
- Button background: `transparent`
- Button hover: `#3d3d3d`
- Button text: `#999999`
- Active indicator: `#ff6900` (MiMo orange)
- Separator: `#333333`

### Typography
- Control buttons: 11px, system font
- Dropdown text: 12px

### Components

1. **Mode Selector** (left)
   - Dropdown: Build | Chat | Agent
   - Icon: Play/Chat/Robot
   - Default: Chat

2. **Model Selector** (middle-left)
   - Dropdown: MiMo-V2-Pro | MiMo-V2-Flash | MiMo-Auto
   - Shows current model name
   - Default: MiMo-Auto

3. **Context Mode** (middle-right)
   - Dropdown: Default | Full | Compact
   - Default: Default

4. **Settings Button** (right)
   - Gear icon
   - Opens settings panel

## Accessibility
- Keyboard navigable with Tab
- Screen reader labels for each control
- High contrast mode support

## Anti-Patterns Avoided
- No gradient backgrounds
- No emoji decorations
- Minimal, functional design matching JetBrains aesthetic
- No floating elements

## Decision Trace

| Decision | Reason | Alternatives | Tradeoff |
|----------|--------|--------------|----------|
| Horizontal bar layout | Matches JetBrains sidebar conventions | Vertical toolbar, floating menu | Less space for labels |
| Transparent buttons | Reduces visual noise | Filled buttons, outlined buttons | Less prominent controls |
| 3 mode options | Covers main use cases | 2 modes, 5 modes | Limited vs overwhelming |
| MiMo orange accent | Brand consistency | Blue (JetBrains default), green | Distinct from IDE |
| Compact 11px text | Fits sidebar width | 12px, 10px | Readability vs space |

## Implementation Notes
- Add to index.html as new div after input-area
- CSS for .control-bar container
- JavaScript for dropdown toggles
- Event handling for mode/model/context changes
- Post changes to IDE via bridge
