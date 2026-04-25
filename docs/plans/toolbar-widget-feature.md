# Toolbar Widget Feature Plan

## Goal

Add an optional Project Switcher entry to the main IDE toolbar, similar in placement and behavior to the default IntelliJ Platform **Project Widget**. The toolbar entry should provide fast access to the plugin's scanned project list while keeping the existing tool window as the full management surface.

This is a future feature plan only. No implementation has been done yet.

## Current Plugin State

The plugin currently exposes its primary UI as a tool window:

- Tool window id: `Projects`
- Factory: `cz.atomsoft.projectswitcher.projectswitcherplugin.MyToolWindowFactory`
- Registered from `src/main/resources/META-INF/plugin.xml`
- Global settings are application-level, stored through `ProjectSwitcherSettings`
- The panel supports:
  - configured scan roots
  - project scanning with nested Gradle/Maven/IDEA project deduplication
  - active Git branch display
  - project icons matching IDEA's recent-project style where available
  - alphabetical and recent-use sorting
  - flat and tree display modes
  - persisted search query
  - persisted tree expansion and scroll state
  - active-project highlight
  - open in current window and open in new window

The toolbar feature should reuse this logic instead of duplicating project discovery, sorting, icon, branch, or active-project matching behavior.

## Platform Findings

The local IntelliJ Platform SDK used by the project is IDEA 2025.2.4. Inspection of the bundled platform files confirmed that the new UI main toolbar is action-based.

Relevant platform declarations found in IDEA 2025.2.4:

```xml
<group id="MainToolbarNewUI">
  <group id="MainToolbarLeft" searchable="false">
    <action id="main.toolbar.Project" class="com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction"/>
    <group id="MainToolbarGeneralActionsGroup" searchable="false">
      <separator/>
    </group>
  </group>
  <group id="MainToolbarCenter" searchable="false">
    <action id="main.toolbar.Filename" class="com.intellij.openapi.wm.impl.headertoolbar.FilenameToolbarWidgetAction"/>
  </group>
  <group id="MainToolbarRight" searchable="false">
    <reference ref="SearchEverywhere"/>
    <reference ref="SettingsEntryPoint"/>
  </group>
</group>
```

The default project widget is therefore:

- Action id: `main.toolbar.Project`
- Class: `com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction`
- Toolbar group: `MainToolbarLeft`

The same toolbar structure was also found in the locally installed Android Studio Canary home:

- Path observed from Android Studio config: `C:\PJazyky\_IDE\android-studio-canary`
- File inspected: `lib/app.jar!/idea/PlatformActions.xml`
- It contains `MainToolbarNewUI`, `MainToolbarLeft`, and `main.toolbar.Project`

This makes the feature realistic for both IDEA and Android Studio, provided we keep dependencies limited to IntelliJ Platform APIs already available through `com.intellij.modules.platform`.

## Useful Platform APIs

The following classes exist in the local IDEA 2025.2.4 SDK and are relevant for implementation:

- `com.intellij.openapi.actionSystem.ex.CustomComponentAction`
  - Allows an action to render a custom Swing component in a toolbar.
  - Methods include `createCustomComponent(Presentation, String)` and `updateCustomComponent(JComponent, Presentation)`.

- `com.intellij.openapi.actionSystem.ex.ToolbarLabelAction`
  - A `DumbAwareAction` that implements `CustomComponentAction`.
  - Suitable for simple label-like toolbar entries.

- `com.intellij.openapi.roots.ui.configuration.actions.IconWithTextAction`
  - Implements `CustomComponentAction`.
  - Used by some platform toolbar/popup actions that need an icon plus text.

- `com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction`
  - The default project widget implementation.
  - Extends an internal expandable combo action and creates a popup.
  - It is useful as a behavior reference, but depending directly on internal `impl` classes should be avoided where possible.

The default project widget also uses:

- `ProjectWidget.Actions`
  - An action group used inside the default Project Widget popup.
  - Existing IDE actions such as `OpenFile`, `NewProject`, and `ProjectFromVersionControl` add themselves there.

This gives two viable integration points:

1. Add our own toolbar action to `MainToolbarLeft`.
2. Add an action to the default widget's `ProjectWidget.Actions` popup.

