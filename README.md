# CadencePower

Android app that turns a BLE cadence sensor (e.g. **iGPSport CAD 70**) into a
**virtual cycling power meter** that Zwift (and any standard cycling app) can pair
with. Designed for indoor spinning bikes that have no power source — the goal is
just to make your **Zwift avatar move** in proportion to how hard you pedal.

> ⚠️ **Honest disclaimer:** the watts reported are *estimated* from cadence using
> a generic trainer power curve, not measured. Don't use this for FTP tests or
> Zwift races. Use it for casual rides in Watopia.

## How it works

```
[CAD 70]  --BLE-->  [Phone running CadencePower]  --BLE-->  [Zwift]
 cadence              power = curve(cadence × gear)         power meter
```

1. Reads cadence from a CSC-profile sensor (UUID `0x1816`).
2. Maps cadence × `metersPerCrankRev` (slider in the UI) → wheel speed.
3. Applies the Kurt Kinetic Road Machine curve:
   `P = 5.244820·v + 0.019168·v³` (v in m/s).
4. Exposes itself as a BLE peripheral implementing the Cycling Power Service
   (UUID `0x1818`) so Zwift sees it as `CadencePower`.

## Hardware tested

- Sensor: iGPSport CAD 70 (BLE)
- Phone: Samsung Galaxy S22 (Android)
- HR: Garmin in broadcast mode → pair directly to Zwift, *not* through this app

## Building the APK

You don't need Android Studio. Push the repo to GitHub and let Actions build it.

### Option 1 — let GitHub Actions build it

1. Create an empty repo at `https://github.com/pedromperezc/CadencePower`.
2. From `C:\android\CadencePower` run:
   ```powershell
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/pedromperezc/CadencePower.git
   git push -u origin main
   ```
3. Open the **Actions** tab on GitHub. The workflow `Build APK` runs automatically.
4. When green, open the run → **Artifacts** → download `CadencePower-debug-apk`.
5. Unzip → copy `app-debug.apk` to your phone → tap to install (allow "install
   from unknown sources" if prompted).

### Option 2 — local build (only if you install Android SDK + JDK 21)

```powershell
.\gradlew.bat :app:assembleDebug
# APK lands at app\build\outputs\apk\debug\app-debug.apk
```

## Pairing in Zwift

1. Open Zwift, go to the pairing screen.
2. **Power Source** → look for `CadencePower` → pair.
3. **Cadence** → pair the CAD 70 directly to Zwift (not through this app).
4. **Heart Rate** → pair the Garmin directly to Zwift (broadcast mode).
5. Ride.

## Tuning the avatar speed

Use the **virtual gear** slider in the app:
- 3 m/rev → very easy, low watts even at high rpm
- 6 m/rev → road bike in mid gear
- 10 m/rev → big gear, hard

Find a value that gives you a power range you find natural (typically 2.5–6.0
for indoor bikes).

## Files

| Path | What it does |
|---|---|
| [app/src/main/java/com/pedroperez/cadencepower/ble/CadenceScanner.kt](app/src/main/java/com/pedroperez/cadencepower/ble/CadenceScanner.kt) | BLE central — reads CAD 70 |
| [app/src/main/java/com/pedroperez/cadencepower/ble/PowerAdvertiser.kt](app/src/main/java/com/pedroperez/cadencepower/ble/PowerAdvertiser.kt) | BLE peripheral — emits Cycling Power |
| [app/src/main/java/com/pedroperez/cadencepower/model/PowerEstimator.kt](app/src/main/java/com/pedroperez/cadencepower/model/PowerEstimator.kt) | cadence → watts curve |
| [app/src/main/java/com/pedroperez/cadencepower/MainActivity.kt](app/src/main/java/com/pedroperez/cadencepower/MainActivity.kt) | Compose UI |
| [.github/workflows/build.yml](.github/workflows/build.yml) | CI build |
