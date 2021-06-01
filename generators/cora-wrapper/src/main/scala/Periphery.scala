package cora

import chisel3._
import freechips.rocketchip.config.Field
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.diplomacy.{LazyModule,BufferParams}
import freechips.rocketchip.tilelink.{TLBuffer, TLIdentityNode}

case object CORAKey extends Field[Option[CORAParams]](None)
case object CORAFrontBusExtraBuffers extends Field[Int](0)

trait CanHavePeripheryCORA { this: BaseSubsystem =>
  p(CORAKey).map { params =>
    val cora = LazyModule(new CORADevice(params))

    fbus.fromMaster(name = Some("cora_noc"), buffer = BufferParams.default) {
      TLBuffer.chainNode(p(CORAFrontBusExtraBuffers))
    } := cora.noc_tl_node

    pbus.toFixedWidthSingleBeatSlave(4, Some("cora_cfg")) { cora.cfg_tl_node }

    ibus.fromSync := cora.int_node
  }
}
