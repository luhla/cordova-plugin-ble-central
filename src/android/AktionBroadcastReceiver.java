

package com.megster.cordova.ble.central;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.location.LocationManager;
import android.os.ParcelUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;

import android.provider.Settings;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.*;

import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL;
import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE;
import static android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED;
import static android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE;

/*
 * Prijima vysledky BLE scanu spusteneho pres PendingIntent (backgroundScanMode).
 * Diky tomu OS probudi proces az kdyz je v dosahu zarizeni odpovidajici ScanFilteru,
 * misto aby aplikace musela drzet scan sama.
 *
 * Omezeni: pokud byl proces zabity, receiver ho sice nastartuje, ale JS vrstva (a tim i
 * handshake) v tu chvili nezije - vysledek se pak jen zaloguje. Pokrytym scenarem je
 * "proces zije, aplikace je na pozadi / telefon zamceny".
 */
public class AktionBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "AktionBR";

    @Override
    public void onReceive(Context context, Intent intent) {
        int bleCallbackType = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1);
        if (bleCallbackType == -1) {
            return;
        }
        ArrayList<ScanResult> scanResults = intent.getParcelableArrayListExtra(
                                        BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        if (scanResults == null || scanResults.isEmpty()) {
            LOG.d(TAG, "Passive background scan without results, callback type: " + bleCallbackType);
            return;
        }

        BLECentralPlugin plugin = BLECentralPlugin.getInstance();
        if (plugin == null) {
            LOG.d(TAG, "Passive background scan: plugin instance is gone, " + scanResults.size() + " result(s) dropped");
            return;
        }

        LOG.d(TAG, "Passive background scan callback type: " + bleCallbackType + ", results: " + scanResults.size());
        for (ScanResult result : scanResults) {
            plugin.handleBackgroundScanResult(result);
        }
    }
}