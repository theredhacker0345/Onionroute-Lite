Phone screenshots for F-Droid listing.

Files (1080x2400, Pixel 6 emulator, captured via CI):
  1_home_disconnected.png — Home screen, DISCONNECTED state (initial launch)
  2_home_connecting.png   — Home screen, CONNECTING 30% (mid-bootstrap, shows progress)
  3_home_connected.png    — Home screen, CONNECTED state with Tor exit IP + bandwidth usage

To regenerate these screenshots locally:
  1. Build the debug APK: ./gradlew assembleFdroidDebug
  2. Install on a Pixel 6 emulator (or similar 1080x2400 device)
  3. adb shell am start -n org.onionroute.lite/.MainActivity
  4. adb shell input tap 540 640   # tap the Connect button
  5. adb shell screencap -p /sdcard/shot.png
  6. adb pull /sdcard/shot.png

F-Droid accepts any phone-aspect-ratio PNG; 1080x2400 is the modern
standard and will be scaled down for display on the F-Droid website
and in the F-Droid Android client.
