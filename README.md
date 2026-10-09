# WallShot

Android app for the SIYI MK15. On every trigger (by time or by distance) it saves a full frame from the
camera's video stream as a JPEG on the MK15, in internal storage, on the SD card, or on both. It can also send
the SIYI "take photo" command to the camera. A CSV log (time, GPS, altitude, heading, file name) is written for
every shot.

**Status: compiled once (before the storage feature), never run on a device.** The video-frame saving code
has not been compiled or run at all. Build it, then test on the ground with the props off.

## Where the photos go
| Choice in the app | Folder |
|---|---|
| MK15 internal storage | `/storage/emulated/0/Android/data/com.drone.wallshot/files/photos/<session>/` |
| SD card in the MK15 | `<SD card>/Android/data/com.drone.wallshot/files/photos/<session>/` |
| Both | the two folders above, same file names |

- The app shows the exact paths and free space at the top of the screen, and "SD card: NOT FOUND" if Android
  does not see a card. If you pick the SD card but none is found, the app saves to internal storage instead
  and says so.
- Files are named `wall_0001_<date>_<time>.jpg`, with GPS in the EXIF when a MAVLink position was available.
- To copy the photos: connect the MK15 to a PC by USB (file transfer mode) and open the folder above, or take
  the SD card out and read it in a card reader. The folder is removed if you uninstall the app, so copy first.
- The CSV log is in `/storage/emulated/0/Android/data/com.drone.wallshot/files/`.

## Defaults (80% forward overlap, 0.3 m/s)
| Setting | Default |
|---|---|
| Standoff from wall | 3.0 m |
| Altitude | 6.0 m AGL (reference only, set in your flight plan) |
| Gimbal / heading | pitch 0 deg, nose toward the wall (set in your flight plan) |
| Corrected horizontal FOV | 90 deg (unpublished, 90 or 120 until measured) |
| Wall width per photo W | 2 x 3.0 x tan(45 deg) = 6.00 m (or type a measured W) |
| Detail | W / 1920 = 3.1 mm per pixel |
| Overlap | 80% |
| Spacing | 0.20 x W = 1.20 m |
| Interval at 0.3 m/s | 4.0 s |
| Path length | 27 m (25 m wall + 1 m each end) |
| Camera control | 192.168.144.25, UDP 37260 |
| Video stream | rtsp://192.168.144.25:8554/main.264 |
| MAVLink listen port | 14550 |
| Save to | MK15 internal storage |

## Build route A: GitHub (no installs)
1. Create a GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the Actions tab, run "Build APK", wait about 5 minutes.
3. Download the `wallshot-debug-apk` artifact and unzip it to get `app-debug.apk`.

## Build route B: Android Studio
1. Open this folder in Android Studio and let Gradle sync.
2. Build > Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`.

## Build route C: Windows cmd
Needs Java 17 (for example `winget install EclipseAdoptium.Temurin.17.JDK`, then open a NEW cmd window) and the
Android SDK folder.
```
cd /d C:\path\to\wallshot
echo sdk.dir=C\:\\Users\\YOURNAME\\AppData\\Local\\Android\\Sdk> local.properties
gradlew.bat assembleDebug
```
The first run downloads Gradle 8.9 and the Android plugin, which takes several minutes.

## Install on the MK15
1. Settings: allow installing apps from unknown sources for the file manager you will use.
2. Connect the MK15 to a PC by USB and set USB mode to file transfer. Copy `app-debug.apk` to internal storage.
3. Open the file manager on the MK15, tap the APK, Install. (If the old version is installed, installing over
   it keeps your settings.)
4. Or, with USB debugging on: `adb install -r app-debug.apk`.

## First test (ground, props off)
1. Insert the SD card (if you want to use it), power the camera, open WallShot. Check the storage lines at the
   top: the SD card should show a path and free space.
2. Press **Test video frame save**. Wait up to 25 s. The pop-up names the file and the folder(s) it was written
   to. Open one on a PC and check the picture and its size in pixels. If it says "No video", the stream URL or
   the camera network is the problem. Try the URL in the SIYI app or VLC first.
3. Press **Test camera command** to check the camera's own storage, if it has any. This is optional.
4. Measure W, type it into the app, choose the storage, press **Start capture**, wait two intervals, press
   **Stop capture**. The status line shows shots, saved and failed.
5. Compare the number of JPEG files with the "saved" count and the CSV rows.

## Using it in flight
- The video connection takes several seconds to start. Press **Start capture** before takeoff or at least
  15 s before the drone reaches the wall row, otherwise the first photos fail (the CSV shows them as not saved).
- Photos taken during the climb or return are extra. Use the CSV timestamps to tell them from the wall pass.
- Keep WallShot running in the background. Do not close it from the recent-apps list.

## Known limits
- Photos are decoded video frames (about 1920 x 1080), not still photos from the sensor.
- The app asks the camera's built-in player for RTSP. If the MK15's Android cannot play that stream, the test
  will say "No video" and nothing is saved by the app.
- UDP has no delivery guarantee. The `camera_ack` column shows whether the camera answered each command.
- Distance mode and GPS in the EXIF need MAVLink position on UDP 14550. QGroundControl may already hold that
  port, in which case the app gets no position. Time mode does not need it, and photos are then saved without GPS.
- Saving one frame takes up to a few hundred milliseconds. The app waits up to 2.5 s for a frame.
