# Makefile for Tiny Tapeout cocotb simulation

SIM ?= icarus
TOPLEVEL_LANG ?= verilog
# Directory of this Makefile
TEST_DIR := $(CURDIR)
SRC_DIR ?= $(abspath $(TEST_DIR)/../src)

VERILOG_SOURCES += $(TEST_DIR)/tb.v
VERILOG_SOURCES += $(SRC_DIR)/tt_um_gmlewis_reticulum.v

# TOPLEVEL is the name of the toplevel module in your Verilog files
TOPLEVEL = tb

# MODULE / COCOTB_TEST_MODULES is the basename of the Python test file
MODULE ?= test
COCOTB_TEST_MODULES ?= $(MODULE)

# Include cocotb's make rules to take care of the simulator setup
include $(shell cocotb-config --makefiles)/Makefile.sim
