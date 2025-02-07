#include "../../fpga_interface.h"

#include "asm_helpers.h"

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>

void initArchState(
  ArmflexArchState *state, 
  uint64_t pc
) {
  for (int i = 0; i < 32; ++i) {
    state->xregs[i] = i;
  }
  state->nzcv = 0;
  state->sp = 0;
  state->pc = pc;
}

void initState_pressure_ldp_stp(
  ArmflexArchState *state,
  uint64_t array_size,
  uint64_t array_ld_base_addr, 
  uint64_t array_st_base_addr
) {
  state->xregs[3] = array_ld_base_addr;
  state->xregs[4] = array_st_base_addr;
  state->xregs[5] = array_size;
}

void initState_set_flag(
  ArmflexArchState *state,
  uint64_t set_flag_addr,
  uint64_t get_flag_addr
) {
  state->xregs[2] = set_flag_addr;
  state->xregs[3] = get_flag_addr;
}

void initState_ldst_all_sizes_pair(
  ArmflexArchState *state,
  uint64_t mem_addr_ld,
  uint64_t mem_addr_st,
  uint64_t step_size
) {
  state->xregs[2] = mem_addr_ld;
  state->xregs[3] = mem_addr_st;
  state->xregs[4] = step_size;
};

void initState_infinite_loop(
  ArmflexArchState *state,
  bool loop
) {
  if(loop) {
    state->xregs[0] = 0;
  } else {
    state->xregs[0] = 1;
  }
};