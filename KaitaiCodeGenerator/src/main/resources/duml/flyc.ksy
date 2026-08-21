meta:
  id: duml_flyc
  title: DUML FlyC (Flight Controller) command set
  endian: le
  bit-endian: le
  -duml-cmdset: 0x03
  -duml-class: Flyc

doc: |
  Commands under cmdSet=FLYC(0x03).

enums:
  flyc_state:
    0x00: manual
    0x01: atti
    0x02: atti_cl
    0x03: atti_hover
    0x04: hover
    0x05: gps_blake
    0x06: gps_atti
    0x07: gps_cl
    0x08: gps_home_lock
    0x09: gps_hot_point
    0x0a: assisted_takeoff
    0x0b: auto_takeoff
    0x0c: auto_landing
    0x0d: atti_landing
    0x0e: navi_go
    0x0f: go_home
    0x10: click_go
    0x11: joystick
    0x17: atti_limited
    0x18: gps_atti_limited
    0x19: navi_mission_follow
    0x1a: navi_submode_tracking
    0x1b: navi_submode_pointing
    0x1c: pano
    0x1d: farming
    0x1e: fpv
    0x1f: sport
    0x20: novice
    0x21: force_landing
    0x23: terrain_tracking
    0x24: navi_adv_gohome
    0x25: navi_adv_landing
    0x26: tripod_gps
    0x27: track_headlock
    0x29: engine_start
    0x2b: gentle_gps
    0x64: other

  flyc_command:
    0x01: auto_fly
    0x02: auto_landing
    0x03: homepoint_now
    0x04: homepoint_hot
    0x05: homepoint_loc
    0x06: gohome
    0x07: start_motor
    0x08: stop_motor
    0x09: calibration
    0x0a: deform_protect_close
    0x0b: deform_protect_open
    0x0c: drop_gohome
    0x0d: drop_takeoff
    0x0e: drop_landing
    0x0f: dynamic_home_point_open
    0x10: dynamic_home_point_close
    0x11: follow_function_open
    0x12: follow_function_close
    0x13: ioc_open
    0x14: ioc_close
    0x15: drop_calibration
    0x16: pack_mode
    0x17: unpack_mode
    0x18: enter_manual_mode
    0x19: stop_deform
    0x1c: down_deform
    0x1d: up_deform
    0x1e: force_landing
    0x1f: force_landing_2
    0x64: other

  flyc_gohome_state:
    0x00: standby
    0x01: preascending
    0x02: align
    0x03: ascending
    0x04: cruise
    0x05: braking
    0x06: bypassing
    0x07: other

  flyc_rc_mode_channel:
    0x00: channel_manual
    0x01: channel_a
    0x02: channel_p
    0x03: channel_nav
    0x04: channel_fpv
    0x05: channel_farm
    0x06: channel_s
    0x07: channel_f

  flyc_battery_type:
    0x00: unknown
    0x01: non_smart
    0x02: smart
    0x03: battery_type_reserved_3

  flyc_gohome_reason:
    0x00: none
    0x01: warning_power_gohome
    0x02: warning_power_landing
    0x03: smart_power_gohome
    0x04: smart_power_landing
    0x05: low_voltage_landing
    0x06: low_voltage_gohome
    0x07: serious_low_voltage_landing
    0x08: rc_onekey_gohome
    0x09: rc_assistant_takeoff
    0x0a: rc_auto_takeoff
    0x0b: rc_auto_landing
    0x0c: app_auto_gohome
    0x0d: app_auto_landing
    0x0e: app_auto_takeoff
    0x0f: outof_control_gohome
    0x10: api_auto_takeoff
    0x11: api_auto_landing
    0x12: api_auto_gohome
    0x13: avoid_ground_landing
    0x14: airport_avoid_landing
    0x15: too_close_gohome_landing
    0x16: too_far_gohome_landing
    0x17: app_wp_mission
    0x18: wp_auto_takeoff
    0x19: gohome_avoid
    0x1a: gohome_finish
    0x1b: vert_low_limit_landing
    0x1c: battery_force_landing
    0x1d: mc_protect_gohome

  flyc_gps_state:
    0x00: already
    0x01: forbidden
    0x02: gpsnum_not_enough
    0x03: gps_hdop_large
    0x04: gps_position_mismatch
    0x05: speed_error_large
    0x06: yaw_error_large
    0x07: compass_error_large
    0x08: unknown

  flyc_product_type:
    0x00: unknown
    0x01: inspire
    0x02: p3s_p3x
    0x03: p3x
    0x04: p3c
    0x05: open_frame
    0x06: aceone
    0x07: wkm
    0x08: naza
    0x09: a2
    0x0a: a3
    0x0b: p4
    0x0e: pm820
    0x0f: p34k
    0x10: wm220
    0x11: orange2
    0x12: pomato
    0x14: n3
    0x17: pm820pro
    0xff: no_flyc
    0x64: none

  flyc_imu_init_fail_reason:
    0x00: none_or_monitor_error
    0x01: collecting_data
    0x02: gyro_dead
    0x03: acce_dead
    0x04: compass_dead
    0x05: barometer_dead
    0x06: barometer_negative
    0x07: compass_mod_too_large
    0x08: gyro_bias_too_large
    0x09: acce_bias_too_large
    0x0a: compass_noise_too_large
    0x0b: barometer_noise_too_large
    0x0c: waiting_mc_stationary
    0x0d: acce_move_too_large
    0x0e: mc_header_moved
    0x0f: mc_vibrated
    0x64: none

  flyc_sdk_ctrl_device:
    0x00: rc
    0x01: app
    0x02: onboard_device
    0x03: camera
    0x80: other

  # Sparse, ~100-entry motor-start-failure / general-fault code. Shared by
  # start_fail_state's low 7 bits and by the two WM620-only tail bytes.
  flyc_start_fail_reason:
    0x00: none_allow_start
    0x01: compass_error
    0x02: assistant_protected
    0x03: device_lock_protect
    0x04: off_radius_limit_landed
    0x05: imu_need_adv_calib
    0x06: imu_sn_error
    0x07: temperature_cal_not_ready
    0x08: compass_calibration_in_progress
    0x09: attitude_error
    0x0a: novice_mode_without_gps
    0x0b: battery_cell_error_stop_motor
    0x0c: battery_communicate_error_stop_motor
    0x0d: battery_voltage_very_low_stop_motor
    0x0e: battery_below_user_low_land_level_stop_motor
    0x0f: battery_main_vol_low_stop_motor
    0x10: battery_temp_and_vol_low_stop_motor
    0x11: battery_smart_low_land_stop_motor
    0x12: battery_not_ready_stop_motor
    0x13: may_run_simulator
    0x14: gear_pack_mode
    0x15: atti_limit
    0x16: product_not_activation_stop_motor
    0x17: in_fly_limit_area_need_stop_motor
    0x18: bias_limit
    0x19: esc_error
    0x1a: imu_is_initing
    0x1b: system_upgrade_stop_motor
    0x1c: have_run_simulator_please_restart
    0x1d: imu_cali_in_progress
    0x1e: too_large_tilt_angle_when_auto_take_off_stop_motor
    0x1f: gyroscope_is_stuck
    0x20: accel_is_stuck
    0x21: compass_is_stuck
    0x22: pressure_sensor_is_stuck
    0x23: pressure_read_is_negative
    0x24: compass_mod_is_huge
    0x25: gyro_bias_is_large
    0x26: accel_bias_is_large
    0x27: compass_noise_is_large
    0x28: pressure_noise_is_large
    0x29: sn_invalid
    0x2a: pressure_slope_is_large
    0x2b: ahrs_error_is_large
    0x2c: flash_operating
    0x2d: gps_disconnect
    0x2e: out_of_whitelist_area
    0x2f: sd_card_exception
    0x3d: imu_no_connection
    0x3e: rc_calibration
    0x3f: rc_calibration_exception
    0x40: rc_calibration_unfinished
    0x41: rc_calibration_exception_2
    0x42: rc_calibration_exception_3
    0x43: aircraft_type_mismatch
    0x44: found_unfinished_module
    0x46: gyro_abnormal
    0x47: baro_abnormal
    0x48: compass_abnormal
    0x49: gps_abnormal
    0x4a: ns_abnormal
    0x4b: topology_abnormal
    0x4c: rc_need_cali
    0x4d: invalid_float
    0x4e: m600_bat_too_little
    0x4f: m600_bat_auth_err
    0x50: m600_bat_comm_err
    0x51: m600_bat_dif_volt_large_1
    0x52: m600_bat_dif_volt_large_2
    0x53: invalid_version
    0x54: gimbal_gyro_abnormal
    0x55: gimbal_esc_pitch_no_data
    0x56: gimbal_esc_roll_no_data
    0x57: gimbal_esc_yaw_no_data
    0x58: gimbal_firm_is_updating
    0x59: gimbal_disorder
    0x5a: gimbal_pitch_shock
    0x5b: gimbal_roll_shock
    0x5c: gimbal_yaw_shock
    0x5d: imu_calibration_finished
    0x5e: takeoff_exception
    0x5f: esc_stall_near_ground
    0x60: esc_unbalance_on_grd
    0x61: esc_part_empty_on_grd
    0x62: engine_start_failed
    0x63: auto_takeoff_launch_failed
    0x64: roll_over_on_grd
    0x65: bat_version_error
    0x66: rtk_bad_signal
    0x67: rtk_deviation_error
    0x70: esc_calibrating
    0x71: gps_sign_invalid
    0x72: gimbal_is_calibrating
    0x73: lock_by_app
    0x74: start_fly_height_error
    0x75: esc_version_not_match
    0x76: imu_ori_not_match
    0x77: stop_by_app
    0x78: compass_imu_ori_not_match
    0x100: other

