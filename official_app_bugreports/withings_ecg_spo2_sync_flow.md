# Withings Official-App ECG and SpO2 Sync Flow

This note captures the current packet-level understanding of how the official Withings app syncs offline ECG and SpO2 records, based on the bugreport/HCI analysis done so far.

The goal is to preserve the current model of the protocol and the most likely explanation for Gadgetbridge's stuck mixed-page sync bug.

## Main source capture

- Bugreport: `bugreport-akita-BP4A.260205.001-2026-04-05-20-54-02.zip`
- Primary transport source: `FS/data/misc/bluetooth/logs/btsnoop_hci.log`
- Relevant BLE peer: watch `db:89:d6:39:d3:d6`
- Main protocol characteristic handle: `0x0013`
- CCC handle for notifications: `0x0014`

## User-recorded offline measurement sequence for this capture

Before the bugreport capture, the user reported the following sequence:

- Synced the watch with the official Withings app
- Cleared logcat with `adb logcat -c`
- Turned Bluetooth off on the phone
- Took the following measurements offline on the watch

### SpO2

- `20:41` inconclusive manual SpO2 `91%`
- `20:42` inconclusive manual SpO2 `98%`
- `20:42` normal manual SpO2 `99%`
- `20:44` inconclusive manual SpO2 `100%`
- `20:45` inconclusive manual SpO2 `97%`
- `20:46` inconclusive manual SpO2 `100%`
- `20:46` normal manual SpO2 `97%`

### ECG

- `20:43` normal sinus rhythm `72 bpm`
- `20:46` normal sinus rhythm `70 bpm`

After those offline measurements, the bugreport/HCI capture was taken and later review activity in the official app was visible around:

- `20:47:55` ECG activity opened
- `20:48:02` SpO2 activity opened
- `20:48:07` SpO2 list opened
- `20:50:43` ECG reopened

## High-confidence protocol facts

### Message transport

- The official app uses the same custom Withings protocol channel Gadgetbridge already targets.
- Main writes and notifications happen on ATT handle `0x0013`.
- Notifications are enabled on handle `0x0014` before the application protocol exchange starts.

### Relevant command/TLV families seen

- `0x0147` - `GET_STORED_MEASURE_SIGNAL`
- `0x0148` - `DELETE_STORED_MEASURE_SIGNAL`
- `0x0974` - `MEASURE_STOP` payload seen in ECG discovery/summary phase
- `0x0116` - `StoredMeasureMeta`
- `0x0117` - `StoredMeasureData`
- `0x0143` - `StoredSignalMeta`
- `0x0144` - `StoredSignalData`
- `0x0145` - `FeatureTagsUserId`
- `0x0146` - `StoredSignalMetaExtended`
- additional unknown-but-relevant TLVs seen near ECG/spO2 data: `0x0149`, `0x014a`, `0x09b9`

## Official ECG sync flow

### 1. Discovery/summary phase

The official app first receives an ECG summary/discovery payload before requesting the full waveform.

Observed sequence:

- frame `775`: app sends `0x0142` with empty payload
- frame `780`: watch replies on `0x0142` with TLV `0x0974`
- frames `771-772`: incoming summary payload includes:
  - `0x097b 0002 0001`
  - `0x097e 0004 00000000`
  - ECG record key `0x0116`
  - summary `0x0117` values

Important observed ECG key:

- `0116001700000001010301f5302200000000000000000069d2ae1c`

Important summary fields seen together with it:

- `0x0117 type 0x0082 raw 0`
- `0x0117 type 0x000b raw 0x46` -> `70 bpm`
- more `0x0117` values with types `0x0089`, `0x008a`, `0x0088`, `0x0087`

### 2. Full ECG fetch by replaying the exact `0x0116`

The official app then fetches the full ECG by sending that exact `0x0116` payload back inside `0x0147`.

Observed request:

- frame `781`
- `010147001b0116001700000001010301f5302200000000000000000069d2ae1c`

