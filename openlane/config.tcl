# OpenLane 1 Configuration for tt_um_gmlewis_reticulum
# Targets SkyWater 130nm standard cell library sky130_fd_sc_hd

set ::env(DESIGN_NAME) "tt_um_gmlewis_reticulum"
set ::env(VERILOG_FILES) [glob $::env(DESIGN_DIR)/../src/*.v]

# Clock configuration (50 MHz = 20 ns period)
set ::env(CLOCK_PORT) "clk"
set ::env(CLOCK_PERIOD) "20.0"

# Design floorplanning
set ::env(DESIGN_IS_CORE) 0
set ::env(FP_PIN_ORDER_CFG) $::env(DESIGN_DIR)/pin_order.cfg
set ::env(FP_SIZING) "absolute"
set ::env(DIE_AREA) "0 0 680 230"

# Target cell density & synthesis strategy
set ::env(PL_TARGET_DENSITY) 0.55
set ::env(SYNTH_STRATEGY) "AREA 0"

# Technology and cell library
set ::env(PDK) "sky130A"
set ::env(STD_CELL_LIBRARY) "sky130_fd_sc_hd"

# Physical design optimizations
set ::env(GLB_RESIZER_TIMING_OPTIMIZATIONS) 1
set ::env(PL_RESIZER_TIMING_OPTIMIZATIONS) 1
set ::env(RUN_CTS) 1
set ::env(RUN_FILL_INSERTION) 1
set ::env(RUN_TAP_ENDCAP_INSERTION) 1
