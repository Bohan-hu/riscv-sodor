package sodor.common

import chisel3._
import chisel3.util._

import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalTreeNode}
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{RocketCrossingParams}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.amba.axi4._

// Abstract core and tile base class for all cores
abstract class AbstractCore extends Module {
  val mem_ports: Seq[MemPortIo]
}
abstract class AbstractInternalTile(implicit val conf: SodorConfiguration) extends Module {
  val io = IO(new Bundle {
    val debug_port = Flipped(new MemPortIo(data_width = conf.debuglen))
    val master_port = Vec(2, new MemPortIo(data_width = conf.debuglen))
  })
}

// Cores and internal tiles constructors
trait SodorCoreFactory {
  def nMemPorts: Int
  def instantiate(implicit conf: SodorConfiguration): AbstractCore
}

trait SodorInternalTileFactory {
  def instantiate(range: AddressSet)(implicit conf: SodorConfiguration): AbstractInternalTile
}

// Original sodor tile in this repo.
// This tile is only for 3-stage core since it has a special structure (SyncMem and one memory port)
class SodorInternalTileStage3(range: AddressSet)(implicit conf: SodorConfiguration) extends AbstractInternalTile
{
  val procConst = {
    class C extends sodor.stage3.constants.SodorProcConstants
    new C
  }

  val core   = Module(new sodor.stage3.Core())
  core.io := DontCare
  val memory = Module(new SyncScratchPadMemory(num_core_ports = procConst.NUM_MEMORY_PORTS))

  if (procConst.NUM_MEMORY_PORTS == 1) // Only used in stage3
  {
    val arbiter = Module(new sodor.stage3.SodorMemArbiter) // only used for single port memory
    core.io.imem <> arbiter.io.imem
    core.io.dmem <> arbiter.io.dmem
    arbiter.io.mem <> io.master_port(0)
  }
  else
  {
    core.io.imem <> io.master_port(1)
    core.io.dmem <> io.master_port(0)
  }
  ((memory.io.core_ports zip io.master_port) zip io.master_port).foreach({ case ((mem_port, core_port), master_port) => {
    val router = Module(new SodorRequestRouter(range))
    router.io.corePort <> core_port
    router.io.scratchPort <> mem_port
    router.io.masterPort <> master_port
  }})

  memory.io.debug_port <> io.debug_port
}

// The general Sodor tile for all cores other than 3-stage
class SodorInternalTile(range: AddressSet, coreCtor: SodorCoreFactory)(implicit conf: SodorConfiguration) extends AbstractInternalTile
{
  // notice that while the core is put into reset, the scratchpad needs to be
  // alive so that the HTIF can load in the program.
  val core   = Module(coreCtor.instantiate)
  core.io := DontCare
  val memory = Module(new AsyncScratchPadMemory(num_core_ports = coreCtor.nMemPorts))

  val nMemPorts = coreCtor.nMemPorts
  ((memory.io.core_ports zip core.mem_ports) zip io.master_port).foreach({ case ((mem_port, core_port), master_port) => {
    val router = Module(new SodorRequestRouter(range))
    router.io.corePort <> core_port
    router.io.scratchPort <> mem_port
    router.io.masterPort <> master_port
  }})

  io.debug_port <> memory.io.debug_port
}

// Tile constructor
case object Stage1Factory extends SodorInternalTileFactory {
  case object Stage1CoreFactory extends SodorCoreFactory {
    val nMemPorts = 2
    def instantiate(implicit conf: SodorConfiguration) = new sodor.stage1.Core()(conf)
  }
  def instantiate(range: AddressSet)(implicit conf: SodorConfiguration) = new SodorInternalTile(range, Stage1CoreFactory)
}

case object Stage2Factory extends SodorInternalTileFactory {
  case object Stage2CoreFactory extends SodorCoreFactory {
    val nMemPorts = 2
    def instantiate(implicit conf: SodorConfiguration) = new sodor.stage2.Core()(conf)
  }
  def instantiate(range: AddressSet)(implicit conf: SodorConfiguration) = new SodorInternalTile(range, Stage2CoreFactory)
}

case object Stage3Factory extends SodorInternalTileFactory {
  def instantiate(range: AddressSet)(implicit conf: SodorConfiguration) = new SodorInternalTileStage3(range)
}

case object Stage5Factory extends SodorInternalTileFactory {
  case object Stage5CoreFactory extends SodorCoreFactory {
    val nMemPorts = 2
    def instantiate(implicit conf: SodorConfiguration) = new sodor.stage5.Core()(conf)
  }
  def instantiate(range: AddressSet)(implicit conf: SodorConfiguration) = new SodorInternalTile(range, Stage5CoreFactory)
}

case object UCodeFactory extends SodorInternalTileFactory {
  case object UCodeCoreFactory extends SodorCoreFactory {
    val nMemPorts = 1
    def instantiate(implicit conf: SodorConfiguration) = new sodor.ucode.Core()(conf)
  }
  def instantiate(range: AddressSet)(implicit conf: SodorConfiguration) = new SodorInternalTile(range, UCodeCoreFactory)
}