## Recommended Product Shape

Do not embed the full existing side panel directly into the toolbar.

The toolbar has limited horizontal space, is updated often, and is expected to remain compact and stable. A full panel with search, sorting, tree/flat controls, settings, refresh, and scroll state would feel heavy and would not match native IDE toolbar behavior.

Recommended shape:

- Keep the existing tool window as the full management UI.
- Add a compact toolbar widget as a fast launcher/status indicator.
- On click, open a popup with the scanned project list.
- Reuse the same data model and rendering conventions as the tool window.

The toolbar widget itself should show only high-signal information:

- project icon
- current project name or a stable label such as `Projects`
- optionally current Git branch
- dropdown arrow/chevron using native toolbar styling

The popup can contain richer interaction:

- filtered project list
- project name and branch
- current project highlight
- right-click or secondary action for open in new window, if feasible
- optional settings entry
- optional "Open tool window" entry

Search should probably live in the popup, not permanently in the toolbar.

## Why Search And Settings Should Not Live Directly In The Toolbar

Technically, a search field in the toolbar is possible through `CustomComponentAction`, because the toolbar can host arbitrary Swing components. It is not recommended for this plugin:

- It would consume too much toolbar width.
- It can steal keyboard focus from the editor.
- It may interact poorly with toolbar layout and customization.
- It would not match the default Project Widget pattern.
- It would duplicate the existing persisted search field in the tool window.

The better approach is:

- Toolbar: compact launcher/current-state display.
- Popup: quick search or speed-search.
- Tool window: persistent full search/filter/sort/tree/settings experience.

Settings should also not be shown as a separate toolbar control. Put settings in the popup footer/header or keep them in the tool window and normal Settings dialog.

## Implementation Options

### Option A: Separate Toolbar Widget

Register a new action under `MainToolbarLeft`, likely near `main.toolbar.Project`.

Example registration shape:

```xml
<actions>
  <action id="BranchProjectSwitcher.ToolbarWidget"
          class="cz.atomsoft.projectswitcher.projectswitcherplugin.ToolbarProjectSwitcherAction"
          text="Project Switcher"
          icon="AllIcons.Nodes.Project">
    <add-to-group group-id="MainToolbarLeft"
                  anchor="after"
                  relative-to-action="main.toolbar.Project"/>
  </action>
</actions>
```

Implementation candidates:

- `DumbAwareAction`, `CustomComponentAction`
- or a normal `DumbAwareAction` with toolbar presentation if a simple button is enough

Click behavior:

- Refresh or read cached scanned projects.
- Open a `JBPopup`, `ListPopup`, or custom popup.
- Select project in current window by default.
- Provide an "Open in New Window" action where possible.

Pros:

- Most visible and fastest access.
- Independent from the default Project Widget.
- Can show branch information directly in the toolbar.
- Can evolve into a richer popup.

Cons:

- Adds another toolbar item near an already existing Project Widget.
- Requires careful width management.
- Needs verification in both IDEA and Android Studio new UI.

### Option B: Add Action To Default Project Widget Popup

Register an action into `ProjectWidget.Actions`.

Example shape:

```xml
<actions>
  <action id="BranchProjectSwitcher.OpenPopup"
          class="cz.atomsoft.projectswitcher.projectswitcherplugin.OpenBranchProjectSwitcherPopupAction"
          text="Project Switcher"
          icon="AllIcons.Nodes.Project">
    <add-to-group group-id="ProjectWidget.Actions"/>
  </action>
</actions>
```

Pros:

- Very native.
- Low UI surface area.
- Less risk of toolbar crowding.
- Uses the existing default Project Widget entry point.

Cons:

- One click deeper.
- Cannot show branch/project status directly in the toolbar.
- Less discoverable for users who expect our plugin to have its own entry.

### Option C: Replace Or Hide Default Project Widget

This is not recommended.

The platform widget is a core IDE component. Replacing it would create avoidable compatibility and UX risk, especially across IDEA and Android Studio.

## Recommended Implementation Strategy

Start with Option A, but keep Option B as a fallback or secondary integration.

Recommended phased plan:

