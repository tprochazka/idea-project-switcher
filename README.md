<!--
Copyright (C) 2026 ATomSoft

This file is part of Project Switcher.

Project Switcher is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3.

Project Switcher is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Project Switcher. If not, see <https://www.gnu.org/licenses/>.
-->

# Project Switcher

Project Switcher is a JetBrains IDE plugin for switching between multiple local
project checkouts from a dedicated tool window. It is useful when the same
repository exists in several folders, each on a different Git branch.

The plugin supports IntelliJ IDEA and Android Studio, and should also work in
other JetBrains IDEs that support the IntelliJ Platform project-opening APIs.

## Features

- Scans globally configured root folders for IntelliJ, Gradle, and Maven
  projects.
- Stops scanning deeper once a project root is found, so multi-module Gradle
  builds are shown as one project.
- Shows each project name together with its active Git branch.
- Highlights the project that is currently open in the IDE window.
- Supports flat and tree views.
- Supports alphabetical sorting and recent-project sorting based on the IDE
  recent projects data.
- Provides persistent search, view mode, sorting mode, scroll position, and tree
  expansion state across IDE windows.
- Uses a shared in-memory project catalog so multiple IDE windows do not need to
  rescan the same folders independently.
- Updates displayed Git branches when a branch changes in an open IDE project.
- Opens a project in the current window on click, with an option to open it in a
  new window from the context menu.

## Usage

1. Open **Settings | Tools | Project Switcher**.
2. Add one or more folders that contain your local project checkouts.
3. Open the **Project Switcher** tool window.
4. Use search, sorting, and view controls to find a project.
5. Click a project to open it in the current IDE window, or use the context menu
   to open it in a new window.

If no scan folders are configured, the tool window shows an empty state with a
direct **Configure Folders** action. The same configuration action is also
available from the tool window overflow menu.

## Installation From ZIP

1. Build or download the plugin ZIP.
2. In the IDE, open **Settings | Plugins**.
3. Choose **Install Plugin from Disk...**.
4. Select `build/distributions/idea-project-switcher-plugin-1.0.3.zip`.

## Development

Requirements:

- JDK 21
- Gradle wrapper from this repository

Common commands:

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
.\gradlew.bat runIde
```

The generated plugin ZIP is written to:

```text
build/distributions/idea-project-switcher-plugin-1.0.3.zip
```

Additional IntelliJ Platform SDK references are kept in
[docs/IdeaPluginDoc.md](docs/IdeaPluginDoc.md).

## License

Licensed under the GNU General Public License, version 3. See
[LICENSE](LICENSE).
