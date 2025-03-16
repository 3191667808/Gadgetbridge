package nodomain.freeyourgadget.gadgetbridge.devices.cardo;

import android.os.Bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DynamicModel {
    private static final Logger LOG = LoggerFactory.getLogger(DynamicModel.class);
    private final List<Control> controls;
    private final String prefix;

    public DynamicModel(String prefix) {
        controls = new ArrayList<>();
        this.prefix = prefix;
    }

    public List<Control> getControls() {
        return controls;
    }

    public Control getControl(String id) {
        for (Control c : controls) {
            if (c.getControlId().equals(id))
                return c;
        }
        return null;
    }

    public void addControl(Control control) {
        controls.add(control);
    }

    public void saveToBundle(Bundle outState) {
        outState.putInt(this.prefix + "_control_count", controls.size());
        for (int i = 0; i < controls.size(); i++) {
            controls.get(i).saveToBundle(outState, this.prefix, i);
        }
    }

    public void restoreFromBundle(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        controls.clear();
        int count = savedInstanceState.getInt(this.prefix + "_control_count", 0);
        for (int i = 0; i < count; i++) {
            Control control = Control.restoreFromBundle(savedInstanceState, this.prefix, i);
            if (control != null) {
                controls.add(control);
            }
        }
    }

    public static class Control {
        private final Type type;
        private final String controlId;
        private int textResource;
        //dropdowns
        private Class<? extends Enum<?>> enumType;
        private int selectedOptionIndex;

        //sliders
        private float sliderMinValue;
        private float sliderMaxValue;
        private float sliderCurrentValue;

        //toggle
        private boolean toggleState;

        public Control(Type type, String controlId, int textResource) {
            this.type = type;
            this.controlId = controlId;
            this.textResource = textResource;
        }

        public <E extends Enum<E>> Control(Class<? extends Enum<?>> enumType, String controlId, int selectedOptionIndex) {
            this.type = Type.DROPDOWN;
            this.controlId = controlId;
            this.enumType = enumType;
            this.selectedOptionIndex = selectedOptionIndex;
        }

        public Control(Type type, String controlId, int textResource, float minValue, float maxValue, float currentValue) {
            this.type = type;
            this.controlId = controlId;
            this.textResource = textResource;
            this.sliderMinValue = minValue;
            this.sliderMaxValue = maxValue;
            this.sliderCurrentValue = currentValue;
        }

        public Control(Type type, String controlId, int textResource, boolean initialState) {
            this.type = type;
            this.controlId = controlId;
            this.textResource = textResource;
            this.toggleState = initialState;
        }

        public static Control restoreFromBundle(Bundle savedInstanceState, String prefix, int index) {
            String typeName = savedInstanceState.getString(prefix + "_control_type_" + index);
            Type type = Type.valueOf(typeName);
            String controlId = savedInstanceState.getString(prefix + "_control_id_" + index);
            int textResource = savedInstanceState.getInt(prefix + "_control_text_resource_" + index);
            boolean enabled = savedInstanceState.getBoolean(prefix + "_control_enabled_" + index);

            if (type == Type.DROPDOWN) {
                try {
                    String enumTypeName = savedInstanceState.getString(prefix + "_dropdown_enum_type_" + index);
                    @SuppressWarnings("unchecked")
                    Class<? extends Enum<?>> enumType = (Class<? extends Enum<?>>) Class.forName(enumTypeName);
                    int selectedOptionIndex = savedInstanceState.getInt(prefix + "_dropdown_selected_option_" + index);
                    return new Control(enumType, controlId, selectedOptionIndex);
                } catch (ClassNotFoundException e) {
                    LOG.error(e.getMessage());
                    return null;
                }
            }

            if (type == Type.SLIDER) {
                float min = savedInstanceState.getFloat(prefix + "_slider_min_" + index);
                float max = savedInstanceState.getFloat(prefix + "_slider_max_" + index);
                float current = savedInstanceState.getFloat(prefix + "_slider_current_" + index);
                return new Control(type, controlId, textResource, min, max, current);
            }

            if (type == Type.TOGGLE) {
                boolean toggleState = savedInstanceState.getBoolean(prefix + "_toggle_state_" + index);
                Control control = new Control(type, controlId, textResource);
                control.setToggleState(toggleState);
                return control;
            }

            return new Control(type, controlId, textResource);
        }

        public int getTextResource() {
            return textResource;
        }

        public void setTextResource(int textResource) {
            this.textResource = textResource;
        }

        public Type getType() {
            return type;
        }

        public String getControlId() {
            return controlId;
        }

        public Class<? extends Enum<?>> getEnumType() {
            return enumType;
        }

        public float getSliderMinValue() {
            return sliderMinValue;
        }

        public float getSliderMaxValue() {
            return sliderMaxValue;
        }

        public float getSliderCurrentValue() {
            return sliderCurrentValue;
        }

        public void setSliderCurrentValue(float value) {
            this.sliderCurrentValue = value;
        }

        public boolean getToggleState() {
            return toggleState;
        }

        public void setToggleState(boolean state) {
            this.toggleState = state;
        }

        public int getSelectedOptionIndex() {
            return selectedOptionIndex;
        }

        public void setSelectedOptionIndex(int selectedOptionIndex) {
            this.selectedOptionIndex = selectedOptionIndex;
        }

        public String[] getDropdownOptions() {
            if (enumType == null) return new String[0];

            Enum<?>[] values = enumType.getEnumConstants();
            String[] names = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                names[i] = values[i].toString();
            }
            return names;
        }

        public void saveToBundle(Bundle outState, String prefix, int index) {
            outState.putString(prefix + "_control_type_" + index, type.name());
            outState.putString(prefix + "_control_id_" + index, controlId);
            outState.putInt(prefix + "_control_text_resource_" + index, textResource);

            if (type == Type.DROPDOWN) {
                outState.putString(prefix + "_dropdown_enum_type_" + index, enumType.getName());
                outState.putInt(prefix + "_dropdown_selected_option_" + index, selectedOptionIndex);
            }

            if (type == Type.SLIDER) {
                outState.putFloat(prefix + "_slider_min_" + index, sliderMinValue);
                outState.putFloat(prefix + "_slider_max_" + index, sliderMaxValue);
                outState.putFloat(prefix + "_slider_current_" + index, sliderCurrentValue);
            }

            if (type == Type.TOGGLE) {
                outState.putBoolean(prefix + "_toggle_state_" + index, toggleState);
            }
        }

        public enum Type {BUTTON, LABEL, DROPDOWN, SLIDER, TOGGLE}
    }
}