package np.devices.sodla

import chisel3._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.subsystem._

import nvdla.NV_small
import nvdla.nvdlaConfig

case class SODLAParams(
  config: String,
  raddress: BigInt,
  synthRAMs: Boolean = false
)

class SODLA(params: SODLAParams)(implicit p: Parameters) extends LazyModule {
  // val blackboxName = "nvdla_" + params.config
  val hasSecondAXI = params.config == "small"
  val dataWidthAXI = if (params.config == "large") 256 else 64

  // DTS
  val dtsdevice = new SimpleDevice("sodla",Seq("nvidia,nv_" + params.config))

  // dbb TL
  val dbb_tl_node = TLIdentityNode()

  // dbb AXI
  val dbb_axi_node = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
          name    = "SODLA DBB",
          id      = IdRange(0, 256))))))

  // TL <-> AXI
  (dbb_tl_node
    := TLBuffer()
    := TLWidthWidget(dataWidthAXI/8)
    := AXI4ToTL()
    := AXI4UserYanker(capMaxFlight=Some(16))
    := AXI4Fragmenter()
    := AXI4IdIndexer(idBits=3)
    := AXI4Buffer()
    := dbb_axi_node)

  // cvsram AXI
  val cvsram_axi_node = if (hasSecondAXI) Some(AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
          name    = "SODLA CVSRAM",
          id      = IdRange(0, 256)))))))
  else None

  cvsram_axi_node.foreach {
    val sram = if (hasSecondAXI) Some(LazyModule(new AXI4RAM(
      address = AddressSet(0, 1*1024-1),
      beatBytes = dataWidthAXI/8)))
    else None
      sram.get.node := _
  }

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

    implicit val conf: nvdlaConfig = new nvdlaConfig
    val u_sodla = Module(new NV_small())
    u_sodla.io.core_clk    := clock
    u_sodla.io.rstn        := ~reset.asBool
    u_sodla.io.csb_rstn    := ~reset.asBool

    val (dbb, _) = dbb_axi_node.out(0)

    dbb.aw  <> u_sodla.io.nvdla_core2dbb_aw
    dbb.w   <> u_sodla.io.nvdla_core2dbb_w
    dbb.b   <> u_sodla.io.nvdla_core2dbb_b
    dbb.ar  <> u_sodla.io.nvdla_core2dbb_ar
    dbb.r   <> u_sodla.io.nvdla_core2dbb_r

    // 先不连通cvsram,等sodla主要逻辑调通后再打开
    // u_sodla.io.nvdla_core2cvsram.foreach { u_nvdla_cvsram =>
    //   val (cvsram, _) = cvsram_axi_node.get.out(0)
    //   cvsram.aw   <> u_nvdla_cvsram.nvdla_core2cvsram_aw
    //   cvsram.w    <> u_nvdla_cvsram.nvdla_core2cvsram_w
    //   cvsram.b    <> u_nvdla_cvsram.nvdla_core2cvsram_b
    //   cvsram.ar   <> u_nvdla_cvsram.nvdla_core2cvsram_ar
    //   cvsram.r    <> u_nvdla_cvsram.nvdla_core2cvsram_r
    // }

    val (cfg, _) = cfg_apb_node.in(0)

    u_sodla.io.psel         := cfg.psel
    u_sodla.io.penable      := cfg.penable
    u_sodla.io.pwrite       := cfg.pwrite
    u_sodla.io.paddr        := cfg.paddr
    u_sodla.io.pwdata       := cfg.pwdata
    cfg.prdata              := u_sodla.io.prdata
    cfg.pready              := u_sodla.io.pready
    cfg.pslverr             := false.B

    val (io_int, _) = int_node.out(0)

    io_int(0)   := u_sodla.io.dla_intr
  }
}


