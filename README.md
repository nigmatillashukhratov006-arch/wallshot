# WallShot v1.2

Android app for the SIYI MK15. On every trigger (by time or by distance) it saves a full frame from the
camera's video stream as a JPEG on the MK15, in internal storage, on the SD card, or on both. It can also send
the SIYI "take photo" command to the camera. A CSV log (time, GPS, altitude, heading, file name) is written for
every shot.

**Status:** v1.0 was run on an MK15: the timer, the foreground service and the CSV log work, but no photo was
saved because the video player failed with "video error 1/-2147483648" (Android's MediaPlayer cannot open the
camera's RTSP stream). v1.1 replaced that player with ExoPlayer and added a stream-check button. v1.2 adds the
automatic SD card copy and makes the video link more robust. v1.2 has been type-checked and its storage and
frame-conversion code tested off the device; it has not been run on an MK15 yet.

## What changed in v1.2
- **New default save mode "Automatic"**: every photo is saved to MK15 internal storage, and also to the SD card
  whenever a card is inserted. The card is re-checked every 5 s during capture, so a card inserted mid-session
  starts receiving copies right away.
- **Automatic copy to the SD card**: when a card is inserted while the app is open (or is already in when the
  app starts), every photo and CSV log that is not yet on the card is copied to it. The same copy runs when
  capture stops. Files already on the card are skipped, so nothing is copied twice.
- **"Copy all photos to SD card now"** button for a manual copy, with a report of how many files were copied.
- The storage lines show the photo count on the MK15 and on the card, so you can see they match.
- The video stream reconnects by itself 3 s after an error or a dropped link, instead of failing every shot
  until capture is restarted. The status line shows how many times it reconnected.
- Capture waits up to 30 s for the first video frame before taking the first photo, so the first shots are
  no longer lost while the stream starts.
- The status line shows the name of the last saved photo.
- Frame conversion to JPEG is faster (whole rows are copied at once), same image output.

## Video stream: what must be true for photos to be saved
- The camera stream must be **H.264**. ExoPlayer's RTSP support covers H.264 only (and the stream description
  must contain SPS/PPS data). It cannot decode H.265.
- Press **1. Check video stream (RTSP)** first. It asks the camera to describe the stream and tells you the codec
  and whether the app can decode it. If it says H265, set the camera's video encoding to H.264 (SIYI Assistant
  or the SIYI app's camera settings) and check again.
- Tick or untick **Use RTSP over TCP**. TCP is on by default. If the stream will not open, try it off.
- Stream address: `rtsp://192.168.144.25:8554/main.264` (main stream) or `.../sub.264` (sub stream).

## Where the photos go
| Choice in the app | Folder |
|---|---|
| Automatic (default) | MK15 internal storage always, plus the SD card whenever one is inserted (folders below) |
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
| Video stream | rtsp://192.168.144.25:8554/main.264, over TCP |
| MAVLink listen port | 14550 |
| Save to | Automatic (MK15 storage + SD card when inserted) |

## Build route A: GitHub (no installs)
1. Upload everything in this folder to your repository (keep the `.github` folder).
2. Open the Actions tab, run "Build APK", wait about 5 minutes.
3. Download the `wallshot-debug-apk` artifact and unzip it to get `app-debug.apk`.

## Build route B: Android Studio
1. Open this folder in Android Studio and let Gradle sync.
2. Build > Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`.

## Build route C: Windows
Run `build-wallshot.bat` (next to `wallshot-source.zip` in Downloads). It installs Java 17 and the Android SDK if
needed, builds, and copies the result to `Downloads\WallShot.apk`. The first build downloads the video library
(ExoPlayer) from Google's Maven server, so it needs internet.

## Install on the MK15
1. Settings: allow installing apps from unknown sources for the file manager you will use.
2. Copy `app-debug.apk` to the MK15 and tap it in the file manager, or: `adb install -r app-debug.apk`.
3. If Android refuses the update (different signing key from the old install), uninstall the old WallShot first.
   Settings are lost, so retype them.

## First test (ground, props off)
1. Insert the SD card (if you want to use it), power the camera, open WallShot. Check the storage lines at the
   top: the SD card should show a path and free space.
2. Press **1. Check video stream (RTSP)**. Read the verdict. Photograph or copy the pop-up text if anything is wrong.
3. Press **2. Test video frame save**. Wait up to 25 s. The pop-up names the file and the folder(s) it was written
   to, or gives the exact video error. Open one on a PC and check the picture and its size in pixels.
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
- H.265 streams cannot be decoded by this app (see above).
- UDP has no delivery guarantee. The `camera_ack` column shows whether the camera answered each command.
- Distance mode and GPS in the EXIF need MAVLink position on UDP 14550. QGroundControl may already hold that
  port, in which case the app gets no position. Time mode does not need it, and photos are then saved without GPS.
- Saving one frame takes up to a few hundred milliseconds. The app waits up to 2.5 s for a frame.
