package cora

import chisel3._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.subsystem._

case class CORAParams(
  config: String,
  raddress: BigInt,
)

class CORADevice(params: CORAParams)(implicit p: Parameters) extends LazyModule {
  val dataWidthAXI = 64
  // DTS
  val dtsdevice = new SimpleDevice("cora",Seq("cora,cora_" + params.config))

  // noc TL
  val noc_tl_node = TLIdentityNode()

  // noc AXI
  val noc_axi_node = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
          name    = "CORA NoC",
          id      = IdRange(0, 256))))))

  // TL <-> AXI
  (noc_tl_node
    := TLBuffer()
    := TLWidthWidget(dataWidthAXI/8)
    := AXI4ToTL()
    := AXI4UserYanker(capMaxFlight=Some(16))
    := AXI4Fragmenter()
    := AXI4IdIndexer(idBits=3)
    := AXI4Buffer()
    := noc_axi_node)

  // cfg APB
  val cfg_apb_node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        slaves = Seq(APBSlaveParameters(
          address       = Seq(AddressSet(params.raddress, 0x40000L-1L)), // 256KB
          resources     = dtsdevice.reg("control"),
          executable    = false,
          supportsWrite = true,
          supportsRead  = true)),
        beatBytes = 4)))

  val cfg_tl_node = cfg_apb_node := LazyModule(new TLToAPB).node

  val int_node = IntSourceNode(IntSourcePortSimple(num = 1, resources = dtsdevice.int))

  lazy val module = new LazyModuleImp(this) {

    val conf: coraConfig = new coraConfig
    val u_cora = Module(new CORA_top()(conf))
    u_cora.io.cora_core_clk     := clock
    u_cora.io.cora_csb_clk      := clock
    u_cora.io.global_clk_ovr_on := clock

    u_cora.io.tmc2slcg_disable_clock_gating := false.B
    u_cora.io.cora_reset_rstn  := ~reset.asBool
    u_cora.io.direct_reset_    := false.B
    u_cora.io.test_mode        := false.B

    val (noc, _) = noc_axi_node.out(0)

    noc.ar.valid     <> u_cora.io.mcif2noc_axi_ar.valid
    noc.ar.ready     <> u_cora.io.mcif2noc_axi_ar.ready
    noc.ar.bits.id   <> u_cora.io.mcif2noc_axi_ar.bits.id
    noc.ar.bits.len  <> u_cora.io.mcif2noc_axi_ar.bits.len
    noc.ar.bits.size <> u_cora.io.mcif2noc_axi_ar_size
    noc.ar.bits.addr <> u_cora.io.mcif2noc_axi_ar.bits.addr

    noc.aw.valid     <> u_cora.io.mcif2noc_axi_aw.valid
    noc.aw.ready     <> u_cora.io.mcif2noc_axi_aw.ready
    noc.aw.bits.id   <> u_cora.io.mcif2noc_axi_aw.bits.id
    noc.aw.bits.len  <> u_cora.io.mcif2noc_axi_aw.bits.len
    noc.aw.bits.size <> u_cora.io.mcif2noc_axi_aw_size
    noc.aw.bits.addr <> u_cora.io.mcif2noc_axi_aw.bits.addr

    noc.w <> u_cora.io.mcif2noc_axi_w

    u_cora.io.noc2mcif_axi_b  <> noc.b
    u_cora.io.noc2mcif_axi_r  <> noc.r

    val (cfg, _) = cfg_apb_node.in(0)

    u_cora.io.psel    <> cfg.psel
    u_cora.io.penable <> cfg.penable
    u_cora.io.pwrite  <> cfg.pwrite
    u_cora.io.paddr   <> cfg.paddr
    u_cora.io.pwdata  <> cfg.pwdata
    u_cora.io.prdata  <> cfg.prdata
    u_cora.io.pready  <> cfg.pready

    cfg.pslverr := false.B

    val (io_int, _) = int_node.out(0)

    io_int(0)   := u_cora.io.cora_intr

    // TODO
    u_cora.io.cora_pwrbus_ram_d_pd <> DontCare
    u_cora.io.cora_pwrbus_ram_o_pd <> DontCare
  }
}


