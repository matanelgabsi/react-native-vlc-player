/*****************************************************************************
 * VLCOptions.java
 *****************************************************************************
 * Copyright © 2015 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ConfigurationInfo;
import android.media.AudioManager;
import android.os.Build;
import android.preference.PreferenceManager;
import androidx.annotation.MainThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.libvlc.util.HWDecoderUtil;
import org.videolan.libvlc.util.VLCUtil;
import org.videolan.medialibrary.media.MediaWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import static android.content.Context.ACTIVITY_SERVICE;
import static android.os.Build.VERSION_CODES.KITKAT;

//  <string-array name="deblocking_values" translatable="false">
//        <item>-1</item>
//        <item>0</item>
//        <item>1</item>
//        <item>3</item>
//        <item>4</item>
//    </string-array>
//          <string name="deblocking_always">完全去块 (最慢)</string>
//    <string name="deblocking_nonref">中度去块</string>
//    <string name="deblocking_nonkey">低级去块</string>
//    <string name="deblocking_all">不去块 (最快)</string>
public class VLCOptions {
    private static final String TAG = "VLCConfig";

    public static final int AOUT_AUDIOTRACK = 0;
    public static final int AOUT_OPENSLES = 1;

    @SuppressWarnings("unused")
    public static final int HW_ACCELERATION_AUTOMATIC = -1;
    public static final int HW_ACCELERATION_DISABLED = 0;
    public static final int HW_ACCELERATION_DECODING = 1;
    public static final int HW_ACCELERATION_FULL = 2;

    private static int AUDIOTRACK_SESSION_ID = 0;

    private static boolean isSupportsOpenGL(Context context) {
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        final ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
        boolean supportsEs2 = configurationInfo.reqGlEsVersion >= 0x2000;
        boolean isEmulator = Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                && (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86"));
        return supportsEs2 || isEmulator;
    }

    // TODO should return List<String>
    public static ArrayList<String> getLibOptions(Context context) {
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);

        /* generate an audio session id so as to share audio output with external equalizer */
        if (Build.VERSION.SDK_INT >= 21 && AUDIOTRACK_SESSION_ID == 0) {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AUDIOTRACK_SESSION_ID = audioManager.generateAudioSessionId();
        }

        ArrayList<String> options = new ArrayList<String>(50);

        final boolean timeStrechingDefault = Build.VERSION.SDK_INT > KITKAT;
        //启用音频的时间伸缩      可加速与减慢音频播放而不改变音调 (需要较快设备支持)。
        final boolean timeStreching = pref.getBoolean("enable_time_stretching_audio", timeStrechingDefault);
        final String subtitlesEncoding = pref.getString("subtitle_text_encoding", "");
        final boolean frameSkip = pref.getBoolean("enable_frame_skip", false);//启用跳帧     加速解码，但可能降低画质
        String chroma = pref.getString("chroma_format", "RV32");//RGB 32 位: 默认色度 RGB 16 位: 性能更佳，但画质下降 YUV: 性能最佳，但并非所有设备可用。仅限 Android 2.3 及更高版本。
        final boolean verboseMode = pref.getBoolean("enable_verbose_mode", true);


        int networkCaching = pref.getInt("network_caching_value", 1500);
        if (networkCaching > 60000)//缓冲网络媒体的时间量 (单位为毫秒)。硬件解码时无效。留空可重置。
            networkCaching = 60000;
        else if (networkCaching < 0)
            networkCaching = 0;

        final String freetypeRelFontsize = pref.getString("subtitles_size", "16");
        final boolean freetypeBold = pref.getBoolean("subtitles_bold", false);
        final String freetypeColor = pref.getString("subtitles_color", "16777215");
        final boolean freetypeBackground = pref.getBoolean("subtitles_background", false);
        final boolean opengl = isSupportsOpenGL(context);

        /* CPU intensive plugin, setting for slow devices */
        options.add(timeStreching ? "--audio-time-stretch" : "--no-audio-time-stretch");

        options.add("--avcodec-skiploopfilter");
        options.add("" + getDeblocking(-1));//这里太大了消耗性能   太小了会花屏
//        options.add("0");//这里太大了消耗性能   太小了会花屏


        options.add("--avcodec-skip-frame");
        options.add(frameSkip ? "2" : "0");
        options.add("--avcodec-skip-idct");
        options.add(frameSkip ? "2" : "0");
        options.add("--subsdec-encoding");
        options.add(subtitlesEncoding);//chi是中国文字
        options.add("--stats");
        /* XXX: why can't the default be fine ? #7792 */
        if (networkCaching > 0)
            options.add("--network-caching=" + networkCaching);
        options.add("--android-display-chroma");
        if (chroma.equals("YV12")) {
            chroma = "";
        } else {
            chroma = opengl ? "YUV" : "RV32";
        }
        options.add(chroma);
        options.add("--audio-resampler");
        options.add(getResampler());
        options.add("--audiotrack-session-id=" + AUDIOTRACK_SESSION_ID);

        options.add("--freetype-rel-fontsize=" + freetypeRelFontsize);
        if (freetypeBold)
            options.add("--freetype-bold");
        options.add("--freetype-color=" + freetypeColor);
        if (freetypeBackground)
            options.add("--freetype-background-opacity=128");
        else
            options.add("--freetype-background-opacity=0");
