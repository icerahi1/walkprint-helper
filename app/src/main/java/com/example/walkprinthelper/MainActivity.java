package com.example.walkprinthelper;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri data = intent.getData();
            if ("walkprint".equals(data.getScheme())) {
                String base64Image = data.getQueryParameter("data");
                if (base64Image != null && !base64Image.isEmpty()) {
                    printImage(base64Image);
                } else {
                    finish(); // No data, close quietly
                }
            } else {
                finish();
            }
        } else {
            // App launched normally, just show a message and close
            Toast.makeText(this, "WalkPrint Helper is running in background mode.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void printImage(String base64Image) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }

        try {
            // 1. Decode PNG Base64 into Android Bitmap
            byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            // 2. Convert Bitmap to ESC/POS 1-bit MSB ByteArray
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int widthBytes = (int) Math.ceil(width / 8.0);
            byte[] pixels = new byte[widthBytes * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);
                    if ((r + g + b) / 3 < 210) {
                        int byteIdx = (y * widthBytes) + (x / 8);
                        int bit = 7 - (x % 8);
                        pixels[byteIdx] |= (1 << bit);
                    }
                }
            }

            // 3. Connect to Printer via Classic Bluetooth (RFCOMM)
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice printer = null;

            Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName() != null && (device.getName().contains("YHK") || device.getName().contains("WalkPrint"))) {
                    printer = device;
                    break;
                }
            }

            if (printer == null) {
                Toast.makeText(this, "Printer not paired! Pair YHK-8D55 in Settings first.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            Toast.makeText(this, "Printing to " + printer.getName() + "...", Toast.LENGTH_SHORT).show();

            // Connect using the standard Serial Port Profile (Classic Bluetooth)
            BluetoothSocket socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            OutputStream os = socket.getOutputStream();

            // 4. Send WalkPrint Commands (Same proven byte sequence)
            os.write(new byte[]{0x1E, 0x47, 0x03});
            Thread.sleep(200);
            os.write(new byte[]{0x1D, 0x67, 0x39});
            Thread.sleep(200);
            os.write(new byte[]{0x1D, 0x67, 0x69});
            Thread.sleep(200);
            
            os.write(new byte[]{0x1B, 0x40}); // Initialize
            Thread.sleep(50);
            os.write(new byte[]{0x1B, 0x37, 0x07, (byte)0x80, 0x02}); // Energy
            Thread.sleep(50);
            os.write(new byte[]{0x1D, 0x49, (byte)0xF0, 0x19}); // Start Print
            Thread.sleep(50);

            // Image Header
            byte[] header = new byte[]{
                    0x1D, 0x76, 0x30, 0x00,
                    (byte) (widthBytes & 0xFF), (byte) ((widthBytes >> 8) & 0xFF),
                    (byte) (height & 0xFF), (byte) ((height >> 8) & 0xFF)
            };
            os.write(header);
            
            // Blast entire image payload instantly! Classic Bluetooth RFCOMM handles flow control automatically.
            os.write(pixels);
            os.flush();

            Thread.sleep(500);
            os.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A}); // Feeds
            os.flush();

            socket.close();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Print Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            finish(); // Silently return user to the Next.js PWA!
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Try again after getting permission
                String base64Image = getIntent().getData().getQueryParameter("data");
                printImage(base64Image);
            } else {
                Toast.makeText(this, "Bluetooth Permission Denied", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
