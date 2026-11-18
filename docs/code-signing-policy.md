# Code signing policy

*Hinweis: Diese Seite ist auf Englisch, weil sie für die Prüfung der Codesignatur durch die SignPath Foundation
gedacht ist.*

## Signing status

Signing of Windows binaries via SignPath Foundation has been applied for. Until signing is live, the Windows
setup and the portable ZIP are not signed with a code signing certificate; [docs/betrieb.md](betrieb.md)
describes what to do about the warnings of Windows and macOS.

The update packages of the desktop program (`dispofold-<version>-update.zip`) are signed separately with the
project's own Ed25519 key. The program checks that signature against the public key built into it before
installing an update.

## Team

| Role | Member |
|---|---|
| Author (committer) | [mschabhuettl](https://github.com/mschabhuettl) |
| Reviewer | [mschabhuettl](https://github.com/mschabhuettl) |
| Approver | [mschabhuettl](https://github.com/mschabhuettl) |

Only the approver can start a release, by pushing a version tag `vMAJOR.MINOR.PATCH`. Every release is
approved by the owner individually.

## How binaries are built

- All release binaries are built by GitHub Actions on GitHub-hosted runners, from the tagged commit of this
  public repository, by the workflow [`.github/workflows/release.yml`](https://github.com/mschabhuettl/dispofold/blob/main/.github/workflows/release.yml).
  Nothing is built or uploaded from a developer machine.
- The release workflow first runs the complete CI (tests, packaging and self tests of the installed programs).
  Only when it passes are the binaries built and attached to a draft release, which is published after all
  parts succeeded.
- Each part of a release uploads a `*-SHA256SUMS.txt` file with the SHA-256 checksums of its files, so
  downloads can be checked with `sha256sum -c`.
- Third-party components bundled with the program are listed in
  [THIRD_PARTY.md](https://github.com/mschabhuettl/dispofold/blob/main/THIRD_PARTY.md).

## Privacy

Dispofold transfers no data to other networked systems unless specifically requested by the user.

- The desktop program (the signed Windows binaries among them) keeps its data encrypted in its local data
  folder. It makes no network request before the user has agreed. On the first start without saved update
  settings (a new installation, or the first start after updating to a version with this question) it asks
  once: "Soll Dispofold automatisch nach neuen Versionen suchen?" ("Should Dispofold check for new versions
  automatically?", Yes or No). Only "Ja" sets the project's GitHub releases API as the update source and turns
  on the check at start; "Nein" leaves the source empty, and then the program never uses the network. An
  update source the user entered earlier is kept and not asked about. The choice can be changed at any time in
  Einstellungen → Updates; clearing the update source switches the check off again.
- The server version (Docker) checks for a new release on start and once a day. This is an operator setting,
  not part of the signed Windows binaries: it is on by default and switched off with
  `DISPOFOLD_UPDATE_CHECK=false` in the Compose `.env` file ([docs/betrieb.md](betrieb.md#neue-version)).
  Apart from that check it makes no calls to the internet at runtime. Its data stays in its own PostgreSQL
  database.
- What a check sends: one anonymous HTTPS GET request to
  `https://api.github.com/repos/mschabhuettl/dispofold/releases/latest`, without credentials, cookies or any
  data of the user; it carries only the standard headers of the Java HTTP client. GitHub sees the IP address the
  request comes from. When the user installs an
  update in the desktop program, it also downloads the update package from the release. Nothing is uploaded,
  and updates are never installed without the user's action (the server never updates itself).
