package cora

import chisel3._

import freechips.rocketchip.config.{Field, Parameters, Config}

/**
  * Config fragment to add a SODLA to the SoC.
  * Supports "small" and "large" configs only.
  * Can enable synth. RAMs instead of default FPGA RAMs.
  */
class WithCORA(config: String) extends Config((site, here, up) => {
  case CORAKey => Some(CORAParams(config = config, raddress = 0x10040000L))
  case CORAFrontBusExtraBuffers => 0
})
