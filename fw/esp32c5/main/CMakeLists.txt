# Copyright 2026 Glenn Lewis. All rights reserved.
# Use of this source code is governed by the BSD-style
# license that can be found in the LICENSE file.

idf_component_register(SRCS "main.c" "asic_qspi.c"
                       INCLUDE_DIRS "."
                       REQUIRES driver esp_timer freertos)
