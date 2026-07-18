/*  Copyright (C) 2026 d3vv3

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
package nodomain.freeyourgadget.gadgetbridge.model;

public class EcgInterpretation {
    public enum DeviceHint {
        NORMAL,
        IRREGULAR,
        UNKNOWN
    }

    public enum SignalQuality {
        GOOD,
        NOISY,
        INCONCLUSIVE
    }

    public enum Rhythm {
        REGULAR,
        IRREGULAR,
        INCONCLUSIVE
    }

    private final DeviceHint deviceHint;
    private final SignalQuality signalQuality;
    private final Rhythm rhythm;
    private final int peakCount;

    public EcgInterpretation(final DeviceHint deviceHint,
                             final SignalQuality signalQuality,
                             final Rhythm rhythm) {
        this(deviceHint, signalQuality, rhythm, 0);
    }

    public EcgInterpretation(final DeviceHint deviceHint,
                             final SignalQuality signalQuality,
                             final Rhythm rhythm,
                             final int peakCount) {
        this.deviceHint = deviceHint;
        this.signalQuality = signalQuality;
        this.rhythm = rhythm;
        this.peakCount = peakCount;
    }

    public DeviceHint getDeviceHint() {
        return deviceHint;
    }

    public SignalQuality getSignalQuality() {
        return signalQuality;
    }

    public Rhythm getRhythm() {
        return rhythm;
    }

    public int getPeakCount() {
        return peakCount;
    }
}
