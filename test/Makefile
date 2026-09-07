# Makefile for Tiny Tapeout cocotb simulation

SIM ?= icarus
TOPLEVEL_LANG ?= verilog
SRC_DIR ?= $(PWD)/../src

VERILOG_SOURCES += $(PWD)/tb.v
VERILOG_SOURCES += $(SRC_DIR)/tt_um_gmlewis_reticulum.v

# TOPLEVEL is the name of the toplevel module in your Verilog files
TOPLEVEL = tb

# MODULE is the basename of the Python test file
MODULE = test

# Include cocotb's make rules to take care of the simulator setup
include $(shell cocotb-config --makefiles)/Makefile.sim
