package nodomain.freeyourgadget.gadgetbridge.devices.cardo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textview.MaterialTextView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.CardoDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmRegion;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;

import static nodomain.freeyourgadget.gadgetbridge.devices.cardo.Ls24xDeviceCoordinator.ACTION_DEVICE_STATUS_UPDATED;


public class DynamicActivity extends AppCompatActivity {
    private static final Logger LOG = LoggerFactory.getLogger(DynamicActivity.class);
    LocalBroadcastManager localBroadcastManager;

    private DynamicModel model;
    private DynamicModel fmRadioModel;
    private LinearLayout containerLayout;

    private GBDevice device;
    private CardoMap<ByteUtils.CardoField, Object> deviceStatus = new CardoMap<>();

    BroadcastReceiver deviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            buildUI(null);
        }
    };

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        model.saveToBundle(outState);
        fmRadioModel.saveToBundle(outState);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            device = bundle.getParcelable(GBDevice.EXTRA_DEVICE);
        } else {
            throw new IllegalArgumentException("Must provide a device when invoking this activity");
        }
        localBroadcastManager = LocalBroadcastManager.getInstance(DynamicActivity.this);

        deviceStatus = ((Ls24xDeviceCoordinator) device.getDeviceCoordinator()).getDeviceStatus();

        ScrollView scrollView = new ScrollView(this);
        containerLayout = new LinearLayout(this);
        containerLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(containerLayout);
        setContentView(scrollView);

        buildUI(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(deviceStatusReceiver, new IntentFilter(ACTION_DEVICE_STATUS_UPDATED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(deviceStatusReceiver);
    }

    private CardView createFMcard() {
        CardView cardView = new CardView(this);
        if (fmRadioModel.getControls().isEmpty())
            return cardView;
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        int margin = (int) getResources().getDisplayMetrics().density * 16;  // Assuming you want 16dp margins on all sides
        layoutParams.setMargins(margin, margin, margin, margin);

        cardView.setLayoutParams(layoutParams);
        cardView.setCardElevation(8f); // Optional, to give the card some elevation/shadow
        cardView.setRadius(16f); // Optional, to make the corners rounded

        ConstraintLayout constraintLayout = new ConstraintLayout(this);
        constraintLayout.setLayoutParams(new ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT));

        TextView titleTextView = new TextView(this);
        titleTextView.setText(getText(R.string.cardo_card_fm_radio));
        titleTextView.setId(View.generateViewId()); // Important for ConstraintLayout
        titleTextView.setTextSize(18);
        titleTextView.setPadding(0, 0, 0, 10);

        constraintLayout.addView(titleTextView);

        View toggleFm = createViewFromControl(fmRadioModel.getControl("fmState"));
        View tuningBar = createViewFromControl(fmRadioModel.getControl("Tuner"));
        constraintLayout.addView(toggleFm);
        constraintLayout.addView(tuningBar);

        fmRadioModel.addControl(new DynamicModel.Control(DynamicModel.Control.Type.BUTTON, "seek_up", R.string.cardo_control_fm_seek_scan_up));
        MaterialButton seekup = createMaterialButton(fmRadioModel.getControl("seek_up"));
        seekup.setEnabled(fmRadioModel.getControl("fmState").getToggleState());
        constraintLayout.addView(seekup);

        fmRadioModel.addControl(new DynamicModel.Control(DynamicModel.Control.Type.BUTTON, "seek_down", R.string.cardo_control_fm_seek_scan_down));
        MaterialButton seekdown = createMaterialButton(fmRadioModel.getControl("seek_down"));
        seekdown.setEnabled(fmRadioModel.getControl("fmState").getToggleState());
        constraintLayout.addView(seekdown);

        int marginInDp = 20;
        int marginInPixels = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                marginInDp,
                getResources().getDisplayMetrics()
        );

        // Set constraints for the views
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(constraintLayout);

        constraintSet.connect(titleTextView.getId(), ConstraintSet.TOP, constraintLayout.getId(), ConstraintSet.TOP, marginInPixels);
        constraintSet.connect(titleTextView.getId(), ConstraintSet.LEFT, constraintLayout.getId(), ConstraintSet.LEFT, marginInPixels);
        constraintSet.connect(titleTextView.getId(), ConstraintSet.RIGHT, constraintLayout.getId(), ConstraintSet.RIGHT, marginInPixels);

        constraintSet.connect(toggleFm.getId(), ConstraintSet.TOP, titleTextView.getId(), ConstraintSet.BOTTOM, marginInPixels / 2);
        constraintSet.connect(toggleFm.getId(), ConstraintSet.LEFT, constraintLayout.getId(), ConstraintSet.LEFT, marginInPixels);
        constraintSet.connect(toggleFm.getId(), ConstraintSet.RIGHT, constraintLayout.getId(), ConstraintSet.RIGHT, marginInPixels);

        constraintSet.connect(tuningBar.getId(), ConstraintSet.TOP, toggleFm.getId(), ConstraintSet.BOTTOM, marginInPixels);
        constraintSet.connect(tuningBar.getId(), ConstraintSet.LEFT, constraintLayout.getId(), ConstraintSet.LEFT, marginInPixels);
        constraintSet.connect(tuningBar.getId(), ConstraintSet.RIGHT, constraintLayout.getId(), ConstraintSet.RIGHT, marginInPixels);


        androidx.constraintlayout.widget.Guideline leftGuideline = new androidx.constraintlayout.widget.Guideline(this);
        leftGuideline.setId(View.generateViewId());
        constraintSet.create(leftGuideline.getId(), ConstraintSet.VERTICAL_GUIDELINE);
        constraintSet.setGuidelineBegin(leftGuideline.getId(), marginInPixels);

        constraintSet.connect(seekdown.getId(), ConstraintSet.TOP, tuningBar.getId(), ConstraintSet.BOTTOM, marginInPixels);
        constraintSet.connect(seekdown.getId(), ConstraintSet.LEFT, leftGuideline.getId(), ConstraintSet.RIGHT, 0);

        constraintSet.connect(seekdown.getId(), ConstraintSet.TOP, tuningBar.getId(), ConstraintSet.BOTTOM, marginInPixels);
        constraintSet.connect(seekdown.getId(), ConstraintSet.LEFT, leftGuideline.getId(), ConstraintSet.RIGHT, 0);

        constraintSet.connect(seekup.getId(), ConstraintSet.TOP, tuningBar.getId(), ConstraintSet.BOTTOM, marginInPixels);
        constraintSet.connect(seekup.getId(), ConstraintSet.LEFT, seekdown.getId(), ConstraintSet.RIGHT, marginInPixels);
        constraintSet.connect(seekup.getId(), ConstraintSet.BOTTOM, constraintLayout.getId(), ConstraintSet.BOTTOM, marginInPixels);

        constraintSet.applyTo(constraintLayout);

        cardView.addView(constraintLayout);

        return cardView;
    }

    private View createViewFromControl(DynamicModel.Control control) {
        if (control.getType() == DynamicModel.Control.Type.LABEL) {
            MaterialTextView textView = new MaterialTextView(this);
            textView.setText(getText(control.getTextResource()));
            textView.setId(View.generateViewId());
            containerLayout.addView(textView);
        } else if (control.getType() == DynamicModel.Control.Type.BUTTON) {
            return createMaterialButton(control);
        } else if (control.getType() == DynamicModel.Control.Type.TOGGLE) {
            return createToggle(control);
        } else if (control.getType() == DynamicModel.Control.Type.DROPDOWN) {
            return createSpinner(control);
        } else if (control.getType() == DynamicModel.Control.Type.SLIDER) {
            return createSlider(control);
        }
        return null;
    }

    private LinearLayout createSlider(DynamicModel.Control control) {
        LinearLayout sliderContainer = new LinearLayout(this);
        sliderContainer.setId(View.generateViewId());
        sliderContainer.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16,
                getResources().getDisplayMetrics()
        );
        sliderContainer.setPadding(padding, padding, padding, padding);

        MaterialTextView label = new MaterialTextView(this);
        label.setText(getText(control.getTextResource()) + String.format(": %.2f", control.getSliderCurrentValue() / 100));
        sliderContainer.addView(label);

        Slider slider = new Slider(this);
        slider.setId(View.generateViewId());
        slider.setValueFrom(control.getSliderMinValue());
        slider.setValueTo(control.getSliderMaxValue());
        slider.setValue(control.getSliderCurrentValue());
        slider.setStepSize(10);

        slider.addOnChangeListener((slider1, value, fromUser) -> {
            control.setSliderCurrentValue(value);
            label.setText(getText(control.getTextResource()) + String.format(": %.2f", value / 100));
            Intent intent = new Intent(CardoDeviceSupport.COMMAND_SET_VALUE);
            intent.putExtra(CardoDeviceSupport.EXTRA_CONTROL_ID, control.getControlId());
            intent.putExtra(CardoDeviceSupport.EXTRA_VALUE, value);
            localBroadcastManager.sendBroadcast(intent);


        });

        sliderContainer.addView(slider);
        return sliderContainer;
    }

    private Spinner createSpinner(DynamicModel.Control control) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setId(View.generateViewId());
        String[] options = control.getDropdownOptions();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                options
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        MaterialShapeDrawable shapeDrawable = new MaterialShapeDrawable();

        shapeDrawable.setFillColor(ColorStateList.valueOf(MaterialColors.getColor(spinner, com.google.android.material.R.attr.colorSurface)));
        shapeDrawable.setStroke(2.0f, MaterialColors.getColor(spinner, com.google.android.material.R.attr.colorOutline));
        float cornerSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                8,  // 8dp per il corner radius
                getResources().getDisplayMetrics()
        );
        shapeDrawable.setCornerSize(cornerSize);
        spinner.setBackground(shapeDrawable);

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16,
                getResources().getDisplayMetrics()
        );
        spinner.setPadding(padding, padding, padding, padding);
        spinner.setSelection(control.getSelectedOptionIndex());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                control.setSelectedOptionIndex(position);
                Enum<?> enumValue = control.getEnumType().getEnumConstants()[position];

                Intent intent = new Intent(CardoDeviceSupport.COMMAND_DROPDOWN_CHANGED);
                intent.putExtra(CardoDeviceSupport.EXTRA_CONTROL_ID, control.getControlId());
                intent.putExtra(CardoDeviceSupport.EXTRA_SELECTED_INDEX, position);
                intent.putExtra(CardoDeviceSupport.EXTRA_VALUE, enumValue);
                localBroadcastManager.sendBroadcast(intent);

                if (view instanceof TextView) {
                    TextView textView = (TextView) view;
                    textView.setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface));
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Ignore
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(padding, padding / 2, padding, padding / 2);
        spinner.setLayoutParams(params);

        return spinner;
    }

    private LinearLayout createToggle(DynamicModel.Control control) {
        LinearLayout switchContainer = new LinearLayout(this);
        switchContainer.setId(View.generateViewId());
        switchContainer.setOrientation(LinearLayout.HORIZONTAL);
        switchContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16,
                getResources().getDisplayMetrics()
        );
        switchContainer.setPadding(padding, padding, padding, padding);

        MaterialTextView label = new MaterialTextView(this);
        label.setText(getText(control.getTextResource()));
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setId(View.generateViewId());
        toggle.setChecked(control.getToggleState());

        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            control.setToggleState(isChecked);
            Intent intent = new Intent(CardoDeviceSupport.COMMAND_TOGGLE_CHANGED);
            intent.putExtra(CardoDeviceSupport.EXTRA_CONTROL_ID, control.getControlId());
            intent.putExtra(CardoDeviceSupport.EXTRA_TOGGLE_STATE, isChecked);
            intent.putExtra(CardoDeviceSupport.EXTRA_VALUE, isChecked);
            localBroadcastManager.sendBroadcast(intent);
        });

        switchContainer.addView(label);
        switchContainer.addView(toggle);
        return switchContainer;
    }

    private MaterialButton createMaterialButton(DynamicModel.Control control) {
        MaterialButton button = new MaterialButton(this);
        button.setText(getText(control.getTextResource()));
        button.setId(View.generateViewId());

        button.setOnClickListener(v -> {
            Intent intent = new Intent(CardoDeviceSupport.COMMAND_BUTTON_PRESSED);
            intent.putExtra(CardoDeviceSupport.EXTRA_CONTROL_ID, control.getControlId());
            intent.putExtra(CardoDeviceSupport.EXTRA_VALUE, 0);

            localBroadcastManager.sendBroadcast(intent);

        });

        button.setOnLongClickListener(v -> {
            Intent intent = new Intent(CardoDeviceSupport.COMMAND_BUTTON_PRESSED);
            intent.putExtra(CardoDeviceSupport.EXTRA_CONTROL_ID, control.getControlId());
            intent.putExtra(CardoDeviceSupport.EXTRA_VALUE, 1);

            localBroadcastManager.sendBroadcast(intent);

            return true;
        });

        return button;
    }

    private void buildUI(Bundle savedInstanceState) {
        initializeModels(savedInstanceState);

        containerLayout.removeAllViews();
        containerLayout.addView(createFMcard());

//        for (final DynamicModel.Control control : model.getControls()) {
//            View view = createViewFromControl(control);
//            if (view != null)
//                containerLayout.addView(view);
//        }
    }

    private void initializeModels(Bundle savedInstanceState) {
        model = new DynamicModel("generic");
        fmRadioModel = new DynamicModel("radio");

        if (savedInstanceState != null) {
            model.restoreFromBundle(savedInstanceState);
            fmRadioModel.restoreFromBundle(savedInstanceState);
        } else {

            //test
//            model.addControl(new DynamicModel.Control(DynamicModel.Control.Type.LABEL, "HEHE", "Hello, World!", true));
//            model.addControl(new DynamicModel.Control(DynamicModel.Control.Type.BUTTON, "HUHU", "Click Me", true));
//            model.addControl(new DynamicModel.Control(CardoLanguage.class, "HOHO", 0));

            fmRadioModel.addControl(new DynamicModel.Control(
                    DynamicModel.Control.Type.SLIDER,
                    "Tuner",
                    R.string.preferences_fm_frequency,
                    ((CardoFmRegion) deviceStatus.getValueByName("fmRegion")).getMinFreq(),
                    ((CardoFmRegion) deviceStatus.getValueByName("fmRegion")).getMaxFreq(),
                    (int) deviceStatus.getValueByName("currentStation")
            ));

            fmRadioModel.addControl(new DynamicModel.Control(
                    DynamicModel.Control.Type.TOGGLE,
                    "fmState",  // control ID
                    R.string.cardo_control_enable_radio,  // label text
                    deviceStatus.getValueByName("fmState") != CardoFmState.IDLE
            ));


        }
    }
}