1. Extract a shared project list model from the tool window.
   - Input: global settings, scan roots, current search query, sort mode, view mode.
   - Output: list/tree of renderable project entries.
   - Must preserve current scanner behavior and active-project detection.

2. Add `ToolbarProjectSwitcherAction`.
   - Implement `DumbAwareAction`.
   - Implement `CustomComponentAction` only if a native-looking custom component is needed.
   - Keep the component compact.
   - Make update logic cheap and avoid scanning from every toolbar update.

3. Add a popup for project switching.
   - Prefer `JBPopupFactory` or action-based `ListPopup`.
   - Use speed-search or a small search field at the top.
   - Render project icon, name, and branch.
   - Highlight the active project.
   - Keep advanced controls minimal.

4. Add settings access.
   - Popup footer/header item: `Configure Scan Folders...`
   - Optional item: `Open Project Switcher Tool Window`

5. Register action in `plugin.xml`.
   - Primary: `MainToolbarLeft`, after `main.toolbar.Project`.
   - Verify whether the action remains visible/customizable in the new UI.
   - Consider also adding a `ProjectWidget.Actions` entry.

6. Verify in both IDEs.
   - IDEA via `runIde`.
   - Android Studio if a matching local IDE target is available or by configuring the Gradle IntelliJ Platform test IDE.

## Data And State Reuse

The toolbar feature should not introduce separate settings for scan roots, recency, active project, icons, or branches.

Reuse:

- `ProjectSwitcherSettings`
- `ProjectScanner`
- project icon provider
- recent project timestamp logic
- active project path matching
- search/filter logic where appropriate

Popup-local state can be transient. The persistent state should remain the existing application-level state unless the feature explicitly needs its own setting, such as:

- show toolbar widget: enabled/disabled
- toolbar label mode: project name, branch, project plus branch, or static label

Any new setting should also be application-level, not project-level.

## Performance Considerations

Toolbar action `update()` can run frequently. It must not:

- scan the configured directories
- perform blocking filesystem traversal
- run Git commands
- allocate a large Swing tree/list

Recommended behavior:

- Keep toolbar update limited to current project name/path/branch if already available.
- Use cached scan results from a service.
- Refresh scan results only on explicit popup open, refresh button, settings change, or background debounce.
- If scanning on popup open is necessary, show cached results immediately and refresh asynchronously.

## Compatibility Considerations

Use stable public APIs where possible:

- `AnAction`
- `DumbAwareAction`
- `CustomComponentAction`
- `JBPopupFactory`
- `DefaultActionGroup`
- `ActionManager`

Avoid direct dependency on internal classes under:

- `com.intellij.openapi.wm.impl.headertoolbar.*`

Those classes are valuable as references but are less stable for plugin API compatibility.

The group ids `MainToolbarLeft` and `ProjectWidget.Actions` exist in the inspected local IDEA and Android Studio builds. They should still be verified against the plugin's declared supported IDE range before release.

## UX Details To Preserve

The toolbar popup should preserve the visual decisions already made for the tool window:

- native project icons with colored initials when available
- Git branch on a secondary line with the IDE branch icon
- active project highlight
- hover highlight matching native IDE behavior
- current-window open on primary click
- "Open in New Window" as secondary action

The toolbar component itself should not become a mini control panel.

## Open Questions

- Should the toolbar widget be enabled by default, or should it be opt-in?
- Should it sit directly after the default Project Widget, or inside the `MainToolbarGeneralActionsGroup` area?
- Should the popup respect the persisted tool-window search query, or should toolbar popup search be transient?
- Should the popup display flat list only, or also support the tree mode?
- Should right-click be supported in a popup, or should "Open in New Window" be a visible action/keyboard modifier?
- Should the default Project Widget popup also contain an entry for this plugin even if a separate toolbar widget exists?

## Suggested First Milestone

The smallest useful milestone:

- Add a toolbar button/action in `MainToolbarLeft`.
- On click, open a native popup with a flat filtered project list.
- Show project icon, name, and branch.
- Primary click opens in current window.
- Include a settings action at the bottom.
- Reuse global settings and scanner logic.

Tree mode, persistent popup search, and more advanced controls can come later if the compact popup proves useful.