This is a key result: the app treats the raw `0x0116` payload as the ECG record identity/key.

### 3. Full ECG response contents

Observed across frames `782-950`:

- same `0x0116` record key
- ECG summary `0x0117`
- delete key `0x0143`
- extra signal metadata `0x0146`
- many `0x0144` waveform chunks
- EOT at frame `950`

Important beginning of the reply:

- `01014700d00116001700000001010301f5302200000000000000000069d2ae1c011700080000000000820000014900080000008201020401014a000400000b7d09b90006000100000000014300080001012c03020e010146000c0000001e00004650000000000147000cffffe0000000`

Decoded key structures inside that reply:

- `0x0116` - ECG meta/key
- `0x0117` - summary values
- `0x0143(signalType=0x0001, flags=0x012c, cursor=0x03020e01)`
- `0x0146(0000001e0000465000000000)`

### 4. Official ECG delete and delete verification

The official app explicitly deletes the fetched ECG waveform using the returned `0x0143`, then verifies the deletion by re-fetching the same `0x0116` key.

Observed delete request:

- frame `953`
- `01014800140145000400000001014300080001012c03020e01`

Observed delete response:

- frame `954`
- `010148000401000000`

Observed verification:

- frame `955`: app re-fetches the same `0x0116` record key
- frame `957`: watch returns empty `0x0147` EOT

This confirms:

1. ECG delete uses `0x0148 + 0x0145 + 0x0143`
2. the app does not trust delete blindly; it verifies removal by fetching the same ECG key again

## Official stored-signal polling behavior

The official app also polls stored signal queues directly by signal type using `0x0147 + 0x0143(signalType, flags=0, cursor=0)`.

Observed requests:

- frame `1099`: signalType `0x0001`
  - `010147000c014300080001000000000000`
- frame `1289`: signalType `0x0004`
  - `010147000c014300080004000000000000`
- frame `1381`: signalType `0x0005`
  - `010147000c014300080005000000000000`

Important observation: in this capture the official app requests these queues with cursor `0`, not with `returned_cursor + 1`.

## Mixed SpO2 + ECG pages are real

The official app definitely receives mixed pages on signalType `0x0004`.

Observed mixed reply: frames `1292-1295`, returned in response to `signalType=0x0004, cursor=0`.

That one page contains:

- `0x0116` with `measurementType=0x0103` (ECG marker/meta)
- `0x0117` with `measurementType=0x0036`, exponent `-1`, raw `0x390` -> SpO2-like value `91.2`
- `0x0143(signalType=0x0004, flags=0x0019, cursor=0x00041301)`
- `0x0146`
- another unknown `0x0117 type 0x0059`
- tiny `0x0144 0002 0100`
- EOT

This is the most important finding for the stuck sync bug: a SpO2 page can contain both SpO2 data and an ECG marker, plus its own delete key.

## How the official app handles mixed pages

After receiving the mixed `signalType=0x0004` page, the official app repeatedly deletes that exact returned page key and keeps re-reading `cursor=0` until the page disappears.

Observed delete frames for the same mixed-page key:

- `1298`
- `1311`
- `1323`
- `1335`
- `1347`
- `1364`
- `1376`

Those delete packets keep the same `0x0143` payload and only change the `0x0145` request id.

The returned mixed-page delete key is:

- `0x0143(signalType=0x0004, flags=0x0019, cursor=0x00041301)`

After each delete attempt, the official app requests signalType `0x0004` again with cursor `0`.

Only after several retries does the page finally disappear:

- frame `1380`: signalType `0x0004` finally returns empty
- frame `1381`: app proceeds to signalType `0x0005`

This strongly suggests the official app treats the returned cursor as an opaque delete key for the current head page, not as a sequential pagination cursor.

## Confirmed Gadgetbridge-side facts

Current Gadgetbridge implementation already matches several important parts of the official protocol:

