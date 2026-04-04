package nodomain.freeyourgadget.gadgetbridge.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import nodomain.freeyourgadget.gadgetbridge.activities.charts.EcgChartFragment;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.EcgPeriodFragment;

public class EcgFragmentAdapter extends NestedFragmentAdapter {
    public EcgFragmentAdapter(final Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(final int position) {
        return switch (position) {
            case 1 -> EcgPeriodFragment.newInstance(7);
            case 2 -> EcgPeriodFragment.newInstance(30);
            default -> new EcgChartFragment();
        };
    }
}
