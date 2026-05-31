/*  Copyright (C) 2026 Dany Mestas

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.oura;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Set;

import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.BondingInterface;
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Pairing activity for Oura Ring 4.
 *
 *  The ring's primary GATT characteristics (notify char {@code 98ED0003} and several others)
 *  require an encrypted LE link — any CCCD write on an unencrypted link is rejected with
 *  {@code INSUFFICIENT_ENCRYPTION (0x0F)}.
 *
 *  This activity sequences pairing in the correct order:
 *  <ol>
 *    <li>Pull the candidate from the discovery flow.</li>
 *    <li>Call {@code device.createBond()} and listen for {@code ACTION_BOND_STATE_CHANGED}.</li>
 *    <li>On {@code BOND_BONDED}: open the GATT connection via
 *        {@link BondingUtil#connectThenComplete(BondingInterface, GBDeviceCandidate)}. The cached
 *        LTK auto-encrypts the new GATT link, so the init pipeline's CCCD writes are accepted.</li>
 *    <li>On {@code BOND_NONE} (after transitioning through {@code BOND_BONDING}): pairing failed,
 *        toast a clear error message, finish with {@code RESULT_CANCELED}.</li>
 *    <li>10 s overall timeout in case Android never delivers a terminal bond state.</li>
 *  </ol>
 *  Out-Of-Band pairing using the AES auth key would be the "correct" answer, but Android marks
 *  {@code BluetoothDevice.createBondOutOfBand} and {@code OobData} as {@code @SystemApi} — they
 *  require {@code BLUETOOTH_PRIVILEGED} which is OEM-only. Gadgetbridge can't reach that path
 *  from a user-installed APK, so Just-Works pairing is the only available option here.
 *
 *  Auth still runs at app level inside {@code OuraRing4Support} after the encrypted link is up. */
@SuppressLint("MissingPermission")  // BLUETOOTH_CONNECT is declared in the manifest and granted by
                                    // the runtime-permission flow before discovery launches this activity;
                                    // every call site that catches SecurityException already handles the
                                    // edge case where the permission is revoked between grant and use.
public class OuraPairingActivity extends AbstractGBActivity implements BondingInterface {

    private static final Logger LOG = LoggerFactory.getLogger(OuraPairingActivity.class);
    private static final long BOND_TIMEOUT_MS = 10_000L;

    private GBDeviceCandidate target;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> failPairing("Bond timed out — factory-reset the ring and retry");
    private boolean finished;

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            final BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (target == null || dev == null || !dev.getAddress().equals(target.getMacAddress())) {
                return;
            }
            final int newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            final int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            LOG.info("Oura bond state {} -> {}", prevState, newState);

            if (newState == BluetoothDevice.BOND_BONDED) {
                succeedPairing();
            } else if (newState == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING) {
                failPairing("Ring rejected pairing — factory-reset the ring in the Oura app, then retry");
            }
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        intent.setExtrasClassLoader(GBDeviceCandidate.class.getClassLoader());
        target = intent.getParcelableExtra(DeviceCoordinator.EXTRA_DEVICE_CANDIDATE);
        if (target == null || target.getDevice() == null) {
            LOG.error("OuraPairingActivity launched without EXTRA_DEVICE_CANDIDATE");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        final BluetoothDevice device = target.getDevice();
        final int currentBond = device.getBondState();
        LOG.info("OuraPairingActivity: starting bond for {} (current state={})",
                target.getMacAddress(), currentBond);

        if (currentBond == BluetoothDevice.BOND_BONDED) {
            // Already bonded — skip createBond, go straight to connect.
            succeedPairing();
            return;
        }

        // The ring advertises a Random Resolvable Private Address (RPA) that rotates every
        // few minutes. The scanned MAC therefore won't match the identity address held in the
        // OS bond table — device.getBondState() returns BOND_NONE for the rotated MAC even
        // when the phone holds an LTK for this ring (typically set up by the Oura companion
        // app during onboarding). Probe the bonded-devices set for an Oura-named identity:
        // if one exists, the BLE stack will resolve the RPA via its cached IRK and auto-
        // encrypt the new GATT link using the cached LTK — no createBond() needed.
        final BluetoothDevice bondedIdentity = findBondedOuraIdentity();
        if (bondedIdentity != null) {
            LOG.info("Oura: phone already bonded to {} ({}); scanned RPA {} will resolve via IRK",
                    bondedIdentity.getAddress(), safeName(bondedIdentity), target.getMacAddress());
            succeedPairing();
            return;
        }

        registerBroadcastReceivers();
        timeoutHandler.postDelayed(timeoutRunnable, BOND_TIMEOUT_MS);

        try {
            if (!device.createBond()) {
                failPairing("createBond() returned false — Bluetooth may be off");
                return;
            }
            GB.toast(this, "Pairing — confirm any Android prompt", Toast.LENGTH_SHORT, GB.INFO);
        } catch (final SecurityException e) {
            LOG.error("SecurityException creating bond", e);
            failPairing("Pairing permission denied");
        }
    }

    /** Returns any device already paired with this phone whose advertised name starts with
     *  "Oura" — i.e. an Oura ring previously bonded by the companion app or by Gadgetbridge.
     *  Used to detect the RPA-vs-identity case where {@code device.getBondState()} would
     *  return BOND_NONE for a rotated MAC despite a cached LTK existing for the ring. */
    private BluetoothDevice findBondedOuraIdentity() {
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            final Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return null;
            }
            for (final BluetoothDevice candidate : bonded) {
                final String name = safeName(candidate);
                if (name != null && name.startsWith("Oura")) {
                    return candidate;
                }
            }
        } catch (final SecurityException e) {
            LOG.warn("Cannot enumerate bonded devices (missing BLUETOOTH_CONNECT?)", e);
        }
        return null;
    }

    private static String safeName(final BluetoothDevice dev) {
        try {
            return dev.getName();
        } catch (final SecurityException e) {
            return null;
        }
    }

    private synchronized void succeedPairing() {
        if (finished) {
            return;
        }
        finished = true;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unregisterBroadcastReceivers();
        LOG.info("OuraPairingActivity: bond OK → connecting");
        BondingUtil.connectThenComplete(this, target);
    }

    private synchronized void failPairing(final String message) {
        if (finished) {
            return;
        }
        finished = true;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unregisterBroadcastReceivers();
        LOG.warn("OuraPairingActivity: pairing failed — {}", message);
        GB.toast(this, message, Toast.LENGTH_LONG, GB.WARN);
        new Handler(Looper.getMainLooper()).post(() -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    @Override
    public void onBondingComplete(final boolean success) {
        new Handler(Looper.getMainLooper()).post(() -> {
            setResult(success ? RESULT_OK : RESULT_CANCELED);
            finish();
        });
    }

    @Override
    public GBDeviceCandidate getCurrentTarget() {
        return target;
    }

    @Override
    public boolean getAttemptToConnect() {
        return true;
    }

    @Override
    public Context getContext() {
        return this;
    }

    @Override
    public void registerBroadcastReceivers() {
        ContextCompat.registerReceiver(this, bondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void unregisterBroadcastReceivers() {
        AndroidUtils.safeUnregisterBroadcastReceiver(this, bondReceiver);
    }

    @Override
    protected void onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable);
        unregisterBroadcastReceivers();
        super.onDestroy();
    }
}
