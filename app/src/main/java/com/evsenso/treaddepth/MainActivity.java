package com.evsenso.treaddepth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 3101;
    private static final UUID SERVICE_UUID = UUID.fromString("7f390001-9d68-4b9a-a65d-455653454e53");
    private static final UUID DATA_UUID = UUID.fromString("7f390002-9d68-4b9a-a65d-455653454e53");
    private static final UUID CONTROL_UUID = UUID.fromString("7f390003-9d68-4b9a-a65d-455653454e53");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private TextView connectionStatus;
    private TextView currentWheelTitle;
    private TextView measurementSummary;
    private Spinner vehicleSpinner;
    private Spinner wheelSpinner;
    private LinearLayout grooveContainer;
    private ProfileView profileView;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic dataChar;
    private BluetoothGattCharacteristic controlChar;
    private boolean scanning;
    private boolean connected;
    private String calibrationStatus = "UNKNOWN";

    private int pendingFrame = -1;
    private double[] pendingProfile;
    private double pendingX0;
    private double pendingDx = 0.5;
    private final List<ProfileView.GrooveMark> pendingGrooves = new ArrayList<>();

    private final Map<String, WheelMeasurement> stored = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final String[] VEHICLES = {"2 Wheeler", "4 Wheeler", "6 Wheeler", "10 Wheeler"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectionStatus = findViewById(R.id.connectionStatus);
        currentWheelTitle = findViewById(R.id.currentWheelTitle);
        measurementSummary = findViewById(R.id.measurementSummary);
        vehicleSpinner = findViewById(R.id.vehicleSpinner);
        wheelSpinner = findViewById(R.id.wheelSpinner);
        grooveContainer = findViewById(R.id.grooveContainer);
        profileView = findViewById(R.id.profileView);
        Button connectButton = findViewById(R.id.connectButton);
        Button measureButton = findViewById(R.id.measureButton);
        Button demoButton = findViewById(R.id.demoButton);
        Button saveButton = findViewById(R.id.saveButton);

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        vehicleSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, VEHICLES));
        vehicleSpinner.setOnItemSelectedListener(new SimpleSelection() {
            @Override public void selected() { updateWheelOptions(); }
        });
        wheelSpinner.setOnItemSelectedListener(new SimpleSelection() {
            @Override public void selected() { showSelectedWheel(); }
        });
        updateWheelOptions();

        connectButton.setOnClickListener(v -> beginConnect());
        measureButton.setOnClickListener(v -> startMeasurement());
        demoButton.setOnClickListener(v -> loadDemo());
        saveButton.setOnClickListener(v -> saveCsv());
        updateConnectionUi("DISCONNECTED");
    }

    private abstract static class SimpleSelection implements android.widget.AdapterView.OnItemSelectedListener {
        abstract void selected();
        @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) { selected(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
    }

    private String vehicle() {
        Object o = vehicleSpinner.getSelectedItem();
        return o == null ? "2 Wheeler" : o.toString();
    }

    private String wheel() {
        Object o = wheelSpinner.getSelectedItem();
        return o == null ? "Front" : o.toString();
    }

    private String key() { return vehicle() + " | " + wheel(); }

    private void updateWheelOptions() {
        String[] wheels;
        switch (vehicle()) {
            case "2 Wheeler":
                wheels = new String[]{"Front", "Rear"};
                break;
            case "4 Wheeler":
                wheels = new String[]{"Front Left", "Front Right", "Rear Left", "Rear Right"};
                break;
            case "6 Wheeler":
                wheels = new String[]{"Front Left", "Front Right", "Rear Left Outer", "Rear Left Inner", "Rear Right Inner", "Rear Right Outer"};
                break;
            default:
                wheels = new String[]{"Front Left", "Front Right", "Rear Axle 1 Left Outer", "Rear Axle 1 Left Inner", "Rear Axle 1 Right Inner", "Rear Axle 1 Right Outer", "Rear Axle 2 Left Outer", "Rear Axle 2 Left Inner", "Rear Axle 2 Right Inner", "Rear Axle 2 Right Outer"};
                break;
        }
        wheelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, wheels));
        showSelectedWheel();
    }

    private void showSelectedWheel() {
        currentWheelTitle.setText(vehicle() + " • " + wheel());
        WheelMeasurement m = stored.get(key());
        if (m == null) {
            profileView.clear();
            grooveContainer.removeAllViews();
            measurementSummary.setText("No measurement stored for this wheel. Connect the AE3 sensor and press START TREAD MEASUREMENT.");
        } else {
            display(m);
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void beginConnect() {
        if (adapter == null) {
            toast("Bluetooth is not available on this device.");
            return;
        }
        if (!adapter.isEnabled()) {
            toast("Please enable Bluetooth first.");
            return;
        }
        if (!hasBlePermissions()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMISSIONS);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSIONS);
            }
            return;
        }
        scanForSensor();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && hasBlePermissions()) scanForSensor();
    }

    @SuppressWarnings("MissingPermission")
    private void scanForSensor() {
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("BLE scanner unavailable.");
            return;
        }
        scanning = true;
        updateConnectionUi("SCANNING FOR EVSENSO-AE3…");
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(java.util.Collections.singletonList(filter), settings, scanCallback);
        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                updateConnectionUi("SENSOR NOT FOUND");
                toast("EVSENSO-AE3 not found. Check sensor power and BLE firmware.");
            }
        }, 12000);
    }

    @SuppressWarnings("MissingPermission")
    private void stopScan() {
        if (scanner != null && scanning) scanner.stopScan(scanCallback);
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            stopScan();
            runOnUiThread(() -> updateConnectionUi("CONNECTING…"));
            if (d != null) connectGatt(d);
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            runOnUiThread(() -> updateConnectionUi("SCAN ERROR " + errorCode));
        }
    };

    @SuppressWarnings("MissingPermission")
    private void connectGatt(BluetoothDevice device) {
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                runOnUiThread(() -> updateConnectionUi("CONNECTED • discovering services…"));
                if (hasBlePermissions()) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                dataChar = null;
                controlChar = null;
                runOnUiThread(() -> updateConnectionUi("DISCONNECTED"));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SERVICE_UUID);
            if (s == null) {
                runOnUiThread(() -> updateConnectionUi("CONNECTED • EVSENSO service missing"));
                return;
            }
            dataChar = s.getCharacteristic(DATA_UUID);
            controlChar = s.getCharacteristic(CONTROL_UUID);
            if (dataChar == null || controlChar == null) {
                runOnUiThread(() -> updateConnectionUi("CONNECTED • BLE characteristics missing"));
                return;
            }
            if (hasBlePermissions()) {
                g.requestMtu(247);
                g.setCharacteristicNotification(dataChar, true);
                BluetoothGattDescriptor d = dataChar.getDescriptor(CCCD_UUID);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            runOnUiThread(() -> {
                updateConnectionUi("CONNECTED • EVSENSO-AE3");
                handler.postDelayed(() -> writeCommand("STATUS"), 500);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] p = c.getValue();
            if (p != null) handlePacket(p);
        }
    };

    @SuppressWarnings("MissingPermission")
    private void writeCommand(String cmd) {
        if (!connected || gatt == null || controlChar == null || !hasBlePermissions()) {
            toast("AE3 sensor is not connected.");
            return;
        }
        controlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        controlChar.setValue(cmd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        gatt.writeCharacteristic(controlChar);
    }

    private void startMeasurement() {
        if (!connected) {
            toast("Connect the OpenMV AE3 sensor first.");
            return;
        }
        if (!"LOCKED".equals(calibrationStatus)) {
            toast("Factory calibration is not locked in the sensor. Precision measurement is blocked.");
            return;
        }
        measurementSummary.setText("Measurement requested from AE3…");
        writeCommand("MEASURE");
    }

    private void handlePacket(byte[] p) {
        if (p.length == 0) return;
        int type = p[0] & 0xFF;
        ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        b.get();
        try {
            if (type == 0x01) {
                String s = new String(p, 1, p.length - 1, java.nio.charset.StandardCharsets.UTF_8);
                if (s.contains("CAL=LOCKED")) calibrationStatus = "LOCKED";
                else if (s.contains("CAL=MISSING") || s.contains("CALIBRATION_MISSING")) calibrationStatus = "MISSING";
                runOnUiThread(() -> {
                    updateConnectionUi("CONNECTED • EVSENSO-AE3");
                    measurementSummary.setText("Sensor: " + s);
                });
            } else if (type == 0x02 && p.length >= 9) {
                pendingFrame = b.getShort() & 0xFFFF;
                int n = b.getShort() & 0xFFFF;
                pendingX0 = (b.getShort() & 0xFFFF) / 10.0;
                pendingDx = (b.getShort() & 0xFFFF) / 100.0;
                pendingProfile = new double[n];
                java.util.Arrays.fill(pendingProfile, Double.NaN);
                pendingGrooves.clear();
            } else if (type == 0x03 && p.length >= 6) {
                int fid = b.getShort() & 0xFFFF;
                int off = b.getShort() & 0xFFFF;
                int count = b.get() & 0xFF;
                if (fid != pendingFrame || pendingProfile == null) return;
                for (int i = 0; i < count && b.remaining() >= 2; i++) {
                    int raw = b.getShort() & 0xFFFF;
                    if (off + i < pendingProfile.length) pendingProfile[off + i] = raw == 0xFFFF ? Double.NaN : raw / 100.0;
                }
            } else if (type == 0x04 && p.length >= 10) {
                int fid = b.getShort() & 0xFFFF;
                b.get();
                double x = (b.getShort() & 0xFFFF) / 10.0;
                double depth = (b.getShort() & 0xFFFF) / 100.0;
                double width = (b.getShort() & 0xFFFF) / 10.0;
                if (fid == pendingFrame) pendingGrooves.add(new ProfileView.GrooveMark(x, depth, width));
            } else if (type == 0x05 && p.length >= 5) {
                int fid = b.getShort() & 0xFFFF;
                int grooveCount = b.get() & 0xFF;
                int quality = b.get() & 0xFF;
                if (fid == pendingFrame && pendingProfile != null) {
                    WheelMeasurement m = new WheelMeasurement(pendingProfile.clone(), pendingX0, pendingDx, new ArrayList<>(pendingGrooves), quality, new Date());
                    stored.put(key(), m);
                    runOnUiThread(() -> {
                        display(m);
                        toast("Profile received: " + grooveCount + " grooves • quality " + quality + "%");
                    });
                }
            }
        } catch (Exception ex) {
            runOnUiThread(() -> measurementSummary.setText("BLE packet error: " + ex.getMessage()));
        }
    }

    private void loadDemo() {
        int n = 841;
        double[] z = new double[n];
        List<ProfileView.GrooveMark> grooves = new ArrayList<>();
        double[] centers = {65, 145, 225, 315};
        double[] depths = {6.8, 5.9, 7.4, 4.7};
        for (int i = 0; i < n; i++) {
            double x = i * 0.5;
            double d = 0.25 + 0.08 * Math.sin(x / 18.0);
            for (int k = 0; k < centers.length; k++) {
                double sigma = 6.0 + k;
                double q = (x - centers[k]) / sigma;
                d += depths[k] * Math.exp(-0.5 * q * q);
            }
            z[i] = d;
        }
        for (int k = 0; k < centers.length; k++) grooves.add(new ProfileView.GrooveMark(centers[k], depths[k], 12 + k));
        WheelMeasurement m = new WheelMeasurement(z, 0, 0.5, grooves, 98, new Date());
        stored.put(key(), m);
        display(m);
        toast("Demo profile loaded. Demo values are not real measurements.");
    }

    private void display(WheelMeasurement m) {
        profileView.setProfile(m.profile, m.x0, m.dx, m.grooves);
        grooveContainer.removeAllViews();
        double min = Double.POSITIVE_INFINITY;
        double max = 0;
        for (int i = 0; i < m.grooves.size(); i++) {
            ProfileView.GrooveMark g = m.grooves.get(i);
            min = Math.min(min, g.depthMm);
            max = Math.max(max, g.depthMm);
            TextView t = new TextView(this);
            t.setText(String.format(Locale.US, "Groove %d     %.2f mm     X %.1f mm     width %.1f mm", i + 1, g.depthMm, g.xMm, g.widthMm));
            t.setTextSize(17f);
            t.setPadding(12, 10, 12, 10);
            grooveContainer.addView(t);
        }
        if (m.grooves.isEmpty()) min = 0;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(m.time);
        measurementSummary.setText(String.format(Locale.US,
                "Wheel: %s\nGrooves: %d\nMinimum groove: %.2f mm\nMaximum groove: %.2f mm\nProfile quality: %d%%\nRange: 420 mm width × 120 mm depth\nTime: %s",
                wheel(), m.grooves.size(), min, max, m.quality, time));
    }

    private void saveCsv() {
        WheelMeasurement m = stored.get(key());
        if (m == null) {
            toast("No measurement to save for this wheel.");
            return;
        }
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "EVSENSO_Tread_" + stamp + ".csv");
            try (FileWriter w = new FileWriter(f)) {
                w.write("vehicle,wheel,x_mm,depth_mm\n");
                for (int i = 0; i < m.profile.length; i++) {
                    double x = m.x0 + i * m.dx;
                    double d = m.profile[i];
                    w.write(String.format(Locale.US, "%s,%s,%.2f,%s\n", vehicle(), wheel(), x, Double.isNaN(d) ? "" : String.format(Locale.US, "%.2f", d)));
                }
                w.write("\nGroove,Center_X_mm,Depth_mm,Width_mm\n");
                for (int i = 0; i < m.grooves.size(); i++) {
                    ProfileView.GrooveMark g = m.grooves.get(i);
                    w.write(String.format(Locale.US, "%d,%.1f,%.2f,%.1f\n", i + 1, g.xMm, g.depthMm, g.widthMm));
                }
            }
            toast("Saved: " + f.getAbsolutePath());
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private void updateConnectionUi(String state) {
        connectionStatus.setText("Sensor: " + state + "\nCalibration: " + calibrationStatus + "\nTarget: ±0.10 mm • 420 mm W × 120 mm D");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        stopScan();
        if (gatt != null && hasBlePermissions()) {
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        super.onDestroy();
    }

    private static class WheelMeasurement {
        final double[] profile;
        final double x0;
        final double dx;
        final List<ProfileView.GrooveMark> grooves;
        final int quality;
        final Date time;
        WheelMeasurement(double[] profile, double x0, double dx, List<ProfileView.GrooveMark> grooves, int quality, Date time) {
            this.profile = profile;
            this.x0 = x0;
            this.dx = dx;
            this.grooves = grooves;
            this.quality = quality;
            this.time = time;
        }
    }
}
