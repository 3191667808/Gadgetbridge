package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.fmradio;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmRegion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.requests.ControlRequest;

public class FmRadioTuneRequest extends ControlRequest {
    public static final int SEEK_DOWN = 0x02;
    public static final int SEEK_UP = 0x03;
    public static final int SCAN_UP = 0x04;
    public static final int SCAN_DOWN = 0x05;

    public static final int AUTO_TUNE = 0x06;
    public static final int TUNE_PRESET = 0x07;
    public static final int TUNE_FREQ = 0x08;
    public static final int STOP_SCAN = 0x09;

    public FmRadioTuneRequest(@ModeOnly int mode) {
        super(ControlSubset.FM, getMessagePayload(mode));
    }

    public FmRadioTuneRequest(@ModeWithArgs int mode, int freqOrPreset, CardoFmRegion region) {
        super(ControlSubset.FM, getMessagePayload(mode, freqOrPreset, region));
    }

    private static byte[] getMessagePayload(int mode) {
        switch (mode) {
            case SCAN_UP:
            case SCAN_DOWN:
            case SEEK_UP:
            case SEEK_DOWN:
            case AUTO_TUNE:
            case STOP_SCAN:
                return new byte[]{(byte) mode, 0x00};
            default:
                throw new IllegalArgumentException("Invalid mode: " + mode);
        }
    }

    private static byte[] getMessagePayload(int mode, int freqOrPreset, CardoFmRegion region) {
        if (mode == TUNE_PRESET && freqOrPreset > 6)
            throw new IllegalArgumentException("Invalid preset: " + freqOrPreset);

        if (mode == TUNE_FREQ && !checkFrequency(freqOrPreset, region))
            throw new IllegalArgumentException("Invalid frequency: " + freqOrPreset);

        switch (mode) {
            case TUNE_PRESET:
                return new byte[]{(byte) mode, 0x01, (byte) freqOrPreset};
            case TUNE_FREQ:
                return new byte[]{(byte) mode, 0x02, (byte) ((freqOrPreset >> 8) & 0xFF), (byte) (freqOrPreset & 0xFF)};
            default:
                throw new IllegalArgumentException("Invalid mode: " + mode);
        }
    }

    private static boolean checkFrequency(int frequency, CardoFmRegion region) {
        return (frequency >= region.getMinFreq() && frequency <= region.getMaxFreq());
//        switch (region) {
//            case WORLDWIDE:
//                return (frequency >= 8700 && frequency <= 10800);
//            case JAPAN:
//                return (frequency >= 7600 && frequency <= 9500);
//        }
//        return false;
    }

    @IntDef({SEEK_DOWN, SEEK_UP, SCAN_UP, SCAN_DOWN, AUTO_TUNE, STOP_SCAN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModeOnly {
    }

    @IntDef({TUNE_PRESET, TUNE_FREQ})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModeWithArgs {
    }
}
