# Third-party components

> **Generated from `app/build.gradle` by `tools/gerar_third_party.py`.**
> Do not edit by hand — a hand-written dependency list is out of date the
> day after someone bumps a version. The generator fails if a dependency
> has no declared licence, so a new one cannot slip through unnoticed.

The authoritative attribution notice is [NOTICE](NOTICE); section 4(d) of
the [Apache License 2.0](LICENSE) requires it to travel with every
redistribution. This file is the detailed version of the same list.

## Not free software, and shipped inside the APK

**Google ML Kit** is proprietary. It is not redistributed as source here —
it is resolved as a binary dependency at build time — and the bundled
variants embed a native recognition engine of roughly **57 MB across four
ABIs**, which is most of the APK's size.

This matters beyond attribution: a combined work that bundles a proprietary
native library is not distributable under a copyleft licence without an
additional permission. It is one reason this project is Apache-2.0.

**Declared position of the author:** Google publishes ML Kit as a
general-purpose developer SDK. It does not declare it a medical device, does
not intend it for clinical use, and publishes no specific statement either
way. We record the absence rather than attribute a position nobody wrote.

## Distributed in the APK

| Component | Version | Licence | Holder |
|---|---|---|---|
| `androidx.appcompat:appcompat` | 1.6.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.camera:camera-camera2` | 1.3.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.camera:camera-core` | 1.3.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.camera:camera-lifecycle` | 1.3.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.camera:camera-view` | 1.3.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Apache-2.0 | The Android Open Source Project |
| `androidx.core:core-ktx` | 1.12.0 | Apache-2.0 | The Android Open Source Project |
| `androidx.documentfile:documentfile` | 1.0.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.exifinterface:exifinterface` | 1.3.7 | Apache-2.0 | The Android Open Source Project |
| `androidx.recyclerview:recyclerview` | 1.3.2 | Apache-2.0 | The Android Open Source Project |
| `androidx.room:room-compiler` | 2.6.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.room:room-ktx` | 2.6.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.room:room-runtime` | 2.6.1 | Apache-2.0 | The Android Open Source Project |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | Apache-2.0 | The Android Open Source Project |
| `androidx.viewpager2:viewpager2` | 1.1.0 | Apache-2.0 | The Android Open Source Project |
| `androidx.work:work-runtime-ktx`<br><sub>fila da sincronizacao, sobrevive a reinicio</sub> | 2.9.0 | Apache-2.0 | The Android Open Source Project |
| `com.github.bumptech.glide:glide` | 4.16.0 | BSD-2-Clause e Apache-2.0 | Google Inc. e Bump Technologies Inc. |
| `com.github.mwiede:jsch`<br><sub>SFTP. Fork mantido do JSch, que parou em 2018</sub> | 0.2.17 | Revised BSD (3-Clause) | Atsuhiko Yamanaka, ymnk e Matthias Wiedemann |
| `com.google.android.gms:play-services-mlkit-document-scanner`<br><sub>NAO e software livre. Resolvida pelo Google Play services.</sub> | 16.0.0-beta1 | Termos das APIs do Google (proprietaria, uso gratuito) | Google LLC |
| `com.google.android.material:material` | 1.11.0 | Apache-2.0 | Google LLC |
| `com.google.mlkit:barcode-scanning`<br><sub>NAO e software livre. Embarcada no APK como biblioteca nativa.</sub> | 17.2.0 | Termos do ML Kit (proprietaria, uso gratuito) | Google LLC |
| `com.google.mlkit:text-recognition`<br><sub>NAO e software livre. Embarcada no APK como biblioteca nativa.</sub> | 16.0.1 | Termos do ML Kit (proprietaria, uso gratuito) | Google LLC |
| `com.hierynomus:smbj`<br><sub>cliente SMB</sub> | 0.13.0 | Apache-2.0 | Jeroen van Erp |
| `com.journeyapps:zxing-android-embedded` | 4.3.0 | Apache-2.0 | Journey Mobile, Inc. |
| `com.opencsv:opencsv`<br><sub>leitura da base de pacientes</sub> | 5.9 | Apache-2.0 | OpenCSV contributors |
| `commons-net:commons-net`<br><sub>FTP e FTPS na sincronizacao</sub> | 3.10.0 | Apache-2.0 | The Apache Software Foundation |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Apache-2.0 | JetBrains s.r.o. |

## Test only — not distributed

| Component | Version | Licence | Holder |
|---|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 | JUnit contributors |
| `org.json:json` | 20231013 | JSON License | JSON.org |

## Toolchain

Gradle 8.13 · Android Gradle Plugin 8.13.2 · Kotlin 1.9.20 · JDK 21 ·
compileSdk 34 · minSdk 24. The Gradle wrapper JAR is versioned so a clone
builds without installing a matching Gradle by hand.
