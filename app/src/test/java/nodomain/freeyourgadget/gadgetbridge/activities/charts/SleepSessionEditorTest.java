package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepStage;

public class SleepSessionEditorTest {
    @Test
    public void editingStageEndMovesNextStageStart() {
        final List<SleepStage> stages = stages();

        SleepSessionEditor.updateStageBoundary(stages, 0, SleepSessionEditor.StageBoundary.END, 900);

        assertEquals(0, stages.get(0).getStartTs());
        assertEquals(900, stages.get(0).getEndTs());
        assertEquals(900, stages.get(1).getStartTs());
        assertEquals(1_200, stages.get(1).getEndTs());
    }

    @Test
    public void editingStageStartMovesPreviousStageEnd() {
        final List<SleepStage> stages = stages();

        SleepSessionEditor.updateStageBoundary(stages, 1, SleepSessionEditor.StageBoundary.START, 480);

        assertEquals(0, stages.get(0).getStartTs());
        assertEquals(480, stages.get(0).getEndTs());
        assertEquals(480, stages.get(1).getStartTs());
        assertEquals(1_200, stages.get(1).getEndTs());
    }

    @Test
    public void editingFirstStageStartMovesSessionBoundaryOnly() {
        final List<SleepStage> stages = stages();

        SleepSessionEditor.updateStageBoundary(stages, 0, SleepSessionEditor.StageBoundary.START, -300);

        assertEquals(-300, stages.get(0).getStartTs());
        assertEquals(600, stages.get(0).getEndTs());
        assertEquals(600, stages.get(1).getStartTs());
    }

    @Test
    public void rejectsBoundaryThatWouldMakeNeighbourTooShort() {
        final List<SleepStage> stages = stages();

        try {
            SleepSessionEditor.updateStageBoundary(stages, 0, SleepSessionEditor.StageBoundary.END, 1_170);
            fail("Expected short neighbour to be rejected");
        } catch (IllegalArgumentException ignored) {
        }

        assertEquals(600, stages.get(0).getEndTs());
        assertEquals(600, stages.get(1).getStartTs());
    }

    @Test
    public void deletingMiddleStageExtendsPreviousStage() {
        final List<SleepStage> stages = threeStages();

        SleepSessionEditor.deleteStage(stages, 1);

        assertEquals(2, stages.size());
        assertEquals(0, stages.get(0).getStartTs());
        assertEquals(1_200, stages.get(0).getEndTs());
        assertEquals(ActivityKind.LIGHT_SLEEP, stages.get(0).getKind());
        assertEquals(1_200, stages.get(1).getStartTs());
        assertEquals(1_800, stages.get(1).getEndTs());
    }

    @Test
    public void deletingFirstStageExtendsNextStage() {
        final List<SleepStage> stages = threeStages();

        SleepSessionEditor.deleteStage(stages, 0);

        assertEquals(2, stages.size());
        assertEquals(0, stages.get(0).getStartTs());
        assertEquals(1_200, stages.get(0).getEndTs());
        assertEquals(ActivityKind.DEEP_SLEEP, stages.get(0).getKind());
        assertEquals(1_200, stages.get(1).getStartTs());
        assertEquals(1_800, stages.get(1).getEndTs());
    }

    @Test
    public void rejectsDeletingOnlyStage() {
        final List<SleepStage> stages = new ArrayList<>();
        stages.add(new SleepStage(0, 600, ActivityKind.LIGHT_SLEEP));

        try {
            SleepSessionEditor.deleteStage(stages, 0);
            fail("Expected the last stage delete to be rejected");
        } catch (IllegalArgumentException ignored) {
        }

        assertEquals(1, stages.size());
        assertEquals(0, stages.get(0).getStartTs());
        assertEquals(600, stages.get(0).getEndTs());
    }

    private static List<SleepStage> stages() {
        final List<SleepStage> stages = new ArrayList<>();
        stages.add(new SleepStage(0, 600, ActivityKind.LIGHT_SLEEP));
        stages.add(new SleepStage(600, 1_200, ActivityKind.DEEP_SLEEP));
        return stages;
    }

    private static List<SleepStage> threeStages() {
        final List<SleepStage> stages = stages();
        stages.add(new SleepStage(1_200, 1_800, ActivityKind.REM_SLEEP));
        return stages;
    }
}
