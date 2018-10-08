/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gmurru.bleframework;

import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * Service for managing connection and data communication with a GATT server
 * hosted on a given Bluetooth LE device.
 */
public class RBLService extends Service {
	private final static String TAG = RBLService.class.getSimpleName();

	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	private String mBluetoothDeviceAddress;
	private BluetoothGatt mBluetoothGatt;

	public final static String ACTION_GATT_CONNECTED = "ACTION_GATT_CONNECTED";
	public final static String ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED";
	public final static String ACTION_GATT_SERVICES_DISCOVERED = "ACTION_GATT_SERVICES_DISCOVERED";
	public final static String ACTION_GATT_RSSI = "ACTION_GATT_RSSI";
	public final static String ACTION_DATA_AVAILABLE = "ACTION_DATA_AVAILABLE";
	public final static String EXTRA_DATA = "EXTRA_DATA";

	public final static UUID UUID_BLE_SHIELD_TX = UUID
			.fromString(RBLGattAttributes.BLE_SHIELD_TX);
	public final static UUID UUID_BLE_SHIELD_RX = UUID
			.fromString(RBLGattAttributes.BLE_SHIELD_RX);
	public final static UUID UUID_BLE_SHIELD_SERVICE = UUID
			.fromString(RBLGattAttributes.BLE_SHIELD_SERVICE);

	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
		{
			if (newState == 2)
			{
				String intentAction = "ACTION_GATT_CONNECTED";
				RBLService.this.broadcastUpdate(intentAction);
				Log.i(RBLService.TAG, "Connected to GATT server.");

				Log.i(RBLService.TAG, "Attempting to start service discovery:" +
						RBLService.this.mBluetoothGatt.discoverServices());
			}
			else if (newState == 0)
			{
				String intentAction = "ACTION_GATT_DISCONNECTED";
				Log.i(RBLService.TAG, "Disconnected from GATT server.");
				RBLService.this.broadcastUpdate(intentAction);
			}
		}

		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status)
		{
			if (status == 0) {
				RBLService.this.broadcastUpdate("ACTION_GATT_RSSI", rssi);
			} else {
				Log.w(RBLService.TAG, "onReadRemoteRssi received: " + status);
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status)
		{
			if (status == 0) {
				RBLService.this.broadcastUpdate("ACTION_GATT_SERVICES_DISCOVERED");
			} else {
				Log.w(RBLService.TAG, "onServicesDiscovered received: " + status);
			}
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
		{
			if (status == 0) {
				RBLService.this.broadcastUpdate("ACTION_DATA_AVAILABLE", characteristic);
			}
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
		{
			RBLService.this.broadcastUpdate("ACTION_DATA_AVAILABLE", characteristic);
		}
	};

	private void broadcastUpdate(final String action) {
		Intent intent = new Intent(action);
		sendBroadcast(intent);
	}

	private void broadcastUpdate(final String action, int rssi) {
		Intent intent = new Intent(action);
		intent.putExtra("EXTRA_DATA", String.valueOf(rssi));
		sendBroadcast(intent);
	}

	private void broadcastUpdate(final String action,
			final BluetoothGattCharacteristic characteristic) {
		Intent intent = new Intent(action);
		if (UUID_BLE_SHIELD_RX.equals(characteristic.getUuid()))
		{
			byte[] rx = characteristic.getValue();
			intent.putExtra("EXTRA_DATA", rx);
		}
		sendBroadcast(intent);
	}

	public class LocalBinder extends Binder {
		RBLService getService() {
			return RBLService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		// After using a given device, you should make sure that
		// BluetoothGatt.close() is called
		// such that resources are cleaned up properly. In this particular
		// example, close() is
		// invoked when the UI is disconnected from the Service.
		close();
		return super.onUnbind(intent);
	}

	private final IBinder mBinder = new LocalBinder();

	/**
	 * Initializes a reference to the local Bluetooth adapter.
	 * 
	 * @return Return true if the initialization is successful.
	 */
	public boolean initialize() {
		if (this.mBluetoothManager == null)
		{
			this.mBluetoothManager = ((BluetoothManager)getSystemService("bluetooth"));
			if (this.mBluetoothManager == null)
			{
				Log.e(TAG, "Unable to initialize BluetoothManager.");
				return false;
			}
		}
		this.mBluetoothAdapter = this.mBluetoothManager.getAdapter();
		if (this.mBluetoothAdapter == null)
		{
			Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
			return false;
		}
		return true;
	}

	/**
	 * Connects to the GATT server hosted on the Bluetooth LE device.
	 * 
	 * @param address
	 *            The device address of the destination device.
	 * 
	 * @return Return true if the connection is initiated successfully. The
	 *         connection result is reported asynchronously through the
	 *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 *         callback.
	 */
	public boolean connect(final String address) {
		if ((this.mBluetoothAdapter == null) || (address == null))
		{
			Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");

			return false;
		}
		if ((this.mBluetoothDeviceAddress != null) &&
				(address.equals(this.mBluetoothDeviceAddress)) && (this.mBluetoothGatt != null))
		{
			Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
			if (this.mBluetoothGatt.connect()) {
				return true;
			}
			return false;
		}
		BluetoothDevice device = this.mBluetoothAdapter.getRemoteDevice(address);
		if (device == null)
		{
			Log.w(TAG, "Device not found.  Unable to connect.");
			return false;
		}
		this.mBluetoothGatt = device.connectGatt(this, false, this.mGattCallback);
		Log.d(TAG, "Trying to create a new connection.");
		this.mBluetoothDeviceAddress = address;

		return true;
	}

	/**
	 * Disconnects an existing connection or cancel a pending connection. The
	 * disconnection result is reported asynchronously through the
	 * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 * callback.
	 */
	public void disconnect() {
		if ((this.mBluetoothAdapter == null) || (this.mBluetoothGatt == null))
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		this.mBluetoothGatt.disconnect();
	}

	/**
	 * After using a given BLE device, the app must call this method to ensure
	 * resources are released properly.
	 */
	public void close() {
		if (this.mBluetoothGatt == null) {
			return;
		}
		this.mBluetoothGatt.close();
		this.mBluetoothGatt = null;
	}

	/**
	 * Request a read on a given {@code BluetoothGattCharacteristic}. The read
	 * result is reported asynchronously through the
	 * {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
	 * callback.
	 * 
	 * @param characteristic
	 *            The characteristic to read from.
	 */
	public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
		if ((this.mBluetoothAdapter == null) || (this.mBluetoothGatt == null))
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		boolean status = this.mBluetoothGatt.readCharacteristic(characteristic);
		Log.d("readCharacteristic", "BluetoothAdapter not initialized" + status);
	}

	public void readRssi() {
		if ((this.mBluetoothAdapter == null) || (this.mBluetoothGatt == null))
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		this.mBluetoothGatt.readRemoteRssi();
	}

	public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
		if ((this.mBluetoothAdapter == null) || (this.mBluetoothGatt == null))
		{
			Log.e(TAG, "BluetoothAdapter or BluetoothGatt was not initialized");
			return false;
		}

		Log.d(TAG, "Writing characteristic...");
		return this.mBluetoothGatt.writeCharacteristic(characteristic);
	}

	/**
	 * Enables or disables notification on a give characteristic.
	 * 
	 * @param characteristic
	 *            Characteristic to act on.
	 * @param enabled
	 *            If true, enable notification. False otherwise.
	 */
	public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled)
	{
		if ((this.mBluetoothAdapter == null) || (this.mBluetoothGatt == null))
		{
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}

		this.mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
		if (UUID_BLE_SHIELD_RX.equals(characteristic.getUuid()))
		{
			for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
				Log.e("setCharacteristic", "BluetoothGattDescriptor: " + descriptor.getUuid().toString());
			}
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(RBLGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));

			Log.d(TAG, descriptor.getUuid().toString());
			this.mBluetoothGatt.writeDescriptor(descriptor);
			return;
		}

		Log.d(TAG, "Could not find characteristic UUID");
	}

	/**
	 * Retrieves a list of supported GATT services on the connected device. This
	 * should be invoked only after {@code BluetoothGatt#discoverServices()}
	 * completes successfully.
	 * 
	 * @return A {@code List} of supported services.
	 */
	public BluetoothGattService getSupportedGattService() {
			if (this.mBluetoothGatt == null) {
				return null;
			}
			return this.mBluetoothGatt.getService(UUID_BLE_SHIELD_SERVICE);
	}
}