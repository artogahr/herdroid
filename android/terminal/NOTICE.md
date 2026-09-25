# Vendored Termux terminal libraries

`src/main` contains the `terminal-emulator` and `terminal-view` libraries from
[termux/termux-app](https://github.com/termux/termux-app) at tag `v0.118.3`. The Termux
repository is GPLv3, with an explicit exception: these two libraries are derived from
[Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) and are
released under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Herdroid changes:

- `TerminalSession.java` no longer spawns a local process. Output comes from a remote byte
  stream and input goes to a `TerminalSession.RemoteIO`.
- `JNI.java` and the native `termux.c` are removed.
