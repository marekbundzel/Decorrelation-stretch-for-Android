# Decorrelation Stretch — Android App

An Android application that applies **decorrelation stretch** to photos taken with the camera or loaded from the gallery. Decorrelation stretch is a remote-sensing / geology technique that amplifies subtle color differences in images, revealing details that are invisible to the naked eye.

---

## Features

| Feature | Details |
|---|---|
| Image source | Camera (full-resolution) or Gallery picker |
| Algorithm | Full PCA-based decorrelation stretch in pure Java |
| Progress feedback | Progress bar + status label during processing |
| Before / After toggle | Instantly compare original and result |
| Save | Exports JPEG (95 % quality) to the device gallery |
| Android support | API 24 (Android 7.0) and above |

---

## How It Works

Decorrelation stretch removes the correlation between the R, G and B channels:

1. **Extract** pixel data into three floating-point arrays (R, G, B).
2. **Compute** the 3 × 3 RGB covariance matrix.
3. **Eigen-decompose** the covariance matrix using the Jacobi method.
4. **Project** every pixel onto the eigenvector basis (PCA rotation).
5. **Stretch** each principal component independently to [0, 255].
6. **Rotate back** to the original RGB colour space.
7. **Clip** values to [0, 255] and write the output bitmap.

The result dramatically enhances subtle spectral differences — ideal for geology, botany, art authentication, remote sensing, and general colour analysis.

---

## Project Structure

```
DecorrelationStretch/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/decorstretch/app/
│       │   ├── MainActivity.java          # UI, camera/gallery, save
│       │   └── DecorrelationStretch.java  # Pure-Java PCA algorithm
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/{colors,strings,themes}.xml
│           └── xml/file_paths.xml
├── build.gradle
└── settings.gradle
```

---

## Building the App

### Prerequisites
- Android Studio Hedgehog (2023.1) or newer
- JDK 17
- Android SDK platform 34, build-tools 34

### Steps

1. **Clone / unzip** the project.
2. Open **Android Studio → Open** and select the `DecorrelationStretch` folder.
3. Wait for Gradle sync to complete.
4. Connect a device or start an emulator (API 24+).
5. Click **Run ▶**.

### Command-line build

```bash
cd DecorrelationStretch
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Required for |
|---|---|
| `CAMERA` | Taking photos with the camera |
| `READ_MEDIA_IMAGES` (API 33+) | Picking images from the gallery |
| `READ_EXTERNAL_STORAGE` (API < 33) | Picking images from the gallery |
| `WRITE_EXTERNAL_STORAGE` (API < 29) | Saving result to gallery |

All permissions are requested at runtime before first use.

---

## Performance Notes

- Images larger than **2048 × 2048 px** are automatically sub-sampled on load to keep memory usage reasonable.
- Processing runs on a **background thread**; the UI remains responsive.
- Typical processing time on a mid-range device: ~1–3 seconds for a 12 MP image.

---

## License

MIT — see LICENSE file.
