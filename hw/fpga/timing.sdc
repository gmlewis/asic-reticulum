# Timing Constraints for Reticulum Cryptographic Accelerator FPGA Target
# Compatible with Gowin EDA, Synopsys Design Compiler, and Yosys/nextpnr

# Primary System Clock (50 MHz)
create_clock -period 20.000 -name sys_clk [get_ports clk]

# Asynchronous QSPI Host Clock (up to 80 MHz)
create_clock -period 12.500 -name qspi_clk [get_ports io_qspi_sclk]

# Clock domain crossing false paths between host SPI clock and core FPGA clock
set_false_path -from [get_clocks sys_clk] -to [get_clocks qspi_clk]
set_false_path -from [get_clocks qspi_clk] -to [get_clocks sys_clk]

# Input / Output delay margins for QSPI interface (3.3V LVCMOS)
set_input_delay -clock qspi_clk -max 4.000 [get_ports {io_qspi_cs_n io_qspi_data_in*}]
set_input_delay -clock qspi_clk -min 1.000 [get_ports {io_qspi_cs_n io_qspi_data_in*}]

set_output_delay -clock qspi_clk -max 4.000 [get_ports {io_qspi_data_out* io_qspi_irq_n}]
set_output_delay -clock qspi_clk -min 1.000 [get_ports {io_qspi_data_out* io_qspi_irq_n}]