//        if (opengl == 1)
//            options.add("--vout=gles2,none");
//        else if (opengl == 0)
//            options.add("--vout=android_display,none");
//        else
//            options.add("--vout=android_display");
        if (isSupportsOpenGL(context)) {
            options.add("--vout=gles2,none");//OpenGL ES 2.0
        } else {
            options.add("--vout=android_display,none");
        }

        /* Configure keystore */
        options.add("--keystore");
        if (AndroidUtil.isMarshMallowOrLater)
            options.add("file_crypt,none");
        else
            options.add("file_plaintext,none");
        options.add("--keystore-file");
        options.add(new File(context.getDir("keystore", Context.MODE_PRIVATE), "file").getAbsolutePath());

        //Chromecast
        options.add(verboseMode ? "-vv" : "-v");
        if (pref.getBoolean("casting_passthrough", false))
            options.add("--sout-chromecast-audio-passthrough");
        else options.add("--no-sout-chromecast-audio-passthrough");
        options.add("--sout-chromecast-conversion-quality=" + pref.getString("casting_quality", "2"));
        options.add("--sout-keep");

        LogUtils.i("options=" + Arrays.toString(options.toArray()));
        return options;
    }

    public static boolean isAudioDigitalOutputEnabled(SharedPreferences pref) {
        return pref.getBoolean("audio_digital_output", false);
    }

    public static void setAudioDigitalOutputEnabled(SharedPreferences pref, boolean enabled) {
        SharedPreferences.Editor editor = pref.edit();
        editor.putBoolean("audio_digital_output", enabled);
        editor.apply();
    }

    public static String getAout(SharedPreferences pref) {
        int aout = -1;
        try {
            aout = Integer.parseInt(pref.getString("aout", "-1"));
        } catch (NumberFormatException ignored) {
        }
        final HWDecoderUtil.AudioOutput hwaout = HWDecoderUtil.getAudioOutputFromDevice();
        if (hwaout == HWDecoderUtil.AudioOutput.AUDIOTRACK || hwaout == HWDecoderUtil.AudioOutput.OPENSLES)
            aout = hwaout == HWDecoderUtil.AudioOutput.OPENSLES ? AOUT_OPENSLES : AOUT_AUDIOTRACK;

        return aout == AOUT_OPENSLES ? "opensles_android" : null /* audiotrack is the default */;
    }

    private static int getDeblocking(int deblocking) {
        int ret = deblocking;
        if (deblocking < 0) {
            /**
             * Set some reasonable sDeblocking defaults:
             *
             * Skip all (4) for armv6 and MIPS by default
             * Skip non-ref (1) for all armv7 more than 1.2 Ghz and more than 2 cores
             * Skip non-key (3) for all devices that don't meet anything above
             */
            VLCUtil.MachineSpecs m = VLCUtil.getMachineSpecs();
            if (m == null)
                return ret;
            if ((m.hasArmV6 && !(m.hasArmV7)) || m.hasMips)
                ret = 4;
            else if (m.frequency >= 1200 && m.processors > 2)
                ret = 1;
            else if (m.bogoMIPS >= 1200 && m.processors > 2) {
                ret = 1;
                Log.d(TAG, "Used bogoMIPS due to lack of frequency info");
            } else
                ret = 3;
        } else if (deblocking > 4) { // sanity check
            ret = 3;
        }
        return ret;
    }

    private static String getResampler() {
        final VLCUtil.MachineSpecs m = VLCUtil.getMachineSpecs();
        return (m == null || m.processors > 2) ? "soxr" : "ugly";
    }

    public static void setMediaOptions(Media media, Context context, int flags) {
        boolean noHardwareAcceleration = (flags & MediaWrapper.MEDIA_NO_HWACCEL) != 0;
        boolean noVideo = (flags & MediaWrapper.MEDIA_VIDEO) == 0;
        final boolean paused = (flags & MediaWrapper.MEDIA_PAUSED) != 0;
        int hardwareAcceleration = HW_ACCELERATION_DISABLED;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (!noHardwareAcceleration) {
            try {
                hardwareAcceleration = Integer.parseInt(prefs.getString("hardware_acceleration", "-1"));
            } catch (NumberFormatException ignored) {
            }
        }
        if (hardwareAcceleration == HW_ACCELERATION_DISABLED)
            media.setHWDecoderEnabled(false, false);
        else if (hardwareAcceleration == HW_ACCELERATION_FULL || hardwareAcceleration == HW_ACCELERATION_DECODING) {
            media.setHWDecoderEnabled(true, true);
            if (hardwareAcceleration == HW_ACCELERATION_DECODING) {
                media.addOption(":no-mediacodec-dr");
                media.addOption(":no-omxil-dr");
            }
        } /* else automatic: use default options */

        if (noVideo) media.addOption(":no-video");
        if (paused) media.addOption(":start-paused");
        if (!prefs.getBoolean("subtitles_autoload", true)) media.addOption(":sub-language=none");
        if (prefs.getBoolean("media_fast_seek", true)) media.addOption(":input-fast-seek");

//        if (RendererDelegate.INSTANCE.getSelectedRenderer() != null) {
//            media.addOption(":sout-chromecast-audio-passthrough="+prefs.getBoolean("casting_passthrough", true));
//            media.addOption(":sout-chromecast-conversion-quality="+prefs.getString("casting_quality", "2"));
//        }
    }

    private static MediaPlayer.Equalizer getEqualizerSetFromSettings(SharedPreferences pref) {
        final float[] bands = getFloatArray(pref, "equalizer_values");
        if (bands != null && pref.contains("equalizer_enabled")) {
            final int bandCount = MediaPlayer.Equalizer.getBandCount();
            if (bands.length != bandCount + 1)
                return null;

            final MediaPlayer.Equalizer eq = MediaPlayer.Equalizer.create();
            eq.setPreAmp(bands[0]);
            for (int i = 0; i < bandCount; ++i)
                eq.setAmp(i, bands[i + 1]);
            return eq;
        } else
            return MediaPlayer.Equalizer.createFromPreset(0);
    }

    @MainThread
    public static MediaPlayer.Equalizer getEqualizerSetFromSettings(Context context, boolean force) {
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        if (!force && !pref.getBoolean("equalizer_enabled", false))
            return null;
        return getEqualizerSetFromSettings(pref);
    }

    @MainThread
    public static MediaPlayer.Equalizer getEqualizerSetFromSettings(Context context) {
        return getEqualizerSetFromSettings(context, false);
    }

    @MainThread
    public static String getEqualizerNameFromSettings(Context context) {
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString("equalizer_set", "Flat");
    }

    @MainThread
    public static void saveEqualizerInSettings(Context context, MediaPlayer.Equalizer eq, String name, boolean enabled, boolean saved) {
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = pref.edit();
        if (eq != null) {
            editor.putBoolean("equalizer_enabled", enabled);
            final int bandCount = MediaPlayer.Equalizer.getBandCount();
            final float[] bands = new float[bandCount + 1];
            bands[0] = eq.getPreAmp();
            for (int i = 0; i < bandCount; ++i) {
                bands[i + 1] = eq.getAmp(i);
            }
            putFloatArray(editor, "equalizer_values", bands);
            editor.putString("equalizer_set", name);
        } else {
            editor.putBoolean("equalizer_enabled", false);
        }
        editor.putBoolean("equalizer_saved", saved);
        editor.apply();
    }

    @MainThread
    public static MediaPlayer.Equalizer getCustomSet(Context context, String customName) {
        try {
            final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String key = "custom_equalizer_" + customName.replace(" ", "_");
            final float[] bands = getFloatArray(pref, key);
            final int bandCount = MediaPlayer.Equalizer.getBandCount();
            if (bands.length != bandCount + 1)
                return null;

            final MediaPlayer.Equalizer eq = MediaPlayer.Equalizer.create();
            eq.setPreAmp(bands[0]);
            for (int i = 0; i < bandCount; ++i)
                eq.setAmp(i, bands[i + 1]);
            return eq;
        } catch (Exception e) {
            return MediaPlayer.Equalizer.createFromPreset(0);
        }
    }

    @MainThread
    public static void saveCustomSet(Context context, MediaPlayer.Equalizer eq, String customName) {
        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        String formatedName = customName.replace(" ", "_");
        String key = "custom_equalizer_" + formatedName;
        SharedPreferences.Editor editor = pref.edit();
        final int bandCount = MediaPlayer.Equalizer.getBandCount();
        final float[] bands = new float[bandCount + 1];
        bands[0] = eq.getPreAmp();
        for (int i = 0; i < bandCount; ++i) {
            bands[i + 1] = eq.getAmp(i);
        }
        putFloatArray(editor, key, bands);
        editor.apply();
    }

    @MainThread
    public static void deleteCustomSet(Context context, String customName) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove("custom_equalizer_" + customName.replace(" ", "_"))
                .apply();
    }

    public static boolean getEqualizerSavedState(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("equalizer_saved", true);
    }

    public static boolean getEqualizerEnabledState(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("equalizer_enabled", false);
    }

    public static int getAudiotrackSessionId() {
        return AUDIOTRACK_SESSION_ID;
    }

    public static float[] getFloatArray(SharedPreferences pref, String key) {
        float[] array = null;
        String s = pref.getString(key, null);
        if (s != null) {
            try {
                JSONArray json = new JSONArray(s);
                array = new float[json.length()];
                for (int i = 0; i < array.length; i++)
                    array[i] = (float) json.getDouble(i);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return array;
    }

    public static void putFloatArray(SharedPreferences.Editor editor, String key, float[] array) {
        try {
            JSONArray json = new JSONArray();
            for (float f : array)
                json.put(f);
            editor.putString(key, json.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
