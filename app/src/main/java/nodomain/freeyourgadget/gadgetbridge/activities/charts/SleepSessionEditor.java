/*  Copyright (C) 2026 Freeyourgadget

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.app.AlertDialog;
import android.os.AsyncTask;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBAccess;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.CorrectedSleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepCorrectionRequest;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepStage;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.sleep.SleepCorrectionWriter;

class SleepSessionEditor {
    private final Fragment fragment;
    private final GBDevice device;
    private final boolean remSleepAvailable;
    private final Runnable onSessionChanged;
    private static final long QUICK_ADJUST_SECONDS = 5 * 60L;
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    enum StageBoundary {
        START,
        END
    }

    SleepSessionEditor(@NonNull final Fragment fragment,
                       @NonNull final GBDevice device,
                       final boolean remSleepAvailable,
                       @NonNull final Runnable onSessionChanged) {
        this.fragment = fragment;
        this.device = device;
        this.remSleepAvailable = remSleepAvailable;
        this.onSessionChanged = onSessionChanged;
    }

    void show(final CorrectedSleepSession session) {
        final CorrectedSleepSession original = new CorrectedSleepSession(session);
        final List<ActivityKind> availableKinds = availableStageKinds(session);
        final BottomSheetDialog dialog = new BottomSheetDialog(fragment.requireContext());
        final LinearLayout editor = new LinearLayout(fragment.requireContext());
        editor.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(16);
        editor.setPadding(padding, padding, padding, padding);

        final NestedScrollView scrollView = new NestedScrollView(fragment.requireContext());
        scrollView.setFillViewport(false);
        final LinearLayout content = new LinearLayout(fragment.requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        editor.addView(scrollView, weightedMatchLayoutParams(1));

        final TextView title = new TextView(fragment.requireContext());
        title.setText(R.string.sleep_edit_title);
        title.setTextSize(20);
        title.setTextColor(GBApplication.getTextColor(fragment.requireContext()));
        content.addView(title, matchWrapLayoutParams());

        final TextView stagesTitle = new TextView(fragment.requireContext());
        stagesTitle.setText(R.string.sleep_edit_stage);
        stagesTitle.setTextColor(GBApplication.getTextColor(fragment.requireContext()));
        stagesTitle.setPadding(0, dp(12), 0, 0);
        content.addView(stagesTitle, matchWrapLayoutParams());

        final LinearLayout stagesContainer = new LinearLayout(fragment.requireContext());
        stagesContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(stagesContainer, matchWrapLayoutParams());

        final List<SleepStage> draftStages = new ArrayList<>();
        final List<StageEditRow> rows = new ArrayList<>();
        resetDraftStages(draftStages, original);
        rebuildStageRows(stagesContainer, draftStages, rows, availableKinds);

        final LinearLayout actionRow = new LinearLayout(fragment.requireContext());
        actionRow.setGravity(Gravity.END);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(12), 0, 0);

        final Button resetButton = new Button(fragment.requireContext());
        resetButton.setText(R.string.sleep_edit_reset);
        resetButton.setEnabled(session.isEdited() && session.getId() != null);
        resetButton.setOnClickListener(v -> resetEditedSession(dialog, session));
        actionRow.addView(resetButton, wrapLayoutParams());

        final Button undoButton = new Button(fragment.requireContext());
        undoButton.setText(R.string.sleep_edit_undo);
        undoButton.setOnClickListener(v -> {
            resetDraftStages(draftStages, original);
            rebuildStageRows(stagesContainer, draftStages, rows, availableKinds);
        });
        actionRow.addView(undoButton, wrapLayoutParams());

        final Button cancelButton = new Button(fragment.requireContext());
        cancelButton.setText(R.string.sleep_edit_cancel);
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        actionRow.addView(cancelButton, wrapLayoutParams());

        final Button saveButton = new Button(fragment.requireContext());
        saveButton.setText(R.string.sleep_edit_save);
        saveButton.setOnClickListener(v -> saveEditedSession(dialog, session, draftStages, rows, availableKinds));
        actionRow.addView(saveButton, wrapLayoutParams());

        editor.addView(actionRow, matchWrapLayoutParams());
        dialog.setContentView(editor);
        dialog.setOnShowListener(d -> expandSleepEditor(dialog));
        dialog.show();
    }

    private void expandSleepEditor(final BottomSheetDialog dialog) {
        final FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet == null) {
            return;
        }

        final ViewGroup.LayoutParams layoutParams = bottomSheet.getLayoutParams();
        layoutParams.height = (int) (fragment.getResources().getDisplayMetrics().heightPixels * 0.9f);
        bottomSheet.setLayoutParams(layoutParams);

        final BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private EditText createTimeField(final long timestamp) {
        final EditText field = new EditText(fragment.requireContext());
        field.setHint(formatEditTimestamp(timestamp));
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setInputType(InputType.TYPE_CLASS_DATETIME);
        field.setText(formatEditTimestamp(timestamp));
        return field;
    }

    private void resetDraftStages(final List<SleepStage> draftStages, final CorrectedSleepSession session) {
        draftStages.clear();
        for (SleepStage stage : session.getStages()) {
            draftStages.add(new SleepStage(stage));
        }
    }

    private void rebuildStageRows(final LinearLayout stagesContainer,
                                  final List<SleepStage> draftStages,
                                  final List<StageEditRow> rows,
                                  final List<ActivityKind> availableKinds) {
        stagesContainer.removeAllViews();
        rows.clear();
        final List<String> labels = new ArrayList<>(availableKinds.size());
        for (ActivityKind kind : availableKinds) {
            labels.add(stageLabel(kind));
        }

        for (int i = 0; i < draftStages.size(); i++) {
            final SleepStage stage = draftStages.get(i);
            final StageEditRow row = new StageEditRow();
            rows.add(row);
            final LinearLayout rowLayout = new LinearLayout(fragment.requireContext());
            rowLayout.setOrientation(LinearLayout.VERTICAL);
            rowLayout.setPadding(0, dp(4), 0, dp(4));

            final LinearLayout timeRow = new LinearLayout(fragment.requireContext());
            timeRow.setOrientation(LinearLayout.HORIZONTAL);
            timeRow.setGravity(Gravity.CENTER_VERTICAL);

            final Button startButton = createStageTimeButton(stage.getStartTs(), R.string.sleep_edit_start);
            final int rowIndex = i;
            startButton.setOnClickListener(v -> showStageBoundaryEditor(stagesContainer, draftStages, rows, availableKinds, rowIndex, StageBoundary.START));
            timeRow.addView(startButton, weightedWrapLayoutParams(1));

            final TextView separator = new TextView(fragment.requireContext());
            separator.setText(" - ");
            separator.setGravity(Gravity.CENTER);
            separator.setTextColor(GBApplication.getTextColor(fragment.requireContext()));
            timeRow.addView(separator, wrapLayoutParams());

            final Button endButton = createStageTimeButton(stage.getEndTs(), R.string.sleep_edit_end);
            endButton.setOnClickListener(v -> showStageBoundaryEditor(stagesContainer, draftStages, rows, availableKinds, rowIndex, StageBoundary.END));
            timeRow.addView(endButton, weightedWrapLayoutParams(1));

            rowLayout.addView(timeRow, matchWrapLayoutParams());

            final LinearLayout controlsRow = new LinearLayout(fragment.requireContext());
            controlsRow.setOrientation(LinearLayout.HORIZONTAL);
            controlsRow.setGravity(Gravity.CENTER_VERTICAL);

            final Spinner spinner = new Spinner(fragment.requireContext());
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(fragment.requireContext(), android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            final int selected = Math.max(0, availableKinds.indexOf(stage.getKind()));
            spinner.setSelection(selected);
            row.spinner = spinner;
            controlsRow.addView(spinner, weightedWrapLayoutParams(1));

            final Button splitButton = new Button(fragment.requireContext());
            splitButton.setText(R.string.sleep_edit_split);
            splitButton.setEnabled(stage.getDurationSeconds() >= SleepSessionService.MIN_STAGE_SECONDS * 2L);
            splitButton.setOnClickListener(v -> {
                syncStageKinds(draftStages, rows, availableKinds);
                final SleepStage stageToSplit = draftStages.get(rowIndex);
                long splitTs = ((stageToSplit.getStartTs() + stageToSplit.getEndTs()) / 2L / 60L) * 60L;
                if (splitTs <= stageToSplit.getStartTs()) {
                    splitTs = stageToSplit.getStartTs() + SleepSessionService.MIN_STAGE_SECONDS;
                }
                if (splitTs >= stageToSplit.getEndTs()) {
                    splitTs = stageToSplit.getEndTs() - SleepSessionService.MIN_STAGE_SECONDS;
                }
                if (splitTs > stageToSplit.getStartTs() && splitTs < stageToSplit.getEndTs()) {
                    draftStages.add(rowIndex + 1, new SleepStage(splitTs, stageToSplit.getEndTs(), stageToSplit.getKind()));
                    stageToSplit.setEndTs(splitTs);
                    rebuildStageRows(stagesContainer, draftStages, rows, availableKinds);
                }
            });
            controlsRow.addView(splitButton, wrapLayoutParams());

            final Button deleteButton = new Button(fragment.requireContext());
            deleteButton.setText(R.string.delete);
            deleteButton.setEnabled(draftStages.size() > 1);
            deleteButton.setOnClickListener(v -> {
                syncStageKinds(draftStages, rows, availableKinds);
                deleteStage(draftStages, rowIndex);
                rebuildStageRows(stagesContainer, draftStages, rows, availableKinds);
            });
            controlsRow.addView(deleteButton, wrapLayoutParams());

            rowLayout.addView(controlsRow, matchWrapLayoutParams());

            stagesContainer.addView(rowLayout, matchWrapLayoutParams());
        }
    }

    private Button createStageTimeButton(final long timestamp, final int labelResId) {
        final Button button = new Button(fragment.requireContext());
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setText(DateTimeUtils.timeToString(new Date(timestamp * 1000L)));
        button.setContentDescription(fragment.getString(labelResId) + " " + formatEditTimestamp(timestamp));
        return button;
    }

    private void showStageBoundaryEditor(final LinearLayout stagesContainer,
                                         final List<SleepStage> draftStages,
                                         final List<StageEditRow> rows,
                                         final List<ActivityKind> availableKinds,
                                         final int rowIndex,
                                         final StageBoundary boundary) {
        syncStageKinds(draftStages, rows, availableKinds);
        final long currentTimestamp = boundary == StageBoundary.START
                ? draftStages.get(rowIndex).getStartTs()
                : draftStages.get(rowIndex).getEndTs();
        final EditText timeField = createTimeField(currentTimestamp);
        new AlertDialog.Builder(fragment.requireContext())
                .setTitle(boundary == StageBoundary.START ? R.string.sleep_edit_start : R.string.sleep_edit_end)
                .setView(createTimeAdjustmentView(timeField))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        updateStageBoundary(draftStages, rowIndex, boundary, parseEditTimestamp(timeField));
                        rebuildStageRows(stagesContainer, draftStages, rows, availableKinds);
                    } catch (ParseException e) {
                        GB.toast(fragment.requireContext(), fragment.getString(R.string.sleep_edit_invalid_time), Toast.LENGTH_LONG, GB.ERROR, e);
                    } catch (IllegalArgumentException e) {
                        GB.toast(fragment.requireContext(), e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR, e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private LinearLayout createTimeAdjustmentView(final EditText timeField) {
        final LinearLayout layout = new LinearLayout(fragment.requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(16);
        layout.setPadding(padding, 0, padding, 0);
        layout.addView(timeField, matchWrapLayoutParams());

        final LinearLayout adjustmentRow = new LinearLayout(fragment.requireContext());
        adjustmentRow.setGravity(Gravity.END);
        adjustmentRow.setOrientation(LinearLayout.HORIZONTAL);

        final Button subtractButton = new Button(fragment.requireContext());
        subtractButton.setText(R.string.sleep_edit_minus_5_minutes);
        subtractButton.setOnClickListener(v -> adjustTimeField(timeField, -QUICK_ADJUST_SECONDS));
        adjustmentRow.addView(subtractButton, wrapLayoutParams());

        final Button addButton = new Button(fragment.requireContext());
        addButton.setText(R.string.sleep_edit_plus_5_minutes);
        addButton.setOnClickListener(v -> adjustTimeField(timeField, QUICK_ADJUST_SECONDS));
        adjustmentRow.addView(addButton, wrapLayoutParams());

        layout.addView(adjustmentRow, matchWrapLayoutParams());
        return layout;
    }

    private void adjustTimeField(final EditText timeField, final long offsetSeconds) {
        try {
            timeField.setText(formatEditTimestamp(parseEditTimestamp(timeField) + offsetSeconds));
            timeField.selectAll();
        } catch (ParseException e) {
            GB.toast(fragment.requireContext(), fragment.getString(R.string.sleep_edit_invalid_time), Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    private void syncStageKinds(final List<SleepStage> draftStages,
                                final List<StageEditRow> rows,
                                final List<ActivityKind> availableKinds) {
        for (int i = 0; i < rows.size() && i < draftStages.size(); i++) {
            final StageEditRow row = rows.get(i);
            if (row.spinner == null) {
                continue;
            }
            final int position = row.spinner.getSelectedItemPosition();
            if (position >= 0 && position < availableKinds.size()) {
                draftStages.get(i).setKind(availableKinds.get(position));
            }
        }
    }

    private void saveEditedSession(final BottomSheetDialog dialog,
                                   final CorrectedSleepSession session,
                                   final List<SleepStage> draftStages,
                                   final List<StageEditRow> rows,
                                   final List<ActivityKind> availableKinds) {
        syncStageKinds(draftStages, rows, availableKinds);
        final List<SleepStage> editedStages = new ArrayList<>();
        for (SleepStage stage : draftStages) {
            editedStages.add(new SleepStage(stage));
        }

        final SleepCorrectionRequest request = SleepCorrectionRequest.fromSession(
                session,
                getEditedStartTs(session, draftStages),
                getEditedEndTs(session, draftStages),
                editedStages
        );

        try {
            SleepSessionService.normalizeForSave(request);
        } catch (IllegalArgumentException e) {
            GB.toast(fragment.requireContext(), e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR, e);
            return;
        }

        new DBAccessTask(fragment.getString(R.string.sleep_edit_save)) {
            @Override
            protected void doInBackground(final DBHandler handler) {
                SleepCorrectionWriter.saveCorrection(handler.getDaoSession(), device, request);
            }

            @Override
            protected void onSuccess() {
                dialog.dismiss();
                onSessionChanged.run();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private long getEditedStartTs(final CorrectedSleepSession session, final List<SleepStage> draftStages) {
        if (draftStages.isEmpty()) {
            return session.getStartTs();
        }
        return draftStages.get(0).getStartTs();
    }

    private long getEditedEndTs(final CorrectedSleepSession session, final List<SleepStage> draftStages) {
        if (draftStages.isEmpty()) {
            return session.getEndTs();
        }
        return draftStages.get(draftStages.size() - 1).getEndTs();
    }

    private void resetEditedSession(final BottomSheetDialog dialog, final CorrectedSleepSession session) {
        new DBAccessTask(fragment.getString(R.string.sleep_edit_reset)) {
            @Override
            protected void doInBackground(final DBHandler handler) {
                SleepCorrectionWriter.deleteCorrection(handler.getDaoSession(), session.getId());
            }

            @Override
            protected void onSuccess() {
                dialog.dismiss();
                onSessionChanged.run();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private List<ActivityKind> availableStageKinds(final CorrectedSleepSession session) {
        final List<ActivityKind> kinds = new ArrayList<>();
        kinds.add(ActivityKind.AWAKE_SLEEP);
        kinds.add(ActivityKind.LIGHT_SLEEP);
        kinds.add(ActivityKind.DEEP_SLEEP);
        if (remSleepAvailable || session.hasStage(ActivityKind.REM_SLEEP)) {
            kinds.add(ActivityKind.REM_SLEEP);
        }
        return kinds;
    }

    private String stageLabel(final ActivityKind kind) {
        if (kind == ActivityKind.AWAKE_SLEEP) {
            return fragment.getString(R.string.abstract_chart_fragment_kind_awake_sleep);
        }
        if (kind == ActivityKind.DEEP_SLEEP) {
            return fragment.getString(R.string.abstract_chart_fragment_kind_deep_sleep);
        }
        if (kind == ActivityKind.REM_SLEEP) {
            return fragment.getString(R.string.abstract_chart_fragment_kind_rem_sleep);
        }
        return fragment.getString(R.string.abstract_chart_fragment_kind_light_sleep);
    }

    private String formatEditTimestamp(final long timestamp) {
        return timestampFormat.format(new Date(timestamp * 1000L));
    }

    private long parseEditTimestamp(final EditText field) throws ParseException {
        final Date parsed = timestampFormat.parse(field.getText().toString().trim());
        if (parsed == null) {
            throw new ParseException(field.getText().toString(), 0);
        }
        return (parsed.getTime() / 1000L / 60L) * 60L;
    }

    static void updateStageBoundary(@NonNull final List<SleepStage> stages,
                                    final int rowIndex,
                                    @NonNull final StageBoundary boundary,
                                    final long timestamp) {
        if (rowIndex < 0 || rowIndex >= stages.size()) {
            throw new IllegalArgumentException("Invalid sleep stage");
        }

        if (boundary == StageBoundary.START) {
            validateStageStartEdit(stages, rowIndex, timestamp);
            if (rowIndex > 0) {
                stages.get(rowIndex - 1).setEndTs(timestamp);
            }
            stages.get(rowIndex).setStartTs(timestamp);
        } else {
            validateStageEndEdit(stages, rowIndex, timestamp);
            stages.get(rowIndex).setEndTs(timestamp);
            if (rowIndex < stages.size() - 1) {
                stages.get(rowIndex + 1).setStartTs(timestamp);
            }
        }
    }

    private static void validateStageStartEdit(final List<SleepStage> stages,
                                               final int rowIndex,
                                               final long timestamp) {
        final SleepStage current = stages.get(rowIndex);
        if (current.getEndTs() - timestamp < SleepSessionService.MIN_STAGE_SECONDS) {
            throw new IllegalArgumentException("Sleep stages must be at least one minute");
        }
        if (rowIndex > 0 && timestamp - stages.get(rowIndex - 1).getStartTs() < SleepSessionService.MIN_STAGE_SECONDS) {
            throw new IllegalArgumentException("Sleep stages must be at least one minute");
        }
    }

    private static void validateStageEndEdit(final List<SleepStage> stages,
                                             final int rowIndex,
                                             final long timestamp) {
        final SleepStage current = stages.get(rowIndex);
        if (timestamp - current.getStartTs() < SleepSessionService.MIN_STAGE_SECONDS) {
            throw new IllegalArgumentException("Sleep stages must be at least one minute");
        }
        if (rowIndex < stages.size() - 1 && stages.get(rowIndex + 1).getEndTs() - timestamp < SleepSessionService.MIN_STAGE_SECONDS) {
            throw new IllegalArgumentException("Sleep stages must be at least one minute");
        }
    }

    static void deleteStage(@NonNull final List<SleepStage> stages,
                            final int rowIndex) {
        if (rowIndex < 0 || rowIndex >= stages.size()) {
            throw new IllegalArgumentException("Invalid sleep stage");
        }
        if (stages.size() <= 1) {
            throw new IllegalArgumentException("At least one sleep stage is required");
        }

        final SleepStage removed = stages.get(rowIndex);
        if (rowIndex > 0) {
            stages.get(rowIndex - 1).setEndTs(removed.getEndTs());
        } else {
            stages.get(1).setStartTs(removed.getStartTs());
        }
        stages.remove(rowIndex);
    }

    private int dp(final int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                fragment.getResources().getDisplayMetrics()
        );
    }

    private LinearLayout.LayoutParams matchWrapLayoutParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapLayoutParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedWrapLayoutParams(final int weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams weightedMatchLayoutParams(final int weight) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private static class StageEditRow {
        private Spinner spinner;
    }

    private abstract class DBAccessTask extends DBAccess {
        private DBAccessTask(final String task) {
            super(task, fragment.requireContext(), true);
        }

        @Override
        protected void onPostExecute(final Object o) {
            super.onPostExecute(o);
            if (getTaskError() == null) {
                onSuccess();
            }
        }

        protected abstract void onSuccess();
    }
}
