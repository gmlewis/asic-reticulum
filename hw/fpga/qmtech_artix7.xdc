# Physical Pin Constraints for QMTECH AMD/Xilinx Artix-7 (XC7A35T-1FTG256C)
# Connects to ESP32-C5 host via J1 PMOD/expansion header.

# 50 MHz Onboard System Oscillator
set_property -dict {PACKAGE_PIN M9 IOSTANDARD LVCMOS33} [get_ports clk]
create_clock -period 20.000 -name sys_clk [get_ports clk]

# Diagnostic LEDs
set_property -dict {PACKAGE_PIN J19 IOSTANDARD LVCMOS33} [get_ports io_led_heartbeat]
set_property -dict {PACKAGE_PIN H19 IOSTANDARD LVCMOS33} [get_ports io_led_busy]
set_property -dict {PACKAGE_PIN K17 IOSTANDARD LVCMOS33} [get_ports io_led_irq]

# 7-Pin QSPI Interface on J1 Expansion Header (3.3V)
set_property -dict {PACKAGE_PIN B12 IOSTANDARD LVCMOS33} [get_ports io_qspi_sclk]
set_property -dict {PACKAGE_PIN A12 IOSTANDARD LVCMOS33 PULLUP true} [get_ports io_qspi_cs_n]

set_property -dict {PACKAGE_PIN C12 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_in[0]}]
set_property -dict {PACKAGE_PIN C12 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_out[0]}]

set_property -dict {PACKAGE_PIN A13 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_in[1]}]
set_property -dict {PACKAGE_PIN A13 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_out[1]}]

set_property -dict {PACKAGE_PIN B14 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_in[2]}]
set_property -dict {PACKAGE_PIN B14 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_out[2]}]

set_property -dict {PACKAGE_PIN A14 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_in[3]}]
set_property -dict {PACKAGE_PIN A14 IOSTANDARD LVCMOS33} [get_ports {io_qspi_data_out[3]}]

# Dedicated Active-Low Interrupt to ESP32-C5 Host
set_property -dict {PACKAGE_PIN C14 IOSTANDARD LVCMOS33} [get_ports io_qspi_irq_n]

# Configuration Bank Voltage
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
