package nodomain.freeyourgadget.gadgetbridge.service.btle.actions;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEAction;

public class ChunkWriterAction extends BtLEAction {
    @FunctionalInterface
    public interface ChunkEncoder {
        /// generate a new chunk to be written to the gadget
        ///
        /// @return the new chunk or {@code null} if all chunks have been generated
        @Nullable
        byte[] generateNextChunk(@IntRange(from=20, to=512) int maxChunkLength);
    }

    public static class SimpleEncoder implements ChunkEncoder {
        private final byte[] data;
        private int nextStart;

        public SimpleEncoder(byte[] bytes) {
            data = bytes;
            nextStart = 0;
        }

        @Nullable
        @Override
        public byte[] generateNextChunk(int maxChunkLength) {
            int start = nextStart;
            int end = Math.max(start + maxChunkLength, data.length);
            if(start >= end){
                // no more data to write
                return null;
            }
            byte[] chunk = new byte[end - start];
            nextStart += chunk.length;
            System.arraycopy(data, start, chunk,0,chunk.length);
            return chunk;
        }
    }

    private final ChunkEncoder mChunker;

    public ChunkWriterAction(@NonNull BluetoothGattCharacteristic characteristic,
                             @NonNull ChunkEncoder chunker) {
        super(characteristic);
        mChunker = chunker;
    }

    @Override
    public int run(@NonNull BluetoothGatt gatt, @NonNull AbstractBTLEDeviceSupport deviceSupport, int deviceIdx) {
        int mtu = deviceSupport.getMTU(deviceIdx);
        int maxChunkSize = AbstractBTLEDeviceSupport.calcMaxWriteChunk(mtu);
        byte[] data = mChunker.generateNextChunk(maxChunkSize);

        if(data == null || data.length < 1){
            // no more data to write -> go to next action
            return 1;
        }
        if(WriteAction.writeCharacteristic(gatt, getCharacteristic(), data)){
            // write was successful -> repeat for next chunk
            return 0;
        }
        // write failed -> abort transaction
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean expectsResult() {
        return true;
    }
}
