package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import static nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.FileTransformControlPacket.getMageneCRC16;

import java.io.IOException;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFileInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFilePacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.FileTransformControlPacket;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;


public class MageneFileManager {

    public enum UploadState {
        IDLE,
        AWAITING_MTU,
        READY_TO_SEND_INFO,
        UPLOADING,
        COMPLETE,
        ERROR
    }

    private MageneSupport support;

    private UploadState state = UploadState.IDLE;
    private byte[] fileData;
    private String filename;
    private FileType fileType;
    private long fileSize;
    private int fileCrc;
    private int mtu;
    private int totalChunks;
    private int nextChunkIndex = 0; // The index of the packet to be sent
    private int currentReadPosition = 0; // The position in the fileData byte array

    private final byte nodeAddress;


    public MageneFileManager(byte nodeAddress, MageneSupport support) {
        this.nodeAddress = nodeAddress;
        this.support = support;
    }

    /**
     * Starts the file upload process.
     * This prepares the file, calculates its CRC, and returns the first command
     * needed to get the device's MTU.
     *
     * @param fileData The raw byte data of the file to upload.
     * @param filename The name of the file.
     * @param fileType The {@link FileType} of the file.
     * @throws IOException If the file cannot be read or CRC calculation fails.
     */
    public void startUpload(byte[] fileData, String filename, FileType fileType) throws IOException {
        if (state != UploadState.IDLE && state != UploadState.COMPLETE && state != UploadState.ERROR) {
            throw new IllegalStateException("Manager is already in an active upload state: " + state);
        }
        reset();

        this.fileData = fileData;
        this.filename = filename;
        this.fileType = fileType;
        this.fileSize = fileData.length;
        this.fileCrc = getMageneCRC16(fileData);

        this.state = UploadState.AWAITING_MTU;

        TransactionBuilder builder = support.createTransactionBuilder("start upload");

        FileTransformControlPacket.Write startUpload = new FileTransformControlPacket.Write(
                nodeAddress,
                FileTransformControlPacket.FileTransformControlCode.START_TRANSFER,
                1,
                0,
                0, // SPU version, adjust if needed FIXME
                Collections.singletonList(this.filename)
        );
        builder.write(support.writeCharacteristic, startUpload.toByteArray());

        builder.queue();
        return;

    }

    /**
     * Handles the response from the initial START_TRANSFER command.
     * This extracts the MTU, calculates the number of chunks, and returns the
     * command to send the file's metadata.
     *
     * @param response The parsed {@link FileTransformControlPacket.Response}.
     */
    public void handleMtuResponse(FileTransformControlPacket.Response response) {
        if (state != UploadState.AWAITING_MTU) {
            throw new IllegalStateException("Not awaiting MTU response. Current state: " + state);
        }

        this.mtu = response.getMtu();
        if (this.mtu <= 0) {
            this.state = UploadState.ERROR;
            throw new IllegalArgumentException("Received invalid MTU size: " + this.mtu);
        }

        // Calculate chunk size, leaving room for packet headers (4 bytes for CommonFilePacket)
        int dataPerChunk = this.mtu-8;
        this.totalChunks = (int) Math.ceil((double) this.fileSize / dataPerChunk);

        this.state = UploadState.READY_TO_SEND_INFO;

        TransactionBuilder builder = support.createTransactionBuilder("send file info");

        CommonFileInfoPacket.Write fileInfo = new CommonFileInfoPacket.Write(
                nodeAddress,
                fileType,
                (byte) 0, // transformType (e.g., 0 for upload)
                (int) fileSize,
                totalChunks,
                fileCrc,
                this.filename
        );

        builder.write(support.writeCharacteristic, fileInfo.toByteArray());
        builder.queue();

        return;
    }

    /**
     * Call this after the {@link CommonFileInfoPacket.Write} command has been successfully sent.
     * This transitions the manager to the UPLOADING state, ready to send chunks.
     */
    public void onFileInfoSent(CommonFileInfoPacket.Response response) {
        // TODO: add response validation
        if (state != UploadState.READY_TO_SEND_INFO) {
            throw new IllegalStateException("Not ready to send file info. Current state: " + state);
        }
        this.state = UploadState.UPLOADING;
        try {
            getNextChunkPacket();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the next file chunk packet to be sent.
     *
     * @return A {@link CommonFilePacket.Write} message, or {@code null} if all chunks have been sent.
     * @throws IOException If there is an error reading the file.
     */
    public void getNextChunkPacket() throws IOException {
        if (state != UploadState.UPLOADING) {
            throw new IllegalStateException("Not in UPLOADING state. Current state: " + state);
        }

        TransactionBuilder builder = support.createTransactionBuilder("upload file chunk");
        while (currentReadPosition < fileSize) {

            // Calculate how much data to send in this chunk
            int dataPerChunk = this.mtu - 8; // Max data size per chunk, leaving room for headers
            int bytesToRead = (int) Math.min(dataPerChunk, fileSize - currentReadPosition);

            byte[] chunkData = new byte[bytesToRead];
            System.arraycopy(this.fileData, currentReadPosition, chunkData, 0, bytesToRead);

            // Update state for the next call
            currentReadPosition += bytesToRead;
            nextChunkIndex++;

            // Create the packet for the current chunk
            CommonFilePacket.Write packet = new CommonFilePacket.Write(nodeAddress, nextChunkIndex, chunkData);

            builder.write(support.writeCharacteristic, packet.toByteArray());

        }

        builder.queue();

        return;
    }

    /**
     * Generates the final command to signal the end of the file transfer.
     * Call this after all chunks have been successfully sent.
     *
     * @return The final {@link FileTransformControlPacket.Write} command.
     */
    public void endUpload(CommonFilePacket.Notification response) throws IOException {
        reset();
        this.state = UploadState.COMPLETE;
        FileTransformControlPacket.Write endUpload = new FileTransformControlPacket.Write(
                nodeAddress, FileTransformControlPacket.FileTransformControlCode.END_TRANSFER, 0, 0, 0, Collections.emptyList()
        );

        TransactionBuilder builder = support.createTransactionBuilder("end upload");
        builder.write(support.writeCharacteristic, endUpload.toByteArray());

        builder.queue();
        return;
    }

    public UploadState getState() { return state; }
    public double getUploadProgress() {
        if (totalChunks == 0) return 0.0;
        return (double) nextChunkIndex / totalChunks;
    }

    private void reset() throws IOException {
        state = UploadState.IDLE;
        fileData = null;
        filename = null;
        fileType = null;
        fileSize = 0;
        fileCrc = 0;
        mtu = 0;
        totalChunks = 0;
        nextChunkIndex = 0;
        currentReadPosition = 0;
    }
}