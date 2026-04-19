/*  Copyright (C) 2019-2024 Andreas Shimokawa, Daniel Dakhno, Taavi Eomäe

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit;

import android.bluetooth.BluetoothGattCharacteristic;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil_hr.file.ResultCode;

public class DownloadFileRequest extends FileRequest {
    private enum DownloadState {
        WAITING_FOR_HEADER,
        RECEIVING_PAYLOAD,
        WAITING_FOR_COMPLETION,
        COMPLETED
    }

    private final ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
    private DownloadState downloadState = DownloadState.WAITING_FOR_HEADER;
    public byte[] file = null;
    public int fileHandle;
    public int size;
    public long timeStamp;
    public boolean isValid = true;
    public String validationError = null;

    public DownloadFileRequest(short handle){
        init(handle, 0, 65535);
    }

    public DownloadFileRequest(short handle, int offset, int length) {
        init(handle, offset, length);
    }

    private void init(short handle, int offset, int length) {
        ByteBuffer buffer = createBuffer();
        buffer.putShort(handle);
        buffer.putInt(offset);
        buffer.putInt(length);
        this.data = buffer.array();
        this.fileHandle = handle;
        this.timeStamp = System.currentTimeMillis();
    }

    @Override
    public byte[] getStartSequence() {
        return new byte[]{1};
    }

    @Override
    public int getPayloadLength() {
        return 11;
    }

    @Override
    public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] data) {
        super.handleResponse(characteristic, data);
        final String uuid = characteristic.getUuid().toString();
        if (uuid.equals("3dda0003-957f-7d4a-34a6-74696673696d")) {
            handleControlPacket(data);
        } else if (uuid.equals("3dda0004-957f-7d4a-34a6-74696673696d")) {
            handlePayloadPacket(data);
        }
    }

    private void handleControlPacket(final byte[] data) {
        if (data.length == 0) {
            invalidateAndComplete("Received empty file download control packet");
            return;
        }

        final int opcode = data[0] & 0x0F;
        if (opcode == 0x02) {
            if (downloadState != DownloadState.WAITING_FOR_HEADER) {
                invalidateAndComplete("Received duplicate file download header");
                return;
            }
            if (data.length < 4) {
                invalidateAndComplete("File download header was shorter than expected");
                return;
            }

            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.status = buffer.get(3);
            final short realHandle = buffer.getShort(1);
            final ResultCode code = ResultCode.fromCode(status);
            if (!code.inidicatesSuccess()) {
                invalidateAndComplete("File download failed with status " + code);
                log("wrong status: " + code + "   (" + status + ")");
            } else if (realHandle != fileHandle) {
                invalidateAndComplete("File download returned unexpected handle " + realHandle);
                log("wrong handle: " + realHandle);
            } else {
                downloadState = DownloadState.RECEIVING_PAYLOAD;
                log("handle: " + realHandle);
            }
            return;
        }

        if (opcode == 0x08) {
            if (data.length < 3) {
                invalidateAndComplete("File download completion packet was shorter than expected");
                return;
            }

            final short realHandle = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(1);
            if (realHandle != fileHandle) {
                invalidateAndComplete("File download completed for unexpected handle " + realHandle);
                log("wrong handle: " + realHandle);
            } else if (downloadState == DownloadState.WAITING_FOR_HEADER) {
                invalidateAndComplete("File download completed before any header was received");
            } else if (downloadState == DownloadState.RECEIVING_PAYLOAD) {
                invalidateAndComplete("File download completed before the final payload chunk arrived");
            } else {
                downloadState = DownloadState.COMPLETED;
                completed = true;
            }
            return;
        }

        invalidateAndComplete("Received unexpected file download control opcode 0x"
                + Integer.toHexString(opcode));
    }

    private void handlePayloadPacket(final byte[] data) {
        if (downloadState == DownloadState.WAITING_FOR_HEADER) {
            invalidateAndComplete("Received file payload before the download header");
            return;
        }
        if (downloadState == DownloadState.WAITING_FOR_COMPLETION
                || downloadState == DownloadState.COMPLETED) {
            invalidateAndComplete("Received file payload after the final payload chunk");
            return;
        }
        if (data.length < 2) {
            invalidateAndComplete("Received empty file payload chunk");
            return;
        }

        payloadBuffer.write(data, 1, data.length - 1);
        if ((data[0] & 0x80) == 0) {
            return;
        }

        file = payloadBuffer.toByteArray();
        this.size = file.length;
        downloadState = DownloadState.WAITING_FOR_COMPLETION;
        log("downloaded file size: " + size);

        if (file.length < 4) {
            invalidate("Downloaded file was too short to contain a checksum");
            return;
        }

        final CRC32 crc = new CRC32();
        crc.update(file, 0, file.length - 4);
        final ByteBuffer checksumBuffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        final long expectedChecksum = cutBits(checksumBuffer.getInt(size - 4));
        final long actualChecksum = crc.getValue();
        if (actualChecksum != expectedChecksum) {
            invalidate("Downloaded file failed CRC verification");
            log("checksum invalid    expected: " + expectedChecksum + "   actual: " + actualChecksum);
        }
    }

    private void invalidate(final String message) {
        isValid = false;
        if (validationError == null) {
            validationError = message;
        }
    }

    private void invalidateAndComplete(final String message) {
        invalidate(message);
        downloadState = DownloadState.COMPLETED;
        completed = true;
    }

    long cutBits(int value) {
        return value & 0b11111111111111111111111111111111L;
    }
}
