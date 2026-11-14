<p align="center">
  <img src="docs/icon/play-store-icon.png" width="112" alt="Ordo icon">
</p>

<h1 align="center">Ordo</h1>

<p align="center">
  A free, open source file manager for Android – with an AI that runs entirely on your phone.<br>
  Everything in order, nothing leaves your device.
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue"></a>
  <img alt="Android 6.0+" src="https://img.shields.io/badge/Android-6.0%2B-3DDC84">
  <img alt="No ads, no tracking" src="https://img.shields.io/badge/ads%20%26%20tracking-none-2563EB">
  <img alt="On-device AI" src="https://img.shields.io/badge/AI-on--device-0E7490">
  <a href="https://beko2210.github.io/ordo/privacy.html"><img alt="Privacy policy" src="https://img.shields.io/badge/privacy-policy-555"></a>
</p>

<p align="center">
  <img src="docs/screenshots/home.png" width="24%" alt="Home screen with recent files, categories, vault and backup">
  <img src="docs/screenshots/files.png" width="24%" alt="Folder shown as a grid">
  <img src="docs/screenshots/view-options.png" width="24%" alt="View options: list or grid in three sizes">
  <img src="docs/screenshots/backup.png" width="24%" alt="Backup to a USB drive or another place">
</p>

## Status

