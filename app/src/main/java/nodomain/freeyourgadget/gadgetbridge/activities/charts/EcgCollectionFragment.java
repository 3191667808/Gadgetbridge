package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.os.Bundle;

import androidx.viewpager2.adapter.FragmentStateAdapter;

import nodomain.freeyourgadget.gadgetbridge.adapter.EcgFragmentAdapter;

public class EcgCollectionFragment extends AbstractCollectionFragment {
    public EcgCollectionFragment() {
    }

    public static EcgCollectionFragment newInstance(final boolean allowSwipe) {
        final EcgCollectionFragment fragment = new EcgCollectionFragment();
        final Bundle args = new Bundle();
        args.putBoolean(ARG_ALLOW_SWIPE, allowSwipe);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public FragmentStateAdapter getFragmentAdapter() {
        return new EcgFragmentAdapter(this);
    }
}
