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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class YcbtPairingTrace {
    private static final String HEADER = "YCBT pairing diagnostics";

    private final List<String> lines;
    private int attemptNumber;
    private int eventNumber;

    public YcbtPairingTrace() {
        this(0, 0, Collections.emptyList());
    }

    public YcbtPairingTrace(final int attemptNumber,
                            final int eventNumber,
                            final List<String> lines) {
        this.attemptNumber = attemptNumber;
        this.eventNumber = eventNumber;
        this.lines = new ArrayList<>(lines);
    }

    public void beginAttempt() {
        attemptNumber++;
        eventNumber = 0;
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add("Attempt " + attemptNumber);
    }

    public void append(final String event) {
        append(event, new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()));
    }

    void append(final String event, final String timestamp) {
        if (attemptNumber == 0) {
            beginAttempt();
        }
        eventNumber++;
        lines.add(String.format(Locale.ROOT, "%02d. %s %s", eventNumber, timestamp, event));
    }

    public String format() {
        if (lines.isEmpty()) {
            return HEADER;
        }
        return HEADER + "\n\n" + String.join("\n", lines);
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public int getEventNumber() {
        return eventNumber;
    }

    public ArrayList<String> getLines() {
        return new ArrayList<>(lines);
    }
}
