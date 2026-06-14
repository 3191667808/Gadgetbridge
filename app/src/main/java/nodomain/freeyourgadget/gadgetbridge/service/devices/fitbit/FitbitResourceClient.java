/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import java.util.Collections;
import java.util.List;

final class FitbitResourceClient {
    static final String PATH_DEVICE_INFO = "md/606";
    static final String PATH_LIVE_ACTIVITY = "liveactivity";
    static final String PATH_SYNC_STATUS = "sync/status";
    static final String PATH_SYNC_CONFIG = "sync/config";
    static final String PATH_INBOX = "app/inbox";
    static final String PATH_INBOX_STATUS = "app/inbox/status";
    static final String PATH_SWITCHBOARD_RECORDS = "sb/records";
    static final String PATH_SWITCHBOARD_DELETE_RECORDS = "sb/delete-records";
    static final String PATH_WIFI_OPERATION_STATUS = "wifi/op/status";
    static final String PATH_APP_DOWNLOAD_STATUS = "app/download/status";

    private final FitbitDtls fitbitDtls;
    private final Sender sender;

    FitbitResourceClient(final FitbitDtls fitbitDtls, final Sender sender) {
        this.fitbitDtls = fitbitDtls;
        this.sender = sender;
    }

    void requestDeviceInfo() {
        get("Fitbit device-info request", PATH_DEVICE_INFO);
    }

    void requestLiveActivity() {
        get("Fitbit liveactivity request", PATH_LIVE_ACTIVITY);
    }

    void requestSyncStatus() {
        get("Fitbit sync-status request", PATH_SYNC_STATUS);
    }

    void requestSyncConfig() {
        get("Fitbit sync-config request", PATH_SYNC_CONFIG);
    }

    void writeSyncConfig(final byte[] payload) {
        put("Fitbit sync-config write", PATH_SYNC_CONFIG, payload);
    }

    void requestInboxStatus() {
        get("Fitbit inbox-status request", PATH_INBOX_STATUS);
    }

    void sendInboxPayload(final byte[] payload) {
        post("Fitbit inbox send", PATH_INBOX, payload);
    }

    void sendSwitchboardRecord(final byte[] payload) {
        post("Fitbit switchboard record send", PATH_SWITCHBOARD_RECORDS, payload);
    }

    void deleteSwitchboardRecords(final byte[] payload) {
        post("Fitbit switchboard record delete", PATH_SWITCHBOARD_DELETE_RECORDS, payload);
    }

    void requestWifiOperationStatus() {
        get("Fitbit wifi operation status request", PATH_WIFI_OPERATION_STATUS);
    }

    void requestAppDownloadStatus() {
        get("Fitbit app download status request", PATH_APP_DOWNLOAD_STATUS);
    }

    private void get(final String taskName, final String path) {
        get(taskName, path, Collections.<FitbitCoap.Option>emptyList());
    }

    private void get(final String taskName, final String path, final List<FitbitCoap.Option> options) {
        send(taskName, fitbitDtls.buildCoapGetRequest(path, options));
    }

    private void post(final String taskName, final String path, final byte[] payload) {
        send(taskName, fitbitDtls.buildCoapPostRequest(path, payload));
    }

    private void put(final String taskName, final String path, final byte[] payload) {
        send(taskName, fitbitDtls.buildCoapPutRequest(path, payload));
    }

    private void send(final String taskName, final byte[] ipv4Packet) {
        if (ipv4Packet == null) {
            return;
        }
        sender.sendFitbitCoapRequest(taskName, ipv4Packet);
    }

    interface Sender {
        void sendFitbitCoapRequest(String taskName, byte[] ipv4Packet);
    }
}
