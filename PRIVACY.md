# Privacy Policy — AA Media Proxy

**Effective date:** 2026-05-14
**Last updated:** 2026-05-14

AA Media Proxy ("the app") is a free, open-source Android app that bridges audio
playing in apps without native Android Auto support to the Android Auto head unit.
This policy explains what data the app accesses, how it is used, and what is — and
is not — sent off the device.

The short version: **the app does not collect, store, transmit, or share any
personal data. Everything happens locally on your device.**

## Data the app accesses

To do its job, the app must read the state of media currently playing on your
device. Specifically, while the app is running it reads:

- The **package name** of media apps that are currently playing audio, so it can
  decide which session to bridge to Android Auto.
- **Media metadata** for the currently playing track — typically the title,
  artist, album, and album artwork — so this information can be displayed on the
  Android Auto screen.
- The current **playback state** (playing, paused, buffering, etc.) and the
  available playback controls (play/pause/skip), so equivalent controls can be
  forwarded to the head unit.
- The **Android Auto connection state**, to know when to start and stop bridging.

Access to this information is granted by the user via the standard Android
"Notification access" permission, which is required by the Android platform for
any app to enumerate active media sessions.

## Data the app does NOT collect

The app does **not**:

- Read the body or contents of your notifications beyond the media session
  information described above.
- Make network requests, contact remote servers, or use any kind of telemetry,
  analytics, crash reporting, or advertising SDK.
- Store any of the accessed data on disk, in a database, or in shared
  preferences. All accessed data is held in memory only for as long as it is
  needed to mirror the current playback state, and is discarded as soon as
  playback changes or the app is closed.
- Share any data with the developer or any third party.
- Collect any personal identifiers, contacts, location, microphone, camera, SMS,
  call logs, or files.

## Permissions used

| Permission | Purpose |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Required by Android to enumerate active media sessions from other apps. Used solely for that purpose; notification content is not otherwise read. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Required to run a media session foreground service while Android Auto is connected. |
| `POST_NOTIFICATIONS` | Required by Android to display the foreground service notification while bridging is active. |
| `WAKE_LOCK` | Held briefly during playback to prevent the device from interrupting the audio stream. |

The app declares no internet permission and cannot make network requests.

## Children's privacy

The app is not directed at children. The app does not collect any personal data
from any user, including children.

## Third-party libraries

The app uses open-source libraries from Google's AndroidX project (Media3, Car
App Library, AppCompat, etc.). These libraries run entirely on-device and are
not configured to collect or transmit user data in this app.

## Open source

The app is open source. You can review the full source code, including every
permission usage and every external API call, at:

https://github.com/manhvu1212/aa-media-proxy

## Changes to this policy

If this policy is updated, the "Last updated" date at the top of this document
will be changed and the new version will be published at the same URL. Material
changes will additionally be noted in the project's release notes.

## Contact

Questions or concerns about this policy or the app's privacy practices can be
raised by:

- Opening an issue at https://github.com/manhvu1212/aa-media-proxy/issues, or
- Emailing the developer at vu.nguyen@creativeforce.io