- `WithingsBaseDeviceSupport.doSync()` polls signal types `0x0001`, `0x0004`, `0x0005`
- `StoredMeasureMeta` preserves the raw `0x0116` payload, which matches the official fetch-by-key behavior
- `StoredMeasureData` already recognizes SpO2 measurement type `54 (0x0036)` and exponent scaling
- `WithingsEcgHandler` already uses:
  - `ECG_MEASUREMENT_TYPE = 0x0103`
  - `ECG_AVERAGE_HR_TYPE = 0x000b`
  - `ECG_RESULT_TYPE = 0x0082`
  - `ECG_WAVEFORM_SIGNAL_TYPE = 0x0001`
- `queueDeleteStoredMeasureSignal()` already builds the same wire shape as the official app:
  - `0x0148 + 0x0145 + 0x0143`

So the current evidence does not point to a malformed delete packet.

## Best current model of official app behavior

The strongest current model is:

1. Official app discovers ECG summaries and receives `0x0116` keys.
2. It fetches each full ECG by replaying the exact `0x0116` in `0x0147`.
3. It deletes fetched ECG waveform records using the returned ECG `0x0143` key.
4. It verifies ECG deletion by re-fetching the same `0x0116`.
5. It also polls stored queues by signal type using `cursor=0`.
6. Some `signalType=0x0004` pages are mixed SpO2 + ECG-marker pages.
7. Deleting the ECG waveform record alone is not enough to remove the mixed `0x0004` page.
8. The mixed page itself must be deleted using its own returned `0x0143`.
9. The official app keeps deleting the mixed head page and re-reading `cursor=0` until it is gone.
10. Only then does it move on to the next stored queue.

## Best current explanation for Gadgetbridge getting stuck

The most plausible explanation is that Gadgetbridge still differs from official behavior in two important ways:

1. It treats non-ECG stored-signal cursors as incrementable (`currentCursor + 1`) instead of re-reading the head page with `cursor=0`.
2. It gives up too early on repeated mixed-page deletes; the official app needed about 7 delete attempts in this capture.

Why this likely matters:

- the mixed-page cursor `0x00041301` does not look sequential
- the official app uses it for delete, not forward pagination
- the watch appears to keep returning the same mixed head page until repeated deletes finally clear it

So the stuck sync bug is best explained as:

- ECG waveform cleanup alone does not remove the mixed SpO2 page
- the mixed page must be deleted using its own `0x0143`
- the official app keeps requesting `signalType=0x0004, cursor=0` and repeatedly deleting the returned head page until it disappears
- Gadgetbridge currently appears too eager to advance or give up

## Important remaining unknowns

Still not fully decoded:

- meaning of outbound command `0x0142`
- exact semantics of `0x0145`
- meaning of `0x0146`
- meaning of `0x0149`, `0x014a`, `0x09b9`
- meaning of `0x0117` types `0x0087`, `0x0088`, `0x0089`, `0x008a`, `0x0059`
- meaning of signalType `0x0005`

## Best next experiments

- Confirm across more official captures that signalType `0x0004` and `0x0005` are always re-read from `cursor=0`
- Measure how many deletes are typically required before a mixed page disappears
- Compare behavior after deleting only ECG waveform (`signalType=0x0001`) vs deleting the mixed SpO2 page (`signalType=0x0004`)
- Decode `0x0146` and neighboring TLVs across more examples
- Instrument Gadgetbridge further for mixed-page delete/retry behavior and compare against the official app timeline

## Practical implication for Gadgetbridge fixes

The current best direction is not to change ECG delete packet encoding, because that already matches the official app.

The more likely fix area is stored-signal loop behavior for mixed pages:

- treat returned `0x0143` on mixed `signalType=0x0004` pages as the opaque delete key for the head page
- prefer re-reading `cursor=0` rather than advancing `currentCursor + 1`
- allow more repeated delete attempts before giving up


# Assessment

