# Changelog

## Unreleased

## 0.3.0

- Herdroid stays connected for 3 minutes after you switch to another app, so a quick switch back no longer waits for a reconnect. A notification shows while it stays connected and has a Disconnect button. In the background only the SSH link stays open; the app stops polling herdr and following chats until you return.
- New space asks for the folder first and names the space after it. You can still type another name. The folder field suggests subfolders on the server.
- A new space opens the agent picker for its first shell. Pick Terminal, or close the picker, to keep the shell.
- A terminal without an agent has an **Agent** button that starts an agent in that shell.
- An agent without a chat view, such as Antigravity, opens in control when you start it from the phone, so you can answer its questions right away.
- The nearby servers list in Add server scrolls.

## 0.2.0

- A + button next to the page dots opens a new tab in the current space. It lists the agent CLIs herdr finds on the server, with the last one you used first, and a Terminal button for a plain shell.
  - Agents get a free name, such as `codex-2`.
  - A terminal tab opens in control, ready to type.
  - If an agent fails to start, its tab closes again. If it starts but asks something first, such as trusting the folder, the tab stays open to answer in the terminal.
- Starting an agent in a terminal you control, for example by typing `claude`, no longer crashes the app. You stay in the terminal and switch to the chat from the top bar.
- A new Claude session shows "Send a message to start" instead of an empty screen.

## 0.1.0

The first public release.

- Chats for Claude Code, Codex and Kimi Code sessions, rebuilt from their transcripts.
- Cards with buttons for agent questions and approvals.
- A terminal view for every pane, watching or in control.
- herdr's spaces and agents in a side panel, with status dots, and swiping between panes.
- Creating and renaming spaces, and renaming agents.
- Saved servers, automatic reconnect, and SSH server discovery.
- An SSH key in the Android Keystore: Ed25519 on Android 13 and newer, otherwise ECDSA.
- Pinch to change the chat text size.
