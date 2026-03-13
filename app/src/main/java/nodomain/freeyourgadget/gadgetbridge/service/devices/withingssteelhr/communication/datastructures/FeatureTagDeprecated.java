/*  Copyright (C) 2024 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures;

import java.nio.ByteBuffer;

/**
 * Encodes one entry in the feature-tags-deprecated list (TLV type {@code 0x099C}).
 *
 * <p>Wire format: 2-byte type + 2-byte length (10) + 10-byte payload:
 * <pre>
 *   id         (uint16 BE)  - feature tag identifier
 *   startTime  (uint32 BE)  - Unix timestamp; 0 = always active
 *   endTime    (uint32 BE)  - Unix timestamp; 0 = no expiry
 * </pre>
 *
 * <p>Known feature tag IDs observed from HCI captures:
 * <ul>
 *   <li>{@link #TAG_ECG_TERMS}    (0x0004) - ECG terms &amp; conditions accepted</li>
 *   <li>{@link #TAG_SPO2_SLEEP}   (0x0005) - Respiratory scan automatic/sleep mode</li>
 *   <li>{@link #TAG_AFIB_WINDOW}  (0x0009) - AFib detection time window (uses timestamps)</li>
 *   <li>{@link #TAG_AFIB_EXTRA}   (0x000A) - AFib detection (always active)</li>
 *   <li>{@link #TAG_AFIB_NIGHT}   (0x000B) - Night-time AFib detection</li>
 *   <li>{@link #TAG_RESP_ALWAYS}  (0x000E) - Respiratory scan always-on mode</li>
 *   <li>{@link #TAG_ECG_MEAS}     (0x000F) - ECG/respiratory measurement enabled</li>
 *   <li>{@link #TAG_0x0011}       (0x0011) - Companion to TAG_RESP_ALWAYS in always-on mode</li>
 *   <li>{@link #TAG_IRREGULAR_HR} (0x0013) - Irregular heart rate detection</li>
 *   <li>{@link #TAG_HIGH_HR}      (0x0014) - High heart rate notification</li>
 *   <li>{@link #TAG_LOW_HR}       (0x0016) - Low heart rate notification</li>
 *   <li>{@link #TAG_0x0035}       (0x0035) - Present when any health feature is active (purpose unknown)</li>
 *   <li>{@link #TAG_0x0058}       (0x0058) - Present when any health feature is active (purpose unknown)</li>
 * </ul>
 */
public class FeatureTagDeprecated extends WithingsStructure {

    // ---- Known feature tag identifiers ----

    /** ECG terms &amp; conditions accepted by the user. */
    public static final short TAG_ECG_TERMS    = 0x0004;
    /** Respiratory scan automatic mode (scans some nights). */
    public static final short TAG_SPO2_SLEEP   = 0x0005;
    /** AFib detection time window (populated with actual timestamps). */
    public static final short TAG_AFIB_WINDOW  = 0x0009;
    /** AFib continuous detection (always-active, timestamps = 0). */
    public static final short TAG_AFIB_EXTRA   = (short) 0x000A;
    /** Night-time AFib detection. */
    public static final short TAG_AFIB_NIGHT   = (short) 0x000B;
    /** Respiratory scan always-on mode (scans every night). */
    public static final short TAG_RESP_ALWAYS  = (short) 0x000E;
    /** ECG / respiratory measurement feature enabled. */
    public static final short TAG_ECG_MEAS     = (short) 0x000F;
    /** Companion to TAG_RESP_ALWAYS in always-on respiratory scan mode. */
    public static final short TAG_0x0011       = (short) 0x0011;
    /** Irregular heart rate detection. */
    public static final short TAG_IRREGULAR_HR = (short) 0x0013;
    /** High heart rate notification. */
    public static final short TAG_HIGH_HR      = (short) 0x0014;
    /** Low heart rate notification. */
    public static final short TAG_LOW_HR       = (short) 0x0016;
    /** Unknown; present when any health feature is active. */
    public static final short TAG_0x0035       = 0x0035;
    /** Unknown; present when any health feature is active. */
    public static final short TAG_0x0058       = 0x0058;

    // ---- Payload ----

    private short tagId;
    private int startTime;  // 0 = always active
    private int endTime;    // 0 = no expiry

    /** No-arg constructor required by {@link DataStructureFactory}. */
    public FeatureTagDeprecated() {}

    /**
     * Creates a feature tag that is always active (start/end = 0).
     *
     * @param tagId one of the {@code TAG_*} constants
     */
    public FeatureTagDeprecated(short tagId) {
        this(tagId, 0, 0);
    }

    /**
     * Creates a feature tag with an explicit activation window.
     *
     * @param tagId     one of the {@code TAG_*} constants
     * @param startTime Unix epoch seconds; 0 = always active
     * @param endTime   Unix epoch seconds; 0 = no expiry
     */
    public FeatureTagDeprecated(short tagId, int startTime, int endTime) {
        this.tagId = tagId;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public short getTagId() { return tagId; }
    public int getStartTime() { return startTime; }
    public int getEndTime() { return endTime; }

    @Override
    public short getLength() {
        // 4-byte TLV header + 10-byte payload (u16 + u32 + u32)
        return 14;
    }

    @Override
    protected void fillinTypeSpecificData(ByteBuffer buffer) {
        buffer.putShort(tagId);
        buffer.putInt(startTime);
        buffer.putInt(endTime);
    }

    @Override
    protected void fillFromRawDataAsBuffer(ByteBuffer buffer) {
        if (buffer.remaining() >= 10) {
            tagId     = buffer.getShort();
            startTime = buffer.getInt();
            endTime   = buffer.getInt();
        }
    }

    @Override
    public short getType() {
        return WithingsStructureType.FEATURE_TAG_DEPRECATED;
    }
}
