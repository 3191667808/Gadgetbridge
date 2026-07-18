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

public class EcgRecord {
    private final Long sessionId;
    private final long deviceId;
    private final long userId;
    private final long startTimestamp;
    private final long endTimestamp;
    private final String sourceApp;
    private final int averageHeartRate;
    private final long deviceHintCode;
    private final long userSymptoms;

    public EcgRecord(final Long sessionId,
                     final long deviceId,
                     final long userId,
                     final long startTimestamp,
                     final long endTimestamp,
                     final String sourceApp,
                     final int averageHeartRate,
                     final long deviceHintCode,
                     final long userSymptoms) {
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.userId = userId;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.sourceApp = sourceApp;
        this.averageHeartRate = averageHeartRate;
        this.deviceHintCode = deviceHintCode;
        this.userSymptoms = userSymptoms;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public long getUserId() {
        return userId;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public String getSourceApp() {
        return sourceApp;
    }

    public int getAverageHeartRate() {
        return averageHeartRate;
    }

    public long getDeviceHintCode() {
        return deviceHintCode;
    }

    public long getUserSymptoms() {
        return userSymptoms;
    }
}