Ordo is in development (version 0.2.0). It is not on Google Play yet. Test builds are published as
[pre-releases](https://github.com/BEKO2210/ordo/releases); you can also [build it from source](#build).

## Features

**Find your files**

- Home screen with recent files, categories (images, videos, audio, documents, downloads, APKs, archives) and
  storage at a glance
- Search by name or by text inside files, with regular expressions if you need them
- List or grid in three sizes, for all folders or just one
- Quick access to bookmarks and common folders

**Tidy up**

- Trash: deleted files can be restored for 30 days
- Storage analysis for internal storage, SD cards and USB drives: large files, duplicates, files you have not used
  for a long time, old APKs
- App manager: see installed apps, save their APK, uninstall

**Work with files**

- Copy, move, rename, share, with speed and time remaining
- Rename many files at once using a pattern
- Create and extract ZIP, TAR and 7z archives
- Built-in viewers for images, PDF and text

**Keep files safe**

- Vault: files are encrypted with AES-256-GCM and opened with your screen lock
- Backup: folders and the vault as a ZIP to a USB drive or any place your phone offers, such as Google Drive, with an
  optional password (AES-256)

**Ask in your own words** – see [AI commands](#ai-commands)

- “Move all MP3s from Download to Music”, “Lösche Videos älter als 30 Tage in Download”
- German and English, typed or dictated with the keyboard
- Ordo shows the plan first and acts only when you confirm; undo with one tap

**For power users**

- FTP, SFTP, SMB and WebDAV servers, and a built-in FTP server
- Root and Shizuku access, Linux permissions, symbolic links, SELinux context
- Themes, including a true black night mode

## AI commands

Ordo turns a sentence into a file operation – move, copy, tidy up into subfolders, or move to the trash – and
shows a preview before anything happens. The language model runs **on the phone** with
[llama.cpp](https://github.com/ggml-org/llama.cpp); commands and file names never leave the device.

**How it is kept safe.** The model only writes a small plan in a fixed JSON format (enforced by a grammar). Ordo
then checks the plan itself before showing it:

- Deleting always means the trash, and only when the sentence clearly asks for it – a negation (“don’t delete”,
  “nicht löschen”) or a delete word inside a file name does not count.
- System folders, hidden folders, `Android/data` and the camera folder are off limits unless the user names the
  camera folder explicitly. Paths are compared after resolving `..` and links.
- Unknown or ambiguous folders lead to a question, never to a guess.
- Every file is checked again right before it is touched; existing files are never overwritten.
- Undo reverses exactly what the command did and leaves files alone that changed since.

**Setup.** The feature is off until you open it with the wand in the search bar. Ordo checks the phone, then
downloads the model once (about 2.6 GB, Wi-Fi by default) from a pinned revision on Hugging Face and verifies its
SHA-256 hash. After that it works offline. The model can be removed in the AI sheet at any time.

| | |
|---|---|
| Model | Qwen3.5-4B, 4-bit (Q4_0), Apache-2.0 |
| Accuracy (own benchmark, [details](docs/KI-BEFEHLE.md)) | 51 of 54 commands, 20 of 20 unseen commands, no dangerous errors |
| Requirements | 64-bit phone, at least 5.5 GB RAM, about 3.1 GB free space |

## Security

- Vault keys live in the Android Keystore; files are encrypted with AES-256-GCM (Tink).
- App data is excluded from cloud backup and device transfer, so server passwords never leave the phone.
- SFTP host keys and self-signed FTPS certificates are pinned on first use; a changed key or certificate refuses
  the connection. CA certificates are checked with their host name.
- Extracting archives never writes outside the chosen folder, also not through links inside the archive.
- Other apps can only ask Ordo to open files on shared storage, and only for reading.
- Test builds use the release configuration (R8, not debuggable, screenshots blocked in the vault).

Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Privacy

Ordo collects no data. There are no ads, no trackers and no analytics. The app only connects to the network when
you add a server yourself, and once to download the AI model if you set up AI commands.

- [Privacy policy](https://beko2210.github.io/ordo/privacy.html) ([Deutsch](https://beko2210.github.io/ordo/datenschutz.html))
- [Legal notice](https://beko2210.github.io/ordo/imprint.html) ([Impressum](https://beko2210.github.io/ordo/impressum.html))

The same texts are in the app under **Menu → Legal notice & privacy**.

### Permissions

| Permission | Why Ordo needs it |
|---|---|
| All files access | A file manager has to read and change the files you open with it |
| Installed apps | App manager |
| Install and delete packages | Install APK files and uninstall apps when you ask for it |
| Network | Only for servers you add (FTP, SFTP, SMB, WebDAV), the FTP server and the one-time AI model download |
| Notifications, foreground service | Progress of copy, move and backup jobs |

## Build

You need JDK 21 and the Android SDK. Gradle downloads the NDK and CMake that the native libraries need;
llama.cpp is a git submodule.

```sh
git clone --recurse-submodules https://github.com/BEKO2210/ordo.git
cd ordo
./gradlew assembleDebug   # debug build
./gradlew assembleBeta    # release configuration, signed with the debug key, for test phones
```

The APKs are written to `app/build/outputs/apk/`.

Run the unit tests with:

```sh
./gradlew testDebugUnitTest
```

## Roadmap

Planned next: tabs and a two-pane view, a media player, scheduled backups, and cloud storage. The full plan is in
[docs/ROADMAP.md](docs/ROADMAP.md) (German).

## Contributing

Bug reports and pull requests are welcome. Please keep changes small, follow the code around them, and add a unit
test for new logic. Report security problems as described in [SECURITY.md](SECURITY.md), not in a public issue.

Ordo is fully translated into English and German. Other languages come from Material Files and do not cover the
newer screens yet.

## Credits

Ordo is a fork of [Material Files](https://github.com/zhanghai/MaterialFiles) by Hai Zhang. Its file system
backend, archive and server support are the foundation of this app. Thank you.

Ordo also uses [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) with Arm
[KleidiAI](https://github.com/ARM-software/kleidiai) (Apache-2.0) for on-device AI, the
[Qwen3.5](https://huggingface.co/Qwen) model (Apache-2.0, downloaded, not bundled),
[Tink](https://github.com/tink-crypto/tink-java) for encryption and
[BlurView](https://github.com/Dimezis/BlurView) for the glass effect. The pictograms are
[Material Symbols](https://github.com/google/material-design-icons) by Google (Apache-2.0).

## License

    Copyright (C) 2018 Hai Zhang
    Copyright (C) 2026 Belkis Aslani

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
