/*  Copyright (C) 2023-2024 Frank Ertl

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.activity;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.HuamiWorkoutScreenActivityType;

public enum WithingsActivityType {

    WALKING(1),
    RUNNING(2),
    HIKING(3),
    BMX(5),
    BIKING(6),
    SWIMMING(7),
    SURFING(8),
    KITESURFING(9),
    WINDSURFING(10),
    BODYBOARDING(11),
    TENNIS(12),
    PINGPONG(13),
    SQUASH(14),
    BADMINTON(15),
    WEIGHTLIFTING(16),
    GYMNASTICS(17),
    ELLIPTICAL(18),
    PILATES(19),
    BASKETBALL(20),
    SOCCER(21),
    FOOTBALL(22),
    RUGBY(23),
    VOLLEYBALL(24),
    GOLFING(227),
    YOGA(28),
    DANCING(29),
    BOXING(30),
    SKIING(34),
    SNOWBOARDING(35),
    ROWING(0), // The code has yet to be identified.
    INDOOR_RUNNING(307),
    CANOEING(495),
    FISHING(497),
    BIATHLON(504),
    DODGEBALL(512),
    TRACK_AND_FIELD(517),
    E_MOUNTAIN_BIKING(526),
    BREAKING(544),
    CROSSFIT(546),
    CRICKET(548),
    FLAMENCO_DANCING(549),
    DOG_WALKING(559),
    BREATHING_EXERCISES(560),
    BOWLING(564),
    ZUMBA(188),
    BASEBALL(191),
    HANDBALL(192),
    HOCKEY(193),
    ICEHOCKEY(194),
    CLIMBING(195),
    ICESKATING(196),
    RIDING(26),
    OTHER(36);

    private static final Logger LOG = LoggerFactory.getLogger(WithingsActivityType.class);

    private final int code;

    WithingsActivityType(int typeCode) {
        this.code = typeCode;
    }

    public static WithingsActivityType fromCode(int withingsCode) {
        for (WithingsActivityType type : values()) {
            if (type.code == withingsCode) {
                return type;
            }
        }
        LOG.warn("No matching WithingsActivityType for code: {}, falling back to OTHER", withingsCode);
        return OTHER;
    }

    public int getCode() {
        return code;
    }

    public ActivityKind toActivityKind() {
        switch (this) {
            case WALKING:
                return ActivityKind.WALKING;
            case RUNNING:
                return ActivityKind.RUNNING;
            case HIKING:
                return ActivityKind.HIKING;
            case BMX:
                return ActivityKind.BMX;
            case BIKING:
                return ActivityKind.CYCLING;
            case SWIMMING:
                return ActivityKind.SWIMMING;
            case SURFING:
                return ActivityKind.ACTIVITY;
            case KITESURFING:
                return ActivityKind.ACTIVITY;
            case WINDSURFING:
                return ActivityKind.ACTIVITY;
            case BODYBOARDING:
                return ActivityKind.SURFING;
            case TENNIS:
                return ActivityKind.ACTIVITY;
            case PINGPONG:
                return ActivityKind.PINGPONG;
            case SQUASH:
                return ActivityKind.ACTIVITY;
            case BADMINTON:
                return ActivityKind.BADMINTON;
            case WEIGHTLIFTING:
                return ActivityKind.ACTIVITY;
            case GYMNASTICS:
                return ActivityKind.EXERCISE;
            case ELLIPTICAL:
                return ActivityKind.ELLIPTICAL_TRAINER;
            case PILATES:
                return ActivityKind.YOGA;
            case BASKETBALL:
                return ActivityKind.BASKETBALL;
            case SOCCER:
                return ActivityKind.SOCCER;
            case FOOTBALL:
                return ActivityKind.ACTIVITY;
            case RUGBY:
                return ActivityKind.ACTIVITY;
            case VOLLEYBALL:
                return ActivityKind.ACTIVITY;
            case GOLFING:
                return ActivityKind.ACTIVITY;
            case YOGA:
                return ActivityKind.YOGA;
            case DANCING:
                return ActivityKind.ACTIVITY;
            case BOXING:
                return ActivityKind.ACTIVITY;
            case SKIING:
                return ActivityKind.ACTIVITY;
            case SNOWBOARDING:
                return ActivityKind.ACTIVITY;
            case ROWING:
                return ActivityKind.ROWING_MACHINE;
            case INDOOR_RUNNING:
                return ActivityKind.INDOOR_RUNNING;
            case CANOEING:
                return ActivityKind.CANOEING;
            case FISHING:
                return ActivityKind.FISHING;
            case BIATHLON:
                return ActivityKind.BIATHLON;
            case DODGEBALL:
                return ActivityKind.DODGEBALL;
            case TRACK_AND_FIELD:
                return ActivityKind.TRACK_AND_FIELD;
            case E_MOUNTAIN_BIKING:
                return ActivityKind.E_MOUNTAIN_BIKE;
            case BREAKING:
                return ActivityKind.BREAKING;
            case CROSSFIT:
                return ActivityKind.CROSSFIT;
            case CRICKET:
                return ActivityKind.CRICKET;
            case FLAMENCO_DANCING:
                return ActivityKind.DANCE;
            case DOG_WALKING:
                return ActivityKind.OUTDOOR_WALKING;
            case BREATHING_EXERCISES:
                return ActivityKind.BREATHWORK;
            case BOWLING:
                return ActivityKind.BOWLING;
            case ZUMBA:
                return ActivityKind.ACTIVITY;
            case BASEBALL:
                return ActivityKind.CRICKET;
            case HANDBALL:
                return ActivityKind.ACTIVITY;
            case HOCKEY:
                return ActivityKind.ACTIVITY;
            case ICEHOCKEY:
                return ActivityKind.ACTIVITY;
            case CLIMBING:
                return ActivityKind.CLIMBING;
            case ICESKATING:
                return ActivityKind.ACTIVITY;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    /**
     * Returns the workout mode byte used in the WORKOUT_SCREEN_SETTINGS TLV.
     * <ul>
     *   <li>1 = Indoor / pool (no GPS, indoor-specific UI)</li>
     *   <li>2 = GPS tracking (outdoor with GPS)</li>
     *   <li>3 = No GPS (outdoor/generic without GPS)</li>
     * </ul>
     */
    public byte getWorkoutMode() {
        switch (this) {
            // GPS-tracked outdoor sports
            case WALKING:
            case RUNNING:
            case HIKING:
            case SURFING:
            case KITESURFING:
            case WINDSURFING:
            case BIATHLON:
            case SKIING:
            case SNOWBOARDING:
            case RIDING:
            case E_MOUNTAIN_BIKING:
            case DOG_WALKING:
                return 2; // GPS
            // Indoor / pool sports
            case SWIMMING:
            case CLIMBING:
            case ELLIPTICAL:
            case WEIGHTLIFTING:
            case GYMNASTICS:
            case PILATES:
            case YOGA:
            case DANCING:
            case BREAKING:
            case CROSSFIT:
            case FLAMENCO_DANCING:
            case BREATHING_EXERCISES:
            case INDOOR_RUNNING:
            case ZUMBA:
            case BOXING:
            case ICESKATING:
            case ROWING:
                return 1; // Indoor
            // Everything else: no GPS, not specifically indoor
            default:
                return 3; // NoGPS
        }
    }

    /**
     * Returns the flags short for the WORKOUT_SCREEN_SETTINGS TLV.
     * Indoor/pool types use 0x0001; all others use 0x0000.
     */
    public short getWorkoutFlags() {
        return getWorkoutMode() == 1 ? (short) 0x0001 : (short) 0x0000;
    }

    public static WithingsActivityType fromPrefValue(final String prefValue) {
        if ("golf".equalsIgnoreCase(prefValue)) {
            return GOLFING;
        }
        if ("rowing_machine".equalsIgnoreCase(prefValue)) {
            return ROWING;
        }

        final String normalizedPref = prefValue.replace("_", "").toLowerCase(Locale.ROOT);
        for (final WithingsActivityType type : values()) {
            final String normalizedType = type.name().replace("_", "").toLowerCase(Locale.ROOT);
            if (normalizedType.equals(normalizedPref)) {
                return type;
            }
        }
        throw new RuntimeException("No matching WithingsActivityType for pref value: " + prefValue);
    }
}
