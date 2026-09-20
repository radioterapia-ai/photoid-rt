# PhotoID RT

> **Support tool. Not validated for clinical use.**
> This is not a medical device. It does not diagnose, does not measure, does not
> interpret medical images, and does not replace professional judgement. Fields
> filled in by label or barcode reading are **suggestions** and require human
> confirmation. The printed sheet is a document belonging to the team that
> issued it. See [NOTICE](NOTICE).

Positioning photo documentation for radiation therapy, on Android tablets used
inside the simulation room and at the treatment unit. Part of the
**[Radioterapia.AI](https://radioterapia.ai)** ecosystem.

---

## The problem it solves

In many services the positioning record is taken on the phone of whoever happens
to be in the room, and the sheet is filled in by hand afterwards. The photos get
lost, or live on someone's personal device, or arrive on the first treatment day
with nobody sure which patient they belong to.

PhotoID RT runs on a tablet inside the room. It identifies the patient, organises
the photographs by category — face, ID label, positioning, immobilization
accessories, printed forms — builds the positioning sheet with its **Time-Out**
page, and prints it. In the **Treatment** module the same photographs are
consulted day by day to check the patient's setup.

The flow:

1. **Identify the patient** — type it, or read the ID label with on-device OCR
   and barcode scanning.
2. **Photograph**, by category.
3. **Fill in the simulation data** — physician, treatment unit, site, risks,
   observations, planned number of fractions.
4. **Generate the positioning sheet** in PDF, with the Time-Out page.
5. **Print it**, over the network or through the Android print service.
6. **Optionally, let the tablet deliver the files** to the service's own server.
   Off by default — see [Delivering the files](#delivering-the-files).

## What is ours, and what is not

**The recognition models are not ours.** Text recognition and barcode reading are
performed by **Google ML Kit**, a third-party component. We did not develop it
and we claim no authorship over it.

What this project builds is the **accessibility layer**: the interface that puts
existing capability within reach of the people working in the room. Ours are the
categorised capture flow, the on-disk organisation, the positioning sheet and its
Time-Out page, and the heuristics that interpret text ML Kit has *already*
recognised (`scan/EtiquetaParser.kt`).

Making something reachable is not a neutral act — it is what turns "available
library" into "used in the clinic". That is why the warnings, and the human
confirmation designed into every suggested field, are our responsibility.

The full component list, with authorship, licence and version, is in
[NOTICE](NOTICE), in [THIRD_PARTY.md](THIRD_PARTY.md), and in the app's own
**Settings → About** screen.

## Two regimes, the same code

They are not the same thing, and the difference is who deploys it and for what.

| | Published code | An institution's own build |
|---|---|---|
| What it is | Teaching and research material | In-house SaMD |
| Medical device? | **No.** Not validated for clinical use | With documented validation and record retention |
| Who answers for it | Whoever downloads, modifies and deploys answers for their own build | The service that deploys it |

The precedent is TotalSegmentator itself: it declares that it is not a medical
device and is not intended for clinical use, and it is a certified component
inside FDA-cleared products.

## Why the APK is 75 MB

About 57 MB of it is the ML Kit recognition engine, in four ABIs.

That size is not overhead to apologise for — **it is the physical evidence that
nothing leaves the tablet.** The app reads a patient's ID label without sending
the image anywhere. Recognition happens on the device, and for that to be true
the model has to live on the device.

## Privacy

- **Photographs of patients are personal data**, and a facial photograph tied to
  a medical record number is biometric data. The healthcare institution is the
  controller; the app is a tool it operates under its own responsibility.
- **No cloud of ours, no telemetry, no analytics.** Network access is confined to
  eight files — network printing, SMB, patient-list sync, treatment photo
  retrieval, and the four synchronization adapters — and a check in the
  validation ritual fails the build if a network primitive appears anywhere
  else. That list doubling in 4.0 is exactly the kind of decision the check
  exists to make visible: it shows up in the diff and goes to review. There is
  no server of ours and no third-party API.
- Images leave the device only to somewhere the service chose: network printing,
  a USB drive, a synchronization app it installed, or the built-in
  synchronization it configured and enabled. Every destination is an address the
  institution itself provides.
- **Where that address points is the institution's decision, and it has
  consequences.** Pointed at a server on its own network, nothing crosses a
  border. Pointed at a cloud service, the files come to be stored where that
  service stores them, under that service's terms. The Privacy Policy inside the
  app says this in those words, in all twelve languages — it used to say
  "there is no international transfer", which stopped being true the moment the
  cloud destination existed.
- Photographs are stored in shared storage at `PhotoID_RT/PHOTOS/`, so the
  tablet's gallery can see them. When "All files access" is not granted, the app
  falls back to its private folder — **and says so on screen**, because Android
  deletes that folder on uninstall.
- **No real data, not even sample data, enters this repository** — no patient
  CSV, no DICOM series, no log carrying an identifier. `*.csv`, `*.xlsx`,
  `*.xlsb` and `*.dcm` are in `.gitignore` as a second barrier, not as
  permission. To test the patient-list import, use invented data.

## Delivering the files

The photographs and the PDF are written to the tablet's shared storage. Getting
them to the service's server is a separate problem, and the app offers two
answers to it.

**The one that has always been there:** point any synchronization app — FolderSync
and the like — at the `PhotoID_RT` folder. The app does nothing; the folder is
just a folder.

**The one added in 4.0:** the app delivers them itself, to destinations the
service configures — an SMB file server, WebDAV, FTP, SFTP, or a folder in a
cloud app already installed on the tablet (through the Android document picker,
so no credential of that cloud ever passes through this app).

It is **off by default, and that is not a formality.** With the master switch
off there is no background service, no connection attempt, and nothing leaves
the device — the behaviour is exactly what it was before the feature existed. A
service that updates the app does not silently start shipping patient
photographs somewhere.

Two properties that are part of the design, not of the current implementation:

- **One way, and it never deletes.** The app writes at the destination. It does
  not remove, does not rename, and does not bring anything back. This is what
  separates a backup from a mirror, and a mirror of patient data means an
  accidental deletion on the tablet erases the institution's copy.
- **Credentials never leave the device.** They live in `EncryptedSharedPreferences`
  under an Android Keystore master key, and they are deliberately excluded from
  the configuration transfer package — which travels by e-mail and on USB drives.

There is no destination of ours anywhere in this. The app has no server, and the
authors receive no copy.

## Languages

Twelve, all complete: Portuguese, English, Spanish, French, German, Italian,
Polish, Chinese, Japanese, Korean, Arabic and Bengali. Arabic renders
right-to-left.

The app opens in the **device's language** when it is one of the twelve, and in
**English** otherwise. It can be changed at any time in Settings — that choice
then wins over the device.

## Install

Download the APK from the [Releases](../../releases) page and install it on the
tablet. Android will ask you to allow installation from this source.

Permanent link to the latest version:

```
https://github.com/radioterapia-ai/photoid-rt/releases/latest/download/PhotoID_RT_LATEST.apk
```

Every release publishes a `SHA256.txt`. Check it before installing:

```
certutil -hashfile PhotoID_RT_LATEST.apk SHA256
```

**Requirements:** Android 7.0 (API 24) or later. Built and used on a Galaxy Tab S6.

Step-by-step, including the "All files access" permission:
[docs/INSTALACAO.md](docs/INSTALACAO.md).

## Build your own

Nothing here depends on our machine. You need:

- **JDK 17 to 21.** The JBR 25 bundled with Android Studio **will not do**:
  Gradle 8.13 fails on it with `Type T not present`, which does not look like a
  JDK error.
- **Android SDK** with platform 34.
- Git.

Point at the SDK by creating `local.properties` in the root (it is not
versioned):

```
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

Run the quality gate — tests and lint in one command:

```
./gradlew check
```

`check` treats `MissingTranslation` and `ExtraTranslation` as **errors**: a
missing or surplus string in any of the twelve languages fails the build. That is
the safety net, not an obstacle. On Windows, `tools\portao.cmd` finds the JDK for
you.

Build the APK and assemble the versioned delivery:

```
tools\empacotar.cmd
```

### Signing

Release signing reads a properties file pointed at by the `PHOTOID_RT_KEYSTORE`
environment variable:

```
storeFile=/path/to/your.jks
storePassword=…
keyAlias=…
keyPassword=…
```

**Without that variable the build does not break** — it falls back to debug
signing, which is what a clone should do. You do not need our key to compile, and
it does not travel with the repository.

If you publish your own build, generate your own keystore. Note that Android
compares certificates on update: a build signed with a different key will not
install over an existing one.

## Before touching the code

Read, in this order:

| File | For what |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Permanent context: product constraints, storage conventions, the validation ritual, and the footguns that have broken the build before |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | Modules, data flows, on-disk formats |
| [docs/ARMADILHAS.md](docs/ARMADILHAS.md) | The mistakes that have cost builds, and how to avoid them |
| [docs/TRANSPLANTE.md](docs/TRANSPLANTE.md) | Standing the project up on a new machine |
| [docs/JORNADA.md](docs/JORNADA.md) | The history of each feature: the idea, the mistakes, the current state |
| [docs/PENDENCIAS.md](docs/PENDENCIAS.md) | What was left open, what was refused, and why |
| [HISTORICO.md](HISTORICO.md) | How this got built, release by release |

Most of these are in Portuguese — they are the working record of a project built
in Brazilian Portuguese, and translating them would freeze them.

**The validation ritual is not optional.** Every check in it was born from a
broken build. `docs/scripts_validacao.py` runs the structural checks, including
string parity across all twelve languages and the one that keeps network
primitives out of files that have no business making network calls.

## Project layout

```
app/src/main/java/com/radioterapia/ai/
├── MainActivity.kt                 simulation camera, identification
├── HomeActivity.kt                 entry point (Simulation / Treatment)
├── BaseActivity.kt                 shared toolbar and all printing logic
├── audit/AuditLogger.kt            audit log (rotated by day)
├── consent/ConsentActivity.kt
├── crop/CropActivity.kt            16:9 crop, pinch to fit
├── i18n/LocaleManager.kt           the twelve languages, and which one opens
├── patient/PatientCache.kt         patient records — key NAME | RECORD
├── pdf/PdfBuilder.kt               positioning sheet + Time-Out page
├── protocolo/                      service-specific final pages
├── quality/PhotoQualityDetector.kt dark / bright / blurred
├── rubricario/                     signature register, in named team blocks
├── scan/                           label reading (OCR by ML Kit)
├── session/SessionManager.kt       session and draft
├── sync/                           optional one-way delivery (off by default)
│   ├── MotorSync.kt                incremental scan, sent-index, time budget
│   ├── SyncWorker.kt               WorkManager: schedule and triggers
│   ├── LogConexao.kt               the connection report, made to be pasted
│   └── destino/                    SMB, WebDAV, FTP, SFTP, SAF
├── transfer/PacoteConfig.kt        export/import configuration, item by item
├── treatment/                      Treatment module (carousel, extra photos)
├── ui/                             Finish, Edit record, Edit simulation,
│                                   History, Settings, Logs, About
├── util/                           DateUtils, StorageLocal, stores
└── wizard/WizardActivity.kt        first run
```

## Known technical debt

- Around 160 Portuguese messages remain in code with interpolation
  (`"Error: $message"`). Extracting them means converting to resources with
  format arguments — mechanical work that changes many signatures at once.
- A few fixed texts remain in layouts: abbreviations, symbols and example
  values. `HardcodedText` stays as a lint warning because of them.
- `MANAGE_EXTERNAL_STORAGE` in the manifest would require formal justification
  if the app ever went to the Play Store. It is why photographs can live in
  shared storage where the clinic's sync tool can reach them.
- `PatientCache` keeps everything in a single JSON.

## Reporting a problem

Include: package version, tablet model, screen orientation, language, and — when
it involves the PDF — whether the physical label is enabled and at what
dimensions. Screenshots help a great deal.

Security issues: please see [SECURITY.md](SECURITY.md) and use private
vulnerability reporting rather than a public issue.

## Licence and name

The source code is released under the **[Apache License 2.0](LICENSE)**.

Two things travel with it and are not optional:

- **[NOTICE](NOTICE)** — section 4(d) of the licence requires it to accompany
  every redistribution. It carries the third-party credits and the declaration
  that this is not validated for clinical use. It is the same thing we ask of
  ourselves toward the components we use.
- **The name is not licensed.** Section 6 grants no trademark rights:
  "Radioterapia.AI" and "PhotoID RT", and their logos, may not be used to
  identify derived products. [TRADEMARK.md](TRADEMARK.md) also states plainly
  what a fork *may* keep — including the package namespace.

Copyright © 2026 Henrique Faria Braga. Radioterapia.AI is a trade name and a
website, not a legal entity.

If this work is useful in yours, a citation is appreciated — see
[CITATION.cff](CITATION.cff). It is asked for, not required.

Learn more at **[radioterapia.ai](https://radioterapia.ai)**.