Based on my direct analysis of the extracted btsnoop_hci.log PCAP from the provided zip archive, I have examined the GATT protocol exchanges and can confirm the following conclusions regarding the Withings protocol, syncing behavior, and data structure:
1. Protocol and Syncing Progress (0-100% Feasibility)
*   ECG Progress is determinable: Yes, the official app can accurately calculate a 0 to 100% progress bar for ECGs. At the start of the process (Frames 768-771), there is a Discovery/Summary Phase where the watch explicitly sends summary markers (0x0116 metadata keys) for all stored ECGs before the app requests the full waveforms. This provides the app an upfront count of total ECG records to fetch.
*   SpO2 / General Queues are NOT determinable: For other queues like SpO2 (signalType=0x0004), there is no upfront discovery phase in this capture. The app blindly polls the queue and doesn't know how many items exist until the queue finally returns empty. Hence, an exact 0-100% progress bar for SpO2 isn't strictly possible.
2. How the Pages and Cursors Work
The protocol does not use sequential pagination (e.g., fetching cursor+1, cursor+2). Instead, it treats the cursor as an opaque delete key for the head page. 
Here is the exact loop observed in the HCI capture:
1.  The phone polls the queue by requesting the head page: Fetch(signalType=0x0004, cursor=0).
2.  The watch returns a page containing data and an opaque cursor identifier (e.g., cursor=0x00041301) inside an 0x0143 structure.
3.  The phone processes the page and issues a Delete(cursor=0x00041301).
4.  To get the "next" page, the phone does not increment the cursor. Instead, it re-issues Fetch(signalType=0x0004, cursor=0). 
3. Are ECG and SpO2 Mixed or Ordered?
They absolutely collide and are mixed. 
At Frame 1292, the watch responds to a signalType=0x0004 (SpO2 queue) fetch. The returned packet payload explicitly mixes both data types in the exact same page:
*   An 0x0116 TLV containing an ECG marker (measurementType=0x0103).
*   An 0x0117 TLV containing the SpO2 value (measurementType=0x0036, raw 0x390 equating to ~91.2%).
Conclusion on the App's Loop/Stuck Sync Behavior
Because of these mixed pages, deleting the ECG waveform alone isn't enough to advance the watch's internal SpO2 pointer. The HCI log shows that when the official app encounters this mixed page, it issues a Delete for that specific mixed-page cursor (00041301) and then re-polls cursor=0.
Fascinatingly, the watch doesn't delete it immediately. The official app has to send 7 consecutive delete requests (incrementing a request ID in the 0x0145 TLV from 0 to 6 across frames 1298 to 1376), checking cursor=0 each time, until the watch finally clears the page and returns an empty queue. Gadgetbridge is likely getting stuck here because it expects the cursor to advance sequentially or it doesn't repeatedly hammer the head page with deletes until it drops.

## ECG Metadata & Device Hint Encoding (ScanWatch 2 / newer)

Based on further analysis of the HCI logs:
1. **The ECG Hint is NOT in 0x0117**: While Gadgetbridge previously mapped the ECG Device Hint (Normal vs. AFib, etc.) to the raw value inside the `0x0117` (`StoredMeasureData`) TLV when the measurement type was `0x0082` (130), logs show this raw value is consistently just `0`. This resulted in Gadgetbridge treating all ECGs as "Normal".
2. **The Real Hint is in 0x0149 (Type 329)**: The actual device diagnosis is encoded in an extended TLV payload immediately following the `0x0117` record. This `0x0149` TLV (`StoredMeasureDataExtend`) contains a 4-byte measurement type (`0x00000082` for the ECG hint, or `0x00000036` for SpO2) and an additional 4-byte `extraData` payload.
3. **Normal Sinus Rhythm Code**: In multiple confirmed "Normal Sinus Rhythm" captures, this `extraData` value was `0x01020401` (decimal `16909313`). Gadgetbridge must actively map `16909313` to `0` internally, otherwise the UI interprets any number `> 0` as "Irregular".
