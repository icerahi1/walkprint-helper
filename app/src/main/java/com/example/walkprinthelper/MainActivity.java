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

        new Thread(() -> {
            try {
                // 1. Decode PNG Base64 into Android Bitmap
                byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

                // 2. Convert Bitmap to WalkPrint 1-bit MSB ByteArray
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
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Printer not paired! Pair YHK-8D55 first.", Toast.LENGTH_LONG).show());
                    finish();
                    return;
                }

                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Printing to " + printer.getName() + "...", Toast.LENGTH_SHORT).show());

                BluetoothSocket socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                OutputStream os = socket.getOutputStream();

                // 4. Send PROPRIETARY WalkPrint Commands
                // Speed = 32
                os.write(makeCommand(189, new byte[]{(byte)32}));
                // Energy = 24000 (0x5DC0)
                os.write(makeCommand(175, new byte[]{(byte)0xC0, (byte)0x5D, 0x00, 0x00}));
                // Apply Energy
                os.write(makeCommand(190, new byte[]{1}));

                // Blast Image in 128-byte packets wrapped in Bitmap command (162)
                for (int i = 0; i < pixels.length; i += 128) {
                    int length = Math.min(128, pixels.length - i);
                    byte[] slice = new byte[length];
                    System.arraycopy(pixels, i, slice, 0, length);
                    
                    os.write(makeCommand(162, slice));
                    Thread.sleep(10); // Slight flow control
                }

                // Finish Feed = 100
                byte[] feed = new byte[]{(byte)100, 0};
                os.write(makeCommand(161, feed));
                
                os.flush();
                Thread.sleep(500);
                socket.close();

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Print Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                finish(); // Return to PWA
            }
        }).start();
    }

    private byte crc8(byte[] payload) {
        int crc = 0;
        for (byte b : payload) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = ((crc << 1) ^ 0x07) & 0xFF;
                } else {
                    crc = (crc << 1) & 0xFF;
                }
            }
        }
        return (byte) crc;
    }

    private byte[] makeCommand(int cmdId, byte[] payload) {
        byte[] cmd = new byte[payload.length + 7];
        cmd[0] = 0x51; // 81
        cmd[1] = 0x78; // 120
        cmd[2] = (byte) cmdId;
        cmd[3] = 0x00; // Transfer type
        cmd[4] = (byte) (payload.length & 0xFF);
        cmd[5] = (byte) ((payload.length >> 8) & 0xFF);
        System.arraycopy(payload, 0, cmd, 6, payload.length);
        cmd[cmd.length - 2] = crc8(payload);
        cmd[cmd.length - 1] = (byte) 0xFF;
        return cmd;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                String base64Image = getIntent().getData().getQueryParameter("data");
                printImage(base64Image);
            } else {
                Toast.makeText(this, "Bluetooth Permission Denied", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
