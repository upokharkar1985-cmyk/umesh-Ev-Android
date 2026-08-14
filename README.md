# EVSenso Tyre / Object Profile – Android Rev 0.4 BLE FINAL

Final Android transport/application build for the OpenMV AE3 + green horizontal laser-line tyre tread profiler.

## Frozen measurement target
- Measurement width: 0–420 mm
- Measurement depth: 0–120 mm
- Required system accuracy target: ±0.10 mm after optical/mechanical calibration and validation
- One-time factory calibration stored and locked in the AE3 sensor
- Android performs no repeated calibration
- Bluetooth Low Energy profile transfer from OpenMV AE3 to Android/PC
- Vehicle selection: 2 Wheeler / 4 Wheeler / 6 Wheeler / 10 Wheeler
- Wheel-by-wheel profile storage
- Individual groove depth display plus complete X/Z tread profile

## Final hardware direction
- Camera: OpenMV AE3
- Lens: OpenMV M8 Ultra-Wide 2.3 mm
- Laser: 515–530 nm green horizontal line laser, 60° fan, TTL controlled
- Sensor-head input supply: protected 10–30 VDC
- Internal rails: 5 V laser rail and regulated 3.3 V AE3 rail

## Android build
Android Gradle Plugin 8.6.1, Gradle 8.7 and compileSdk 35 are configured. GitHub Actions builds `app-debug.apk` and uploads it as an artifact.

## Accuracy note
The BLE protocol carries depth with 0.01 mm numerical resolution. Actual ±0.10 mm measurement accuracy must be proven on the complete fixed AE3 + lens + laser + enclosure assembly using a multi-point factory geometric calibration across the working width/depth envelope.
