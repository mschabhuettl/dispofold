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

- The server version (Docker) makes no calls to the internet at runtime. Its data stays in its own PostgreSQL
  database.
- The desktop program keeps its data encrypted in its local data folder. It only uses the network when the
  user enters an update source in Einstellungen → Updates; by default no source is set, so it makes no network
  requests at all. Clearing the update source switches the check off again. An update check only downloads
  the update information and, when the user installs an update, the update package; no data is sent.
- If a later version adds an automatic check for new versions, this section will describe it and how to
  switch it off.
