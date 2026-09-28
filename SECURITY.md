# Security policy

## Reporting a vulnerability

**Please do not open a public issue.**

Use GitHub's private vulnerability reporting on this repository — the
**Security** tab, then *Report a vulnerability*. It goes only to the maintainer
and does not require exchanging email addresses.

If that is unavailable to you, get in touch through
**[radioterapia.ai](https://radioterapia.ai)** and say only that you have a
security report; the details can then move to a private channel.

You will get a first reply within **7 days**. If a fix is warranted it ships in
a release, and the release notes credit you unless you prefer otherwise.

## What is in scope

This app runs on a tablet inside a clinical room and holds **photographs of
patients**. The findings that matter most here are the ones that expose those
files or the patient records beside them:

- Anything that lets another app on the device read `PhotoID_RT/PHOTOS/` or the
  app's own `filesDir` when it should not.
- Anything that sends data off the device outside the four files that are
  allowed to touch the network — network printing, SMB, patient-list sync and
  treatment photo retrieval.
- Credential handling: SMB passwords are kept in `EncryptedSharedPreferences`
  with a key in the Android Keystore, and they are deliberately excluded from
  the configuration export.
- Anything that makes the app install, or accept an update, from a build that is
  not signed with the project key.

## What is already known, and is not a finding

- **`MANAGE_EXTERNAL_STORAGE`.** Photographs are written to shared storage on
  purpose, so the service's own sync tool can reach them. It is a documented
  design decision, stated in the README.
- **The APK is distributed by sideloading**, not through an app store. Verify
  the `SHA256.txt` published with each release.
- **Google ML Kit is proprietary and bundled**, about 57 MB of native libraries.
  Reports about ML Kit itself belong with Google.
- **The app is not a medical device and is not validated for clinical use.** A
  report that it produces a clinically wrong result is a correctness bug, not a
  vulnerability — open an issue for it, and it is welcome.

## Supported versions

The latest release. This is a small project with a single maintainer; there are
no backports.
