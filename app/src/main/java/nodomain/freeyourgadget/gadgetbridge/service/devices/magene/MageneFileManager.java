package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import static nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.FileTransformControlPacket.getMageneCRC16;

import androidx.annotation.Nullable;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;

import org.slf4j.Logger;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFileInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFilePacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.FileTransformControlPacket;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;


public class MageneFileManager {

    private static final Logger LOG = LoggerFactory.getLogger(MageneFileManager.class);
    private static final int MAX_START_TRANSFER_RETRIES = 10; // Or your preferred number of retries

    private static class UploadRequest {
        final byte[] fileData;
        final String filename;
        final FileType fileType;
        int startTransferRetryCount = 0; // New field

        UploadRequest(byte[] fileData, String filename, FileType fileType) {
            this.fileData = fileData;
            this.filename = filename;
            this.fileType = fileType;
            // startTransferRetryCount defaults to 0
        }
    }


    public enum UploadState {
        IDLE,
        AWAITING_MTU,
        READY_TO_SEND_INFO,
        UPLOADING,
        COMPLETE,
        ERROR
    }

    private MageneSupport support;
    private final Queue<UploadRequest> uploadQueue = new LinkedList<>();
    private UploadRequest currentUploadRequest;
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


    public boolean isQueueEmpty() {
        return uploadQueue.isEmpty();
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



    public void startUpload(byte[] fileData, String filename, FileType fileType) {
        if (fileData == null || filename == null || fileType == null) {
            LOG.error("Attempted to start upload with null fileData, filename, or fileType.");
            // Or throw new IllegalArgumentException("File data, filename, and fileType cannot be null.");
            return;
        }
        LOG.info("Queueing file for upload: {}", filename);
        UploadRequest newRequest = new UploadRequest(fileData, filename, fileType);
        uploadQueue.add(newRequest);
        tryProcessNextInQueue();
    }

    private void tryProcessNextInQueue() {
        if (state != UploadState.IDLE) {
            LOG.debug("Not in IDLE state (current: {}), cannot process next in queue yet.", state);
            return; // Already processing something or in an error state that wasn't cleared to IDLE
        }

        if (uploadQueue.isEmpty()) {
            LOG.debug("Upload queue is empty. Nothing to process.");
            return;
        }

        currentUploadRequest = uploadQueue.peek(); // Get the next item without removing it yet
        if (currentUploadRequest == null) { // Should not happen if queue.isEmpty() is false
            LOG.error("Queue was not empty but peeked null. This is unexpected.");
            uploadQueue.poll(); // Remove the problematic null
            tryProcessNextInQueue(); // Try again, maybe next item is fine
            return;
        }

        LOG.info("Attempting to process next file in queue: {}", currentUploadRequest.filename);
        resetForNewUpload(); // Clear out old file-specific data

        this.fileData = currentUploadRequest.fileData;
        this.filename = currentUploadRequest.filename;
        this.fileType = currentUploadRequest.fileType;
        this.fileSize = this.fileData.length;

        this.fileCrc = getMageneCRC16(this.fileData);

        this.state = UploadState.AWAITING_MTU;
        LOG.debug("State set to AWAITING_MTU for file: {}", this.filename);

        TransactionBuilder builder = support.createTransactionBuilder("start upload: " + this.filename);

        FileTransformControlPacket.Write startUploadPacket = new FileTransformControlPacket.Write(
                nodeAddress,
                FileTransformControlPacket.FileTransformControlCode.START_TRANSFER,
                1, // Protocol version or sequence number, confirm if this is static or needs to be dynamic
                0, // Number of files, usually 1 for single file transfer as per many protocols
                (int)(System.currentTimeMillis()/1000), // SPU version, adjust if needed (TODO: Make configurable or detect?)
                Collections.singletonList(this.filename)
        );
        builder.write(support.writeCharacteristic, startUploadPacket.toByteArray());
        builder.queue();

        // We now assume builder.queue() was successful for sending START_TRANSFER.
        // The device will respond with MTU via a characteristic notification,
        // which should trigger handleMtuResponse() externally.
        LOG.info("START_TRANSFER command queued for file: {}. Waiting for MTU response from device.", this.filename);
    }

    public void handleMtuResponse(FileTransformControlPacket.Response response) {
        if (currentUploadRequest == null) {
            LOG.error("handleMtuResponse called but no current upload request is active. Ignoring. Current state: {}", state);
            return;
        }

        if (state != UploadState.AWAITING_MTU) {
            LOG.warn("Received MTU response for file '{}' but not in AWAITING_MTU state. Current state: {}. Ignoring MTU response.", currentUploadRequest.filename, state);
            return;
        }

        // ***** START: New ready status check and retry logic *****
        // IMPORTANT: Replace 'FileTransformControlPacket.DeviceReadyStatus.READY' with the actual ready status value/enum
        if (response.getReadyStatus() != FileTransformControlPacket.FileTransformReadyStatus.READY) {
            LOG.warn("Device not ready for file '{}'. Status: {}. Retry count: {}/{}",
                    currentUploadRequest.filename, response.getReadyStatus(), currentUploadRequest.startTransferRetryCount, MAX_START_TRANSFER_RETRIES);

            if (currentUploadRequest.startTransferRetryCount < MAX_START_TRANSFER_RETRIES) {
                currentUploadRequest.startTransferRetryCount++;
                LOG.info("Retrying START_TRANSFER for file: {}", currentUploadRequest.filename);

                TransactionBuilder builder = support.createTransactionBuilder("retry start_transfer: " + currentUploadRequest.filename);
                FileTransformControlPacket.Write startUploadPacket = new FileTransformControlPacket.Write(
                        nodeAddress,
                        FileTransformControlPacket.FileTransformControlCode.START_TRANSFER,
                        1, // Protocol version or sequence number
                        0, // Number of files
                        (int)(System.currentTimeMillis()/1000), // SPU version
                        Collections.singletonList(currentUploadRequest.filename)
                );
                builder.write(support.writeCharacteristic, startUploadPacket.toByteArray());
                try {
                    builder.queue();
                    // State remains AWAITING_MTU
                    LOG.info("Re-queued START_TRANSFER command for file: {} (attempt {}). Waiting for MTU response.",
                            currentUploadRequest.filename, currentUploadRequest.startTransferRetryCount);
                } catch (Exception e) {
                    LOG.error("Failed to re-queue START_TRANSFER command during retry for file: {}. Error: {}", currentUploadRequest.filename, e.getMessage(), e);
                    handleCurrentFileUploadError("Failed to re-send START_TRANSFER command: " + e.getMessage());
                }
            } else {
                LOG.error("Device not ready for file '{}' after {} retries. Aborting upload for this file.",
                        currentUploadRequest.filename, MAX_START_TRANSFER_RETRIES);
                handleCurrentFileUploadError("Device not ready after max START_TRANSFER retries.");
            }
            return; // Return whether we retried or errored out
        }
        // ***** END: New ready status check and retry logic *****

        // If device IS ready, proceed as before
        this.mtu = response.getMtu();
        LOG.info("Received MTU: {} for file: {} (Device is READY)", this.mtu, currentUploadRequest.filename);

        if (this.mtu <= 0) {
            LOG.error("Received invalid MTU size: {} for file: {}", this.mtu, currentUploadRequest.filename);
            handleCurrentFileUploadError("Invalid MTU received: " + this.mtu);
            return;
        }

        int dataPerChunk = this.mtu - 8; // Placeholder for packet header overhead
        if (dataPerChunk <= 0) {
            LOG.error("MTU {} is too small to send any data per chunk (dataPerChunk <= 0) for file: {}", this.mtu, currentUploadRequest.filename);
            handleCurrentFileUploadError("MTU too small for data chunks: " + this.mtu);
            return;
        }
        this.totalChunks = (int) Math.ceil((double) this.fileSize / dataPerChunk);
        LOG.debug("Calculated totalChunks: {} for fileSize: {} with dataPerChunk: {} (MTU: {})", totalChunks, fileSize, dataPerChunk, this.mtu);

        this.state = UploadState.READY_TO_SEND_INFO;
        LOG.debug("State set to READY_TO_SEND_INFO for file: {}", currentUploadRequest.filename);

        TransactionBuilder infoBuilder = support.createTransactionBuilder("send file info: " + currentUploadRequest.filename);
        CommonFileInfoPacket.Write fileInfoPacket = new CommonFileInfoPacket.Write(
                nodeAddress,
                fileType,
                (byte) 0, // transformType
                (int) fileSize,
                totalChunks,
                fileCrc,
                this.filename
        );
        infoBuilder.write(support.writeCharacteristic, fileInfoPacket.toByteArray());

        try {
            infoBuilder.queue();
            LOG.info("CommonFileInfoPacket queued for file: {}. Waiting for device acknowledgment via onFileInfoSent().", currentUploadRequest.filename);
        } catch (Exception e) {
            LOG.error("Failed to queue CommonFileInfoPacket for file: {}. Error: {}", currentUploadRequest.filename, e.getMessage(), e);
            handleCurrentFileUploadError("Failed to send CommonFileInfoPacket: " + e.getMessage());
        }
    }


    /**
     * Call this after the {@link CommonFileInfoPacket.Write} command has been successfully sent
     * and acknowledged by the device.
     * This transitions the manager to the UPLOADING state and queues all file chunks.
     *
     * @param response The parsed {@link CommonFileInfoPacket.Response} from the device.
     */
    public void onFileInfoSent(CommonFileInfoPacket.Response response) {
        if (currentUploadRequest == null) {
            LOG.error("onFileInfoSent called but no current upload request is active. Ignoring. Current state: {}", state);
            return;
        }

        if (state != UploadState.READY_TO_SEND_INFO) {
            LOG.warn("onFileInfoSent called for file '{}' but not in READY_TO_SEND_INFO state. Current state: {}. Ignoring.", currentUploadRequest.filename, state);
            // If we are in another state, this might be a late/unexpected response.
            // Or, an error might have occurred, and this is a delayed success from a previous step.
            // If an error already moved us to IDLE and tried next, we should not proceed with this old context.
            return;
        }

        // TODO: Add response validation if necessary.
        // For example, if (response.getStatus() != ExpectedStatus.SUCCESS) {
        //     handleCurrentFileUploadError("File Info Acknowledgment indicated an error: " + response.getStatus());
        //     return;
        // }

        LOG.info("File Info Acknowledged by device for file: {}. Proceeding to send chunks.", currentUploadRequest.filename);
        this.state = UploadState.UPLOADING;
        LOG.debug("State set to UPLOADING for file: {}", currentUploadRequest.filename);

        sendAllFileChunks();
    }

    private void sendAllFileChunks() {
        if (state != UploadState.UPLOADING) {
            LOG.error("sendAllFileChunks called but not in UPLOADING state. Current state: {}. Aborting chunk sending for file: {}", state, currentUploadRequest != null ? currentUploadRequest.filename : "unknown");
            // This case should ideally not happen if state transitions are correct.
            // If it does, an error has occurred, or state was managed incorrectly.
            // Handling the error for the current file ensures the queue can advance.
            if (currentUploadRequest != null) { // Ensure we have a context for the error
                handleCurrentFileUploadError("Attempted to send chunks in non-UPLOADING state: " + state);
            }
            return;
        }

        if (fileData == null || currentUploadRequest == null) {
            LOG.error("sendAllFileChunks called with null fileData or currentUploadRequest.");
            handleCurrentFileUploadError("Internal error: null fileData or currentUploadRequest before sending chunks.");
            return;
        }

        LOG.info("Preparing to send all data chunks for file: {}", currentUploadRequest.filename);
        TransactionBuilder builder = support.createTransactionBuilder("upload all file chunks: " + currentUploadRequest.filename);

        currentReadPosition = 0; // Ensure we start from the beginning of the file data
        nextChunkIndex = 0;      // Reset chunk index for this file

        // Define dataPerChunk based on MTU. Ensure this calculation is robust.
        // The '8' is a placeholder for CommonFilePacket header overhead.
        // Replace with CommonFilePacket.PACKET_HEADER_OVERHEAD or similar if available.
        int dataPerChunk = this.mtu - 8;
        if (dataPerChunk <= 0) {
            LOG.error("Cannot send chunks, dataPerChunk is {} (MTU: {}) for file: {}", dataPerChunk, this.mtu, currentUploadRequest.filename);
            handleCurrentFileUploadError("MTU too small for data packet headers during chunking.");
            return;
        }

        while (currentReadPosition < fileSize) {
            int bytesToRead = (int) Math.min(dataPerChunk, fileSize - currentReadPosition);
            byte[] chunkData = new byte[bytesToRead];
            System.arraycopy(this.fileData, currentReadPosition, chunkData, 0, bytesToRead);

            currentReadPosition += bytesToRead;
            nextChunkIndex++; // Magene chunk index is 1-based, ensure CommonFilePacket handles this or adjust here

            CommonFilePacket.Write packet = new CommonFilePacket.Write(nodeAddress, nextChunkIndex, chunkData);
            builder.write(support.writeCharacteristic, packet.toByteArray());
        }

        try {
            builder.queue();
            LOG.info("All ({}) data chunks for file '{}' have been queued for sending. Waiting for device acknowledgment to trigger endUpload().", nextChunkIndex, currentUploadRequest.filename);
        } catch (Exception e) {
            LOG.error("Failed to queue data chunks for file: {}. Error: {}", currentUploadRequest.filename, e.getMessage(), e);
            handleCurrentFileUploadError("Failed to queue data chunks: " + e.getMessage());
        }
        // If builder.queue() succeeds, MageneSupport layer is expected to call endUpload()
        // after the device acknowledges all data has been received.
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
     * Call this method when the device has acknowledged receipt of all data chunks.
     * This method will send the END_TRANSFER command.
     * If the END_TRANSFER command is successfully queued, the current upload is considered successful.
     *
     * @param response The notification from the device acknowledging the last data chunk (may not be strictly needed if its only role is to trigger this call).
     */
    public void endUpload(CommonFilePacket.Notification response) { // Removed throws IOException
        if (currentUploadRequest == null) {
            LOG.error("endUpload called but no current upload request is active. Ignoring.");
            // This might happen if an error occurred and reset the state before this callback arrived.
            return;
        }

        if (state != UploadState.UPLOADING) {
            LOG.warn("endUpload called for file '{}' but not in UPLOADING state. Current state: {}. This might be a delayed callback after an error or an unexpected sequence. Ignoring.", currentUploadRequest.filename, state);
            return;
        }

        LOG.info("All data chunks for file '{}' acknowledged by device. Sending END_TRANSFER.", currentUploadRequest.filename);

        FileTransformControlPacket.Write endUploadPacket = new FileTransformControlPacket.Write(
                nodeAddress, FileTransformControlPacket.FileTransformControlCode.END_TRANSFER, 0, 0, 0, Collections.emptyList()
        );

        TransactionBuilder builder = support.createTransactionBuilder("end upload: " + currentUploadRequest.filename);
        builder.write(support.writeCharacteristic, endUploadPacket.toByteArray());

        try {
            builder.queue();
            LOG.info("END_TRANSFER command successfully queued for file: {}.", currentUploadRequest.filename);
            // Since END_TRANSFER is the final step and we assume it will be accepted by the device if successfully queued,
            // we can now consider this file upload successful.
            handleCurrentFileUploadSuccess(); // This will set state to IDLE and try to process the next file in queue
        } catch (Exception e) {
            LOG.error("Failed to queue END_TRANSFER command for file: {}. Error: {}", currentUploadRequest.filename, e.getMessage(), e);
            handleCurrentFileUploadError("Failed to send END_TRANSFER: " + e.getMessage());
        }
    }


    private void reset() { // Removed 'throws IOException' as file operations are not directly here anymore
        LOG.debug("Resetting MageneFileManager. Clearing queue and current upload.");
        uploadQueue.clear();
        currentUploadRequest = null;

        state = UploadState.IDLE;
        // these will be set by tryProcessNextInQueue from currentUploadRequest
        resetForNewUpload();
    }

    private void resetForNewUpload() {
        LOG.debug("Resetting for a new upload from the queue.");
        // Clear fields specific to the file being uploaded
        // fileData, filename, fileType, fileSize, fileCrc will be set from currentUploadRequest
        this.fileData = null;
        this.filename = null;
        this.fileType = null;
        this.fileSize = 0;
        this.fileCrc = 0;

        // Clear fields specific to the transfer mechanics of that file
        this.mtu = 0;
        this.totalChunks = 0;
        this.nextChunkIndex = 0;
        this.currentReadPosition = 0;
        // DO NOT change 'state' here, it's managed by the calling method (tryProcessNextInQueue)
        // DO NOT clear 'currentUploadRequest' here

    }

    private void handleCurrentFileUploadSuccess() {
        if (currentUploadRequest == null) {
            LOG.warn("handleCurrentFileUploadSuccess called with no current upload request.");
            // Should still reset to IDLE and try next, just in case.
        } else {
            LOG.info("Successfully uploaded file: {}", currentUploadRequest.filename);
            uploadQueue.poll(); // Remove the successfully uploaded item from the head of the queue
        }
        currentUploadRequest = null; // Clear the current request
        state = UploadState.IDLE;    // Set state to IDLE, ready for next
        tryProcessNextInQueue();     // Attempt to process the next item
    }

    private void handleCurrentFileUploadError(String errorMessage) {
        if (currentUploadRequest == null) {
            LOG.error("handleCurrentFileUploadError called with no current upload request. Error: {}", errorMessage);
            // Should still reset to IDLE and try next, just in case.
        } else {
            LOG.error("Failed to upload file: {}. Reason: {}", currentUploadRequest.filename, errorMessage);
            uploadQueue.poll(); // Remove the failed item from the head of the queue
        }
        currentUploadRequest = null; // Clear the current request
        state = UploadState.ERROR; // Set state to ERROR to indicate a problem
        // Consider if you want to immediately go IDLE and try next, or stay in ERROR
        // For now, let's go IDLE and try the next one. If all fail, queue will empty.
        state = UploadState.IDLE;
        tryProcessNextInQueue();     // Attempt to process the next item
    }


}