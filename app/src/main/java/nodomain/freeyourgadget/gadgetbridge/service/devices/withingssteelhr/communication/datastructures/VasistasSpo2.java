/*  Copyright (C) 2026 Gadgetbridge contributors

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

public class VasistasSpo2 extends WithingsStructure {
    private int error;
    private int quality;
    private int spo2;

    public int getError() {
        return error;
    }

    public int getQuality() {
        return quality;
    }

    public int getSpo2() {
        return spo2;
    }

    @Override
    public short getLength() {
        return 16;
    }

    @Override
    protected void fillinTypeSpecificData(final ByteBuffer buffer) {
        buffer.putInt(error);
        buffer.putInt(quality);
        buffer.putInt(spo2);
    }

    @Override
    protected void fillFromRawDataAsBuffer(final ByteBuffer rawDataBuffer) {
        error = rawDataBuffer.getInt();
        quality = rawDataBuffer.getInt();
        spo2 = rawDataBuffer.getInt();
    }

    @Override
    public short getType() {
        return WithingsStructureType.VASISTAS_SPO2;
    }
}
