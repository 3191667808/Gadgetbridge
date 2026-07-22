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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import java.util.regex.Pattern;

final class YcbtSessionNegotiation {
    private static final Pattern SUPPORTED_MODEL = Pattern.compile("^R(?:10|11)M$");

    enum Stage {
        IDLE,
        MODEL,
        BATTERY,
        CAPABILITIES,
        READY,
        FAILED
    }

    enum Result {
        IGNORED,
        REQUEST_BATTERY,
        REQUEST_CAPABILITIES,
        READY,
        FAILED
    }

    enum Timeout {
        IGNORED,
        RETRY,
        FAILED
    }

    private final int maximumAttempts;
    private Stage stage = Stage.IDLE;
    private int attempts;

    YcbtSessionNegotiation(final int maximumAttempts) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        this.maximumAttempts = maximumAttempts;
    }

    Stage begin() {
        stage = Stage.MODEL;
        attempts = 1;
        return stage;
    }

    Result handleModel(final String model) {
        if (stage != Stage.MODEL) {
            return Result.IGNORED;
        }
        if (model == null || !SUPPORTED_MODEL.matcher(model).matches()) {
            stage = Stage.FAILED;
            return Result.FAILED;
        }
        advance(Stage.BATTERY);
        return Result.REQUEST_BATTERY;
    }

    Result handleBattery(final Integer level) {
        if (stage != Stage.BATTERY || level == null) {
            return Result.IGNORED;
        }
        advance(Stage.CAPABILITIES);
        return Result.REQUEST_CAPABILITIES;
    }

    Result handleCapabilities() {
        if (stage != Stage.CAPABILITIES) {
            return Result.IGNORED;
        }
        stage = Stage.READY;
        attempts = 0;
        return Result.READY;
    }

    Timeout onTimeout() {
        if (stage == Stage.IDLE || stage == Stage.READY || stage == Stage.FAILED) {
            return Timeout.IGNORED;
        }
        if (attempts < maximumAttempts) {
            attempts++;
            return Timeout.RETRY;
        }
        stage = Stage.FAILED;
        return Timeout.FAILED;
    }

    void reset() {
        stage = Stage.IDLE;
        attempts = 0;
    }

    boolean isReady() {
        return stage == Stage.READY;
    }

    Stage getStage() {
        return stage;
    }

    private void advance(final Stage nextStage) {
        stage = nextStage;
        attempts = 1;
    }
}
