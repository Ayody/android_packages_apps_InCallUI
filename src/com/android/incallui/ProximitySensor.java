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
 * limitations under the License
 */

package com.android.incallui;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.telecom.AudioState;

import com.android.incallui.AudioModeProvider.AudioModeListener;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.InCallPresenter.InCallStateListener;
import com.android.services.telephony.common.AudioMode;
import com.google.common.base.Objects;

/**
 * Class manages the proximity sensor for the in-call UI.
 * We enable the proximity sensor while the user in a phone call. The Proximity sensor turns off
 * the touchscreen and display when the user is close to the screen to prevent user's cheek from
 * causing touch events.
 * The class requires special knowledge of the activity and device state to know when the proximity
 * sensor should be enabled and disabled. Most of that state is fed into this class through
 * public methods.
 */
public class ProximitySensor implements AccelerometerListener.OrientationListener,
        InCallStateListener, AudioModeListener, SensorEventListener {
    private static final String TAG = ProximitySensor.class.getSimpleName();

    private static final String PROXIMITY_SENSOR = "proximity_sensor";

    private final PowerManager mPowerManager;
    private SensorManager mSensor;
    private Sensor mProxSensor;
    private final AudioModeProvider mAudioModeProvider;
    private final AccelerometerListener mAccelerometerListener;
    private int mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
    private boolean mUiShowing = false;
    private boolean mIsPhoneOutgoing = false;
    private boolean mIsPhoneOffhook = false;
    private boolean mProximitySpeaker = false;
    private boolean mDialpadVisible;
    private Context mContext;

    // True if the keyboard is currently *not* hidden
    // Gets updated whenever there is a Configuration change
    private boolean mIsHardKeyboardOpen;

    public ProximitySensor(Context context, AudioModeProvider audioModeProvider) {
        mContext = context;
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        if (mPowerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            mSensor = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
            mProxSensor = mSensor.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        } else {
            mProxSensor = null;
            mSensor = null;
        }

        mAccelerometerListener = new AccelerometerListener(mContext, this);
        mAudioModeProvider = audioModeProvider;
        mAudioModeProvider.addListener(this);
    }

    public void tearDown() {
        mAudioModeProvider.removeListener(this);

        mAccelerometerListener.enable(false);

        TelecomAdapter.getInstance().turnOffProximitySensor(true);

        if (mSensor != null) {
            mSensor.unregisterListener(this);
        }
    }

    /**
     * Called to identify when the device is laid down flat.
     */
    @Override
    public void orientationChanged(int orientation) {
        mOrientation = orientation;
        updateProximitySensorMode();
    }

    /**
     * Called to keep track of the overall UI state.
     */
    @Override
    public void onStateChange(InCallState oldState, InCallState newState, CallList callList) {
        // We ignore incoming state because we do not want to enable proximity
        // sensor during incoming call screen. We check hasLiveCall() because a disconnected call
        // can also put the in-call screen in the INCALL state.
        boolean hasOngoingCall = InCallState.INCALL == newState && callList.hasLiveCall();
        boolean isOffhook = (InCallState.OUTGOING == newState) || hasOngoingCall;
        boolean isOutgoing = (InCallState.OUTGOING == newState);

        if (isOffhook != mIsPhoneOffhook) {
            mIsPhoneOffhook = isOffhook;
            mIsPhoneOutgoing = isOutgoing;

            mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
            mAccelerometerListener.enable(mIsPhoneOffhook);

            updateProxSpeaker();
            updateProximitySensorMode();
        } else if (isOutgoing != mIsPhoneOutgoing) {
            mIsPhoneOutgoing = isOutgoing;
            updateProxSpeaker();
            updateProximitySensorMode();
        }
    }

    @Override
    public void onSupportedAudioMode(int modeMask) {
    }

    @Override
    public void onMute(boolean muted) {
    }

    /**
     * Called when the audio mode changes during a call.
     */
    @Override
    public void onAudioMode(int mode) {
        updateProximitySensorMode();
    }

    /**
     * Proximity state changed
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values[0] != mProxSensor.getMaximumRange()) {
            setProxSpeaker(false);
        } else {
            setProxSpeaker(true);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void onDialpadVisible(boolean visible) {
        mDialpadVisible = visible;
        updateProximitySensorMode();
    }

    /**
     * Called by InCallActivity to listen for hard keyboard events.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        mIsHardKeyboardOpen = newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;

        // Update the Proximity sensor based on keyboard state
        updateProximitySensorMode();
    }

    /**
     * Used to save when the UI goes in and out of the foreground.
     */
    public void onInCallShowing(boolean showing) {
        if (showing) {
            mUiShowing = true;

        // We only consider the UI not showing for instances where another app took the foreground.
        // If we stopped showing because the screen is off, we still consider that showing.
        } else if (mPowerManager.isScreenOn()) {
            mUiShowing = false;
        }
        updateProximitySensorMode();
    }

    /**
     * TODO: There is no way to determine if a screen is off due to proximity or if it is
     * legitimately off, but if ever we can do that in the future, it would be useful here.
     * Until then, this function will simply return true of the screen is off.
     */
    public boolean isScreenReallyOff() {
        return !mPowerManager.isScreenOn();
    }

    /**
     * Updates the wake lock used to control proximity sensor behavior,
     * based on the current state of the phone.
     *
     * On devices that have a proximity sensor, to avoid false touches
     * during a call, we hold a PROXIMITY_SCREEN_OFF_WAKE_LOCK wake lock
     * whenever the phone is off hook.  (When held, that wake lock causes
     * the screen to turn off automatically when the sensor detects an
     * object close to the screen.)
     *
     * This method is a no-op for devices that don't have a proximity
     * sensor.
     *
     * Proximity wake lock will *not* be held if any one of the
     * conditions is true while on a call:
     * 1) If the audio is routed via Bluetooth
     * 2) If a wired headset is connected
     * 3) if the speaker is ON
     * 4) If the slider is open(i.e. the hardkeyboard is *not* hidden)
     */
    private synchronized void updateProximitySensorMode() {
        final int audioMode = mAudioModeProvider.getAudioMode();

        // turn proximity sensor off and turn screen on immediately if
        // we are using a headset, the keyboard is open, or the device
        // is being held in a horizontal position.
            boolean screenOnImmediately = (AudioState.ROUTE_WIRED_HEADSET == audioMode
                    || AudioState.ROUTE_SPEAKER == audioMode
                    || AudioState.ROUTE_BLUETOOTH == audioMode
                    || mIsHardKeyboardOpen);
            screenOnImmediately |= Settings.System.getInt(mContext.getContentResolver(),
                    PROXIMITY_SENSOR, 1) == 0;

            // We do not keep the screen off when the user is outside in-call screen and we are
            // horizontal, but we do not force it on when we become horizontal until the
            // proximity sensor goes negative.
            final boolean horizontal =
                    (mOrientation == AccelerometerListener.ORIENTATION_HORIZONTAL);
            screenOnImmediately |= !mUiShowing && horizontal;

            // We do not keep the screen off when dialpad is visible, we are horizontal, and
            // the in-call screen is being shown.
            // At that moment we're pretty sure users want to use it, instead of letting the
            // proximity sensor turn off the screen by their hands.
            screenOnImmediately |= mDialpadVisible && horizontal;

            Log.v(this, "screenonImmediately: ", screenOnImmediately);

            Log.i(this, Objects.toStringHelper(this)
                    .add("keybrd", mIsHardKeyboardOpen ? 1 : 0)
                    .add("dpad", mDialpadVisible ? 1 : 0)
                    .add("offhook", mIsPhoneOffhook ? 1 : 0)
                    .add("hor", horizontal ? 1 : 0)
                    .add("ui", mUiShowing ? 1 : 0)
                    .add("aud", AudioState.audioRouteToString(audioMode))
                    .toString());

            if (mIsPhoneOffhook && !screenOnImmediately) {
                Log.d(this, "Turning on proximity sensor");
                // Phone is in use!  Arrange for the screen to turn off
                // automatically when the sensor detects a close object.
                TelecomAdapter.getInstance().turnOnProximitySensor();
            } else {
                Log.d(this, "Turning off proximity sensor");
                // Phone is either idle, or ringing.  We don't want any special proximity sensor
                // behavior in either case.
                TelecomAdapter.getInstance().turnOffProximitySensor(screenOnImmediately);
            }
    }

    private void updateProxSpeaker() {
        if (mSensor != null && mProxSensor != null) {
            if (mIsPhoneOffhook) {
                mSensor.registerListener(this, mProxSensor,
                        SensorManager.SENSOR_DELAY_GAME);
            } else {
                mSensor.unregisterListener(this);
            }
        }
    }

    private void setProxSpeaker(final boolean speaker) {
        final int audioMode = mAudioModeProvider.getAudioMode();
        if (mIsPhoneOffhook && Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PROXIMITY_AUTO_SPEAKER, 0) == 1
                && audioMode != AudioMode.WIRED_HEADSET
                && audioMode != AudioMode.BLUETOOTH) {
            if (speaker && audioMode != AudioMode.SPEAKER) {
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.PROXIMITY_AUTO_SPEAKER_INCALL_ONLY, 0) == 0
                        || (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.PROXIMITY_AUTO_SPEAKER_INCALL_ONLY, 0) == 1
                        && !mIsPhoneOutgoing)) {
			    final Handler handler = new Handler();
			    handler.postDelayed(new Runnable() {
		            @Override
		    	    public void run() {
                            TelecomAdapter.getInstance().setAudioRoute(AudioMode.SPEAKER);
			    }
                        }, 500);
		}
            } else if (!speaker) {
                TelecomAdapter.getInstance().setAudioRoute(AudioMode.EARPIECE);
                updateProximitySensorMode();
            }
        }
    }
}
