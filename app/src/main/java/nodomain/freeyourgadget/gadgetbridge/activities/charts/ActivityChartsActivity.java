/*  Copyright (C) 2023-2025 Daniel Dakhno, José Rebelo, Martin.JM, Thomas Kuehne

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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractFragmentPagerAdapter;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBFragment;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityAmounts;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.util.LimitedQueue;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class ActivityChartsActivity extends AbstractChartsActivity {
    public static final String ACTIVITY = "activity";
    public static final String ACTIVITYLIST = "activitylist";
    public static final String BODYENERGY = "bodyenergy";
    public static final String CALORIES = "calories";
    public static final String CYCLING = "cycling";
    public static final String HEARTRATE = "heartrate";
    public static final String HRVSTATUS = "hrvstatus";
    public static final String LIVESTATS = "livestats";
    public static final String PAI = "pai";
    public static final String RESPIRATORYRATE = "respiratoryrate";
    public static final String SLEEP = "sleep";
    public static final String SPEEDZONES = "speedzones";
    public static final String SPO2 = "spo2";
    public static final String STEPSWEEK = "stepsweek";
    public static final String STRESS = "stress";
    public static final String TEMPERATURE = "temperature";
    public static final String VO2MAX = "vo2max";
    public static final String WEIGHT = "weight";

    LimitedQueue<Integer, ActivityAmounts> mActivityAmountCache = new LimitedQueue<>(60);

    @Override
    protected AbstractFragmentPagerAdapter createFragmentPagerAdapter(final FragmentManager fragmentManager) {
        return new SectionsPagerAdapter(fragmentManager);
    }

    @Override
    protected int getRecordedDataType() {
        return RecordedDataTypes.TYPE_ACTIVITY | RecordedDataTypes.TYPE_STRESS;
    }

    @Override
    protected boolean supportsRefresh() {
        final DeviceCoordinator coordinator = getDevice().getDeviceCoordinator();
        return coordinator.supportsActivityDataFetching(getDevice());
    }

    @Override
    protected boolean allowRefresh() {
        final DeviceCoordinator coordinator = getDevice().getDeviceCoordinator();
        return coordinator.allowFetchActivityData(getDevice()) && supportsRefresh();
    }

    @Override
    protected List<String> fillChartsTabsList() {
        return fillChartsTabsList(getDevice(), this);
    }

    private static List<String> fillChartsTabsList(final GBDevice device, final Context context) {
        final List<String> tabList;
        final Prefs prefs = new Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()));
        final String myTabs = prefs.getString(DeviceSettingsPreferenceConst.PREFS_DEVICE_CHARTS_TABS, null);

        if (myTabs == null) {
            //make list mutable to be able to remove items later
            tabList = new ArrayList<>(Arrays.asList(context.getResources().getStringArray(R.array.pref_charts_tabs_items_default)));
        } else {
            tabList = new ArrayList<>(Arrays.asList(myTabs.split(",")));
        }
        final DeviceCoordinator coordinator = device.getDeviceCoordinator();
        if (!coordinator.supportsActivityTabs()) {
            tabList.remove(ACTIVITY);
            tabList.remove(ACTIVITYLIST);
        }
        if (!coordinator.supportsSleepMeasurement()) {
            tabList.remove(SLEEP);
        }
        if (!coordinator.supportsStressMeasurement()) {
            tabList.remove(STRESS);
        }
        if (!coordinator.supportsPai()) {
            tabList.remove(PAI);
        }
        if (!coordinator.supportsSpo2(device)) {
            tabList.remove(SPO2);
        }
        if (!coordinator.supportsStepCounter()) {
            tabList.remove(STEPSWEEK);
        }
        if (!coordinator.supportsSpeedzones()) {
            tabList.remove(SPEEDZONES);
        }
        if (!coordinator.supportsRealtimeData()) {
            tabList.remove(LIVESTATS);
        }
        if (!coordinator.supportsTemperatureMeasurement(device)) {
            tabList.remove(TEMPERATURE);
        }
        if (!coordinator.supportsCyclingData()) {
            tabList.remove(CYCLING);
        }
        if (!coordinator.supportsWeightMeasurement()) {
            tabList.remove(WEIGHT);
        }
        if (!coordinator.supportsHrvMeasurement(device)) {
            tabList.remove(HRVSTATUS);
        }
        if (!coordinator.supportsHeartRateMeasurement(device)) {
            tabList.remove(HEARTRATE);
        }
        if (!coordinator.supportsBodyEnergy()) {
            tabList.remove(BODYENERGY);
        }
        if (!coordinator.supportsVO2Max()) {
            tabList.remove(VO2MAX);
        }
        if (!coordinator.supportsActiveCalories()) {
            tabList.remove(CALORIES);
        }
        if (!coordinator.supportsRespiratoryRate()) {
            tabList.remove(RESPIRATORYRATE);
        }
        return tabList;
    }

    public static int getChartsTabIndex(final String tab, final GBDevice device, final Context context) {
        final List<String> enabledTabsList = fillChartsTabsList(device, context);
        return enabledTabsList.indexOf(tab);
    }

    /**
     * A {@link FragmentStatePagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    private class SectionsPagerAdapter extends AbstractFragmentPagerAdapter {
        SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public AbstractGBFragment getItem(int position) {
            final DeviceCoordinator coordinator = getDevice().getDeviceCoordinator();
            // getItem is called to instantiate the fragment for the given page.
            final String fragmentName = enabledTabsList.get(position);

            final Intent intent = getIntent();
            final String mode = intent.getStringExtra(ActivityChartsActivity.EXTRA_MODE);
            final boolean allowSwipe = (enabledTabsList.size() == 1);

            final AbstractGBFragment fragment = coordinator.createDeviceChartsFragment(getDevice(), fragmentName, allowSwipe, mode);
            if(fragment != null){
                return fragment;
            }

            switch (fragmentName) {
                case ACTIVITY:
                    return new ActivitySleepChartFragment();
                case ACTIVITYLIST:
                    return new ActivityListingChartFragment();
                case SLEEP:
                    return SleepCollectionFragment.newInstance(allowSwipe);
                case HEARTRATE:
                    return HeartRateCollectionFragment.newInstance(allowSwipe);
                case HRVSTATUS:
                    return new HRVStatusFragment();
                case BODYENERGY:
                    return new BodyEnergyFragment();
                case VO2MAX:
                    return new VO2MaxFragment();
                case STRESS:
                    return StressCollectionFragment.newInstance(enabledTabsList.size() == 1);
                case PAI:
                    return new PaiChartFragment();
                case STEPSWEEK:
                    return StepsCollectionFragment.newInstance(allowSwipe);
                case SPEEDZONES:
                    return new SpeedZonesFragment();
                case LIVESTATS:
                    return new LiveActivityFragment();
                case SPO2:
                    return new Spo2ChartFragment();
                case TEMPERATURE:
                    return coordinator.supportsContinuousTemperature(getDevice()) ? new TemperatureDailyFragment() : new TemperatureChartFragment();
                case CYCLING:
                    return new CyclingChartFragment();
                case WEIGHT:
                    return new WeightChartFragment();
                case CALORIES:
                    return CaloriesCollectionFragment.newInstance(allowSwipe);
                case RESPIRATORYRATE:
                    return RespiratoryRateCollectionFragment.newInstance(allowSwipe);
            }

            return new UnknownFragment();
        }

        @Override
        public int getCount() {
            return enabledTabsList.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (enabledTabsList.get(position)) {
                case ACTIVITY:
                    return getString(R.string.activity_sleepchart_activity_and_sleep);
                case ACTIVITYLIST:
                    return getString(R.string.charts_activity_list);
                case SLEEP:
                    return getString(R.string.sleepchart_your_sleep);
                case HEARTRATE:
                    return getString(R.string.menuitem_hr);
                case HRVSTATUS:
                    return getString(R.string.pref_header_hrv_status);
                case BODYENERGY:
                    return getString(R.string.body_energy);
                case VO2MAX:
                    return getString(R.string.menuitem_vo2_max);
                case STRESS:
                    return getString(R.string.menuitem_stress);
                case PAI:
                    return getString(getDevice().getDeviceCoordinator().getPaiName());
                case STEPSWEEK:
                    return getString(R.string.steps);
                case SPEEDZONES:
                    return getString(R.string.stats_title);
                case LIVESTATS:
                    return getString(R.string.liveactivity_live_activity);
                case SPO2:
                    return getString(R.string.pref_header_spo2);
                case TEMPERATURE:
                    return getString(R.string.menuitem_temperature);
                case CYCLING:
                    return getString(R.string.title_cycling);
                case WEIGHT:
                    return getString(R.string.menuitem_weight);
                case CALORIES:
                    return getString(R.string.calories);
                case RESPIRATORYRATE:
                    return getString(R.string.respiratoryrate);
            }

            return String.format(Locale.getDefault(), "Unknown %d", position);
        }
    }

    /**
     * A dummy fragment to avoid a crash when we get a unknown tab position (eg. broken
     * preference migration).
     */
    public static class UnknownFragment extends AbstractGBFragment {
        @Override
        public View onCreateView(@NonNull final LayoutInflater inflater,
                                 final ViewGroup container,
                                 final Bundle savedInstanceState) {
            return null;
        }

        @Nullable
        @Override
        protected CharSequence getTitle() {
            return "Unknown";
        }
    }
}