types:
  osd_general_response:
    -duml: {cmd: 0x43, direction: request, name: OsdGeneral}
    doc: |
      Aircraft "OSD General Data" push. Broadcast by the flight controller
      and relayed to the phone by the RC's HD Link module (sender module 14).
    seq:
      - id: longitude
        type: f8
        doc: radians
      - id: latitude
        type: f8
        doc: radians
      - id: relative_height
        type: s2
        -scale: 0.1
        doc: meters, altitude above ground
      - id: vgx
        type: s2
        -scale: 0.1
        doc: meters/sec, ground speed X
      - id: vgy
        type: s2
        -scale: 0.1
        doc: meters/sec, ground speed Y
      - id: vgz
        type: s2
        -scale: 0.1
        doc: meters/sec, ground speed Z (positive down)
      - id: pitch
        type: s2
        -scale: 0.1
        doc: degrees
      - id: roll
        type: s2
        -scale: 0.1
        doc: degrees
      - id: yaw
        type: s2
        -scale: 0.1
        doc: degrees

      # ctrl_info byte (offset 30): low 7 bits then top bit
      - id: flyc_state
        type: b7
        enum: flyc_state
      - id: no_rc_state
        type: b1

      - id: latest_cmd
        type: u1
        enum: flyc_command
        doc: last command the controller executed

      # controller_state, 4 bytes / 32 bits (offset 32)
      - id: can_ioc_work
        type: b1
      - id: on_ground
        type: b1
      - id: in_air
        type: b1
      - id: motor_on
        type: b1
        doc: force allow start motors ignoring errors
      - id: usonic_on
        type: b1
        doc: ultrasonic wave sonar in use
      - id: gohome_state
        type: b3
        enum: flyc_gohome_state

      - id: mvo_used
        type: b1
        doc: monocular visual odometry used as horizontal velocity sensor
      - id: battery_req_gohome
        type: b1
      - id: battery_req_land
        type: b1
        doc: landing required, battery voltage low
      - id: reserved_controller_state_11
        type: b1
      - id: still_heating
        type: b1
        doc: IMU preheating
      - id: rc_mode_channel
        type: b2
        enum: flyc_rc_mode_channel
      - id: gps_used
        type: b1
        doc: satellite positioning used as horizontal velocity sensor

      - id: compass_over_range
        type: b1
      - id: wave_err
        type: b1
        doc: ultrasonic sensor error
      - id: gps_level
        type: b4
        doc: satellite positioning signal level, 0-15
      - id: battery_type
        type: b2
        enum: flyc_battery_type

      - id: accel_over_range
        type: b1
      - id: is_vibrating
        type: b1
      - id: press_err
        type: b1
        doc: barometer error
      - id: esc_stall
        type: b1
        doc: ESC reports motor blocked
      - id: esc_empty
        type: b1
        doc: ESC reports not enough force
      - id: propeller_catapult
        type: b1
      - id: gohome_height_mod
        type: b1
        doc: go-home height was modified
      - id: out_of_limit
        type: b1

      - id: gps_nums
        type: u1
        doc: number of GNSS positioning satellites
      - id: gohome_landing_reason
        type: u1
        enum: flyc_gohome_reason
        doc: reason for automatic go-home or landing

      # start_fail_state byte (offset 38).
      - id: start_fail_reason
        type: b7
        enum: flyc_start_fail_reason
      - id: start_fail_happened
        type: b1

      # controller_state_ext byte (offset 39).
      - id: gps_state
        type: b4
        enum: flyc_gps_state
        doc: cause of not being able to switch to GPS mode
      - id: wp_limit_mode
        type: b1
      - id: reserved_controller_state_ext_5
        type: b3

      - id: batt_remain
        type: u1
        doc: battery remaining capacity, percent
      - id: ultrasonic_height
        type: u1
        doc: height as reported by the ultrasonic sensor
      - id: motor_startup_time
        type: u2
        doc: aka fly time
      - id: motor_startup_times
        type: u1
        doc: aka motor revolution count

      # bat_alarm1 / bat_alarm2 bytes.
      - id: alarm1_voltage
        type: b7
      - id: alarm1_function
        type: b1
      - id: alarm2_voltage
        type: b7
      - id: alarm2_function
        type: b1

      - id: version_match
        type: u1
        doc: flight controller version match code
      - id: product_type
        type: u1
        enum: flyc_product_type
      - id: imu_init_fail_reason
        type: u1
        enum: flyc_imu_init_fail_reason
      # Non existing in P3 packets, exist in WM620_FW_01.02.0300
      - id: motor_fail_reason
        type: u1
        enum: flyc_start_fail_reason
        if: _io.size > 50
      - id: motor_start_cause_no_start_action
        type: u1
        enum: flyc_start_fail_reason
        if: _io.size > 50
      - id: sdk_ctrl_device
        type: u1
        enum: flyc_sdk_ctrl_device
        if: _io.size > 50
      - id: unknown35
        type: u2
        if: _io.size > 50

  send_gps_request:
    -duml: {cmd: 0x20, direction: request, name: SendGpsToFlyc}
    doc: |
      Phone GPS fix, pushed by the app to the flight controller.

      `unknown1`/`unknown2` stayed constant within each capture but
      differed across the three (0x7a/0x6a, 0x7b/0x6a, 0x7d/0x6a) -
      likely a session/uptime-derived value the flight controller doesn't
      validate, but unconfirmed.
    seq:
      - id: fix_valid
        type: u1
        doc: |
          Observed 2 with latitude/longitude both 0 before the phone has a
          fix, 3 once it does.
      - id: latitude
        type: s4
        -scale: 0.000001
        doc: degrees
      - id: longitude
        type: s4
        -scale: 0.000001
        doc: degrees
      - id: seq
        type: u2
        doc: increments once per message;
      - id: unknown1
        type: u1
      - id: unknown2
        type: u1
