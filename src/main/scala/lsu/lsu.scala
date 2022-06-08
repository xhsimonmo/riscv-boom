//******************************************************************************
// Copyright (c) 2012 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Out-of-Order Load/Store Unit
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Load/Store Unit is made up of the Load-Address Queue, the Store-Address
// Queue, and the Store-Data queue (LAQ, SAQ, and SDQ).
//
// Stores are sent to memory at (well, after) commit, loads are executed
// optimstically ASAP.  If a misspeculation was discovered, the pipeline is
// cleared. Loads put to sleep are retried.  If a LoadAddr and StoreAddr match,
// the Load can receive its data by forwarding data out of the Store-Data
// Queue.
//
// Currently, loads are sent to memory immediately, and in parallel do an
// associative search of the SAQ, on entering the LSU. If a hit on the SAQ
// search, the memory request is killed on the next cycle, and if the SDQ entry
// is valid, the store data is forwarded to the load (delayed to match the
// load-use delay to delay with the write-port structural hazard). If the store
// data is not present, or it's only a partial match (SB->LH), the load is put
// to sleep in the LAQ.
//
// Memory ordering violations are detected by stores at their addr-gen time by
// associatively searching the LAQ for newer loads that have been issued to
// memory.
//
// The store queue contains both speculated and committed stores.
//
// Only one port to memory... loads and stores have to fight for it, West Side
// Story style.
//
// TODO:
//    - Add predicting structure for ordering failures
//    - currently won't STD forward if DMEM is busy
//    - ability to turn off things if VM is disabled
//    - reconsider port count of the wakeup, retry stuff

package boom.lsu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.rocket
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Str

import boom.common._
import boom.exu.{BrUpdateInfo, Exception, FuncUnitResp, CommitSignals, ExeUnitResp}
import boom.util.{BoolToChar, AgePriorityEncoder, IsKilledByBranch, GetNewBrMask, WrapInc, IsOlder, UpdateBrMask}

class LSUExeIO(implicit p: Parameters) extends BoomBundle()(p)
{
  // The "resp" of the maddrcalc is really a "req" to the LSU
  val req       = Flipped(new ValidIO(new FuncUnitResp(xLen)))
  // Send load data to regfiles
  val iresp    = new DecoupledIO(new boom.exu.ExeUnitResp(xLen))
  val fresp    = new DecoupledIO(new boom.exu.ExeUnitResp(xLen+1)) // TODO: Should this be fLen?
}

class BoomDCacheReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val addr  = UInt(coreMaxAddrBits.W)
  val data  = Bits(coreDataBits.W)
  val is_hella = Bool() // Is this the hellacache req? If so this is not tracked in LDQ or STQ
}

class BoomDCacheResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val data = Bits(coreDataBits.W)
  val is_hella = Bool()
}

class LSUDMemIO(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p)
{
  // In LSU's dmem stage, send the request
  val req         = new DecoupledIO(Vec(memWidth, Valid(new BoomDCacheReq)))
  // In LSU's LCAM search stage, kill if order fail (or forwarding possible)
  val s1_kill     = Output(Vec(memWidth, Bool()))
  // Get a request any cycle
  val resp        = Flipped(Vec(memWidth, new ValidIO(new BoomDCacheResp)))
  // In our response stage, if we get a nack, we need to reexecute
  val nack        = Flipped(Vec(memWidth, new ValidIO(new BoomDCacheReq)))

  val brupdate       = Output(new BrUpdateInfo)
  val exception    = Output(Bool())
  val rob_pnr_idx  = Output(UInt(robAddrSz.W))
  val rob_head_idx = Output(UInt(robAddrSz.W))

  val release = Flipped(new DecoupledIO(new TLBundleC(edge.bundle)))

  // Clears prefetching MSHRs
  val force_order  = Output(Bool())
  val ordered     = Input(Bool())

  val perf = Input(new Bundle {
    val acquire = Bool()
    val release = Bool()
  })

}

class LSUCoreIO(implicit p: Parameters) extends BoomBundle()(p)
{
  val exe = Vec(memWidth, new LSUExeIO)

  val dis_uops    = Flipped(Vec(coreWidth, Valid(new MicroOp)))
  // val dis_ldq_idx = Output(Vec(coreWidth, UInt(ldqAddrSz.W)))
  // val dis_stq_idx = Output(Vec(coreWidth, UInt(stqAddrSz.W)))

  //SM: dispatch index for buffer
  val dis_buf_idx = Output(Vec(coreWidth, UInt(stqAddrSz.W)))

  val ldq_full    = Output(Vec(coreWidth, Bool()))
  val stq_full    = Output(Vec(coreWidth, Bool()))

  val fp_stdata   = Flipped(Decoupled(new ExeUnitResp(fLen)))

  val commit      = Input(new CommitSignals)
  val commit_load_at_rob_head = Input(Bool())

  // Stores clear busy bit when stdata is received
  // memWidth for int, 1 for fp (to avoid back-pressure fpstdat)
  val clr_bsy         = Output(Vec(memWidth + 1, Valid(UInt(robAddrSz.W))))

  // Speculatively safe load (barring memory ordering failure)
  val clr_unsafe      = Output(Vec(memWidth, Valid(UInt(robAddrSz.W))))

  // Tell the DCache to clear prefetches/speculating misses
  val fence_dmem   = Input(Bool())

  // Speculatively tell the IQs that we'll get load data back next cycle
  val spec_ld_wakeup = Output(Vec(memWidth, Valid(UInt(maxPregSz.W))))
  // Tell the IQs that the load we speculated last cycle was misspeculated
  val ld_miss      = Output(Bool())

  val brupdate       = Input(new BrUpdateInfo)
  val rob_pnr_idx  = Input(UInt(robAddrSz.W))
  val rob_head_idx = Input(UInt(robAddrSz.W))
  val exception    = Input(Bool())

  val fencei_rdy  = Output(Bool())

  val lxcpt       = Output(Valid(new Exception))

  val tsc_reg     = Input(UInt())

  val perf        = Output(new Bundle {
    val acquire = Bool()
    val release = Bool()
    val tlbMiss = Bool()
  })
}

class LSUIO(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p)
{
  val ptw   = new rocket.TLBPTWIO
  val core  = new LSUCoreIO
  val dmem  = new LSUDMemIO

  val hellacache = Flipped(new freechips.rocketchip.rocket.HellaCacheIO)
}

class LDQEntry(implicit p: Parameters) extends BoomBundle()(p)
    with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val addr_is_uncacheable = Bool() // Uncacheable, wait until head of ROB to execute

  val executed            = Bool() // load sent to memory, reset by NACKs
  val succeeded           = Bool()
  val order_fail          = Bool()
  val observed            = Bool()

  val st_dep_mask         = UInt(numStqEntries.W) // list of stores older than us
  val youngest_stq_idx    = UInt(stqAddrSz.W) // index of the oldest store younger than us

  val forward_std_val     = Bool()
  val forward_stq_idx     = UInt(stqAddrSz.W) // Which store did we get the store-load forward from?

  val debug_wb_data       = UInt(xLen.W)
}

class STQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val data                = Valid(UInt(xLen.W))

  val committed           = Bool() // committed by ROB
  val succeeded           = Bool() // D$ has ack'd this, we don't need to maintain this anymore

  val debug_wb_data       = UInt(xLen.W)
}

//SM: the buffer to hold both load and store in issue order
class BufferEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val is_ld = Bool() //each valid entry is either load or store
  val ld = Valid(new LDQEntry)
  val st = Valid(new STQEntry)
}

class LSU(implicit p: Parameters, edge: TLEdgeOut) extends BoomModule()(p)
  with rocket.HasL1HellaCacheParameters
{
  val io = IO(new LSUIO)


  // val ldq = Reg(Vec(numLdqEntries, Valid(new LDQEntry)))
  // val stq = Reg(Vec(numStqEntries, Valid(new STQEntry)))
  val buf = Reg(Vec(numStqEntries, Valid(new BufferEntry)))

  val ldq_head         = Reg(UInt(ldqAddrSz.W))
  val ldq_tail         = Reg(UInt(ldqAddrSz.W))
  val stq_head         = Reg(UInt(stqAddrSz.W)) // point to next store to clear from STQ (i.e., send to memory)
  val stq_tail         = Reg(UInt(stqAddrSz.W))
  val stq_commit_head  = Reg(UInt(stqAddrSz.W)) // point to next store to commit
  val stq_execute_head = Reg(UInt(stqAddrSz.W)) // point to next store to execute

  val buf_head         = Reg(UInt(ldqAddrSz.W))
  val buf_tail         = Reg(UInt(ldqAddrSz.W))

  // If we got a mispredict, the tail will be misaligned for 1 extra cycle
  // assert (io.core.brupdate.b2.mispredict ||
  //         buf(stq_execute_head).bits.st.valid ||
  //         stq_head === stq_execute_head ||
  //         stq_tail === stq_execute_head,
  //           "stq_execute_head got off track.")
  

  val h_ready :: h_s1 :: h_s2 :: h_s2_nack :: h_wait :: h_replay :: h_dead :: Nil = Enum(7)
  // s1 : do TLB, if success and not killed, fire request go to h_s2
  //      store s1_data to register
  //      if tlb miss, go to s2_nack
  //      if don't get TLB, go to s2_nack
  //      store tlb xcpt
  // s2 : If kill, go to dead
  //      If tlb xcpt, send tlb xcpt, go to dead
  // s2_nack : send nack, go to dead
  // wait : wait for response, if nack, go to replay
  // replay : refire request, use already translated address
  // dead : wait for response, ignore it
  val hella_state           = RegInit(h_ready)
  val hella_req             = Reg(new rocket.HellaCacheReq)
  val hella_data            = Reg(new rocket.HellaCacheWriteData)
  val hella_paddr           = Reg(UInt(paddrBits.W))
  val hella_xcpt            = Reg(new rocket.HellaCacheExceptions)


  val dtlb = Module(new NBDTLB(
    instruction = false, lgMaxSize = log2Ceil(coreDataBytes), rocket.TLBConfig(dcacheParams.nTLBSets, dcacheParams.nTLBWays)))

  io.ptw <> dtlb.io.ptw
  io.core.perf.tlbMiss := io.ptw.req.fire
  io.core.perf.acquire := io.dmem.perf.acquire
  io.core.perf.release := io.dmem.perf.release


  val clear_store     = WireInit(false.B)
  // val live_store_mask = RegInit(0.U(numStqEntries.W))
  // var next_live_store_mask = Mux(clear_store, live_store_mask & ~(1.U << stq_head),
  //                                             live_store_mask)


  def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Enqueue new entries
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // This is a newer store than existing loads, so clear the bit in all the store dependency masks
  // for (i <- 0 until numLdqEntries)
  // {
  //   when (clear_store)
  //   {
  //     ldq(i).bits.st_dep_mask := ldq(i).bits.st_dep_mask & ~(1.U << stq_head)
  //   }
  // }

  // Decode stage
  //SM:
  var ld_enq_idx = buf_tail
  var st_enq_idx = buf_tail
  var buf_enq_idx = buf_tail

  // val stq_nonempty = (0 until numStqEntries).map{ i => stq(i).valid }.reduce(_||_) =/= 0.U
  val buf_nonempty = (0 until numStqEntries).map{ i => buf(i).valid }.reduce(_||_) =/= 0.U

  var ldq_full = Bool()
  var stq_full = Bool()

  // var buf_full = Bool()

  for (w <- 0 until coreWidth)
  {
    ldq_full = WrapInc(ld_enq_idx, numLdqEntries) === ldq_head
    io.core.ldq_full(w)    := ldq_full
    // io.core.dis_ldq_idx(w) := ld_enq_idx

    stq_full = WrapInc(st_enq_idx, numStqEntries) === stq_head
    io.core.stq_full(w)    := stq_full
    // io.core.dis_stq_idx(w) := st_enq_idx

    // buf_full = WrapInc(buf_enq_idx, numLdqEntries) === buf_head
    // io.core.dis_ldq_idx(w) := buf_enq_idx
    // io.core.dis_stq_idx(w) := buf_enq_idx
    io.core.dis_buf_idx(w) := buf_enq_idx

    val dis_ld_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.uses_ldq && !io.core.dis_uops(w).bits.exception
    val dis_st_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.uses_stq && !io.core.dis_uops(w).bits.exception
    when (dis_ld_val)
    {
      printf("Line 327: enqueue load value with enq_idx being %d.\n", buf_enq_idx)
      buf(buf_enq_idx).valid                        := true.B 
      buf(buf_enq_idx).bits.is_ld                   := true.B 
      buf(buf_enq_idx).bits.ld.valid                := true.B
      buf(buf_enq_idx).bits.ld.bits.uop             := io.core.dis_uops(w).bits
      // buf(buf_enq_idx).bits.ld.bits.youngest_stq_idx  := buf_enq_idx
      // buf(buf_enq_idx).bits.ld.bits.st_dep_mask     := next_live_store_mask

      buf(buf_enq_idx).bits.ld.bits.addr.valid      := false.B
      buf(buf_enq_idx).bits.ld.bits.executed        := false.B
      buf(buf_enq_idx).bits.ld.bits.succeeded       := false.B
      buf(buf_enq_idx).bits.ld.bits.order_fail      := false.B
      buf(buf_enq_idx).bits.ld.bits.observed        := false.B
      buf(buf_enq_idx).bits.ld.bits.forward_std_val := false.B

      // assert (buf_enq_idx === io.core.dis_uops(w).bits.ldq_idx, "[lsu] mismatch enq load tag.")
      // assert (!buf(buf_enq_idx).bits.ld.valid, "[lsu] Enqueuing uop is overwriting ldq entries")
      assert (!buf(buf_enq_idx).valid, "[lsu] Enqueuing uop is overwriting ldq entries")
    }
      .elsewhen (dis_st_val)
    {
      printf("Line 347: enqueue store value with enq_idx being %d.\n", buf_enq_idx)
      buf(buf_enq_idx).valid                   := true.B 
      buf(buf_enq_idx).bits.is_ld              := false.B 
      buf(buf_enq_idx).bits.st.valid           := true.B
      buf(buf_enq_idx).bits.st.bits.uop        := io.core.dis_uops(w).bits
      buf(buf_enq_idx).bits.st.bits.addr.valid := false.B
      buf(buf_enq_idx).bits.st.bits.data.valid := false.B
      buf(buf_enq_idx).bits.st.bits.committed  := false.B
      buf(buf_enq_idx).bits.st.bits.succeeded  := false.B

      // assert (st_enq_idx === io.core.dis_uops(w).bits.stq_idx, "[lsu] mismatch enq store tag.")
      // assert (!buf(buf_enq_idx).bits.st.valid, "[lsu] Enqueuing uop is overwriting stq entries")
      assert (!buf(buf_enq_idx).valid, "[lsu] Enqueuing uop is overwriting stq entries")
    }

    ld_enq_idx = Mux(dis_ld_val, WrapInc(ld_enq_idx, numLdqEntries),
                                 ld_enq_idx)
    // printf("ld_enq_idx is set to %d \n", ld_enq_idx)

    // next_live_store_mask = Mux(dis_st_val, next_live_store_mask | (1.U << st_enq_idx),
    //                                        next_live_store_mask)
    st_enq_idx = Mux(dis_st_val, WrapInc(st_enq_idx, numStqEntries),
                                 st_enq_idx)
    // printf("st_enq_idx is set to %d \n", st_enq_idx)

    buf_enq_idx = Mux(dis_st_val|dis_ld_val, WrapInc(buf_enq_idx, numLdqEntries),
                                buf_enq_idx)
                                
    assert(!(dis_ld_val && dis_st_val), "A UOP is trying to go into both the LDQ and the STQ")
  }

  // ldq_tail := ld_enq_idx
  // stq_tail := st_enq_idx
  buf_tail := buf_enq_idx
  // printf("buf_enq_idx and buf_tail is set to %d \n", buf_tail)

  io.dmem.force_order   := io.core.fence_dmem
  io.core.fencei_rdy    := !buf_nonempty && io.dmem.ordered


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Execute stage (access TLB, send requests to Memory)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // We can only report 1 exception per cycle.
  // Just be sure to report the youngest one
  val mem_xcpt_valid  = Wire(Bool())
  val mem_xcpt_cause  = Wire(UInt())
  val mem_xcpt_uop    = Wire(new MicroOp)
  val mem_xcpt_vaddr  = Wire(UInt())


  //---------------------------------------
  // Can-fire logic and wakeup/retry select
  //
  // First we determine what operations are waiting to execute.
  // These are the "can_fire"/"will_fire" signals

  val will_fire_load_incoming  = Wire(Vec(memWidth, Bool()))
  val will_fire_stad_incoming  = Wire(Vec(memWidth, Bool()))
  val will_fire_sta_incoming   = Wire(Vec(memWidth, Bool()))
  val will_fire_std_incoming   = Wire(Vec(memWidth, Bool()))
  val will_fire_sfence         = Wire(Vec(memWidth, Bool()))
  val will_fire_hella_incoming = Wire(Vec(memWidth, Bool()))
  val will_fire_hella_wakeup   = Wire(Vec(memWidth, Bool()))
  val will_fire_release        = Wire(Vec(memWidth, Bool()))
  val will_fire_load_retry     = Wire(Vec(memWidth, Bool()))
  val will_fire_sta_retry      = Wire(Vec(memWidth, Bool()))
  val will_fire_store_commit   = Wire(Vec(memWidth, Bool()))
  val will_fire_load_wakeup    = Wire(Vec(memWidth, Bool()))

  val exe_req = WireInit(VecInit(io.core.exe.map(_.req)))
  // Sfence goes through all pipes
  for (i <- 0 until memWidth) {
    when (io.core.exe(i).req.bits.sfence.valid) {
      exe_req := VecInit(Seq.fill(memWidth) { io.core.exe(i).req })
    }
  }

  // -------------------------------
  // Assorted signals for scheduling

  // Don't wakeup a load if we just sent it last cycle or two cycles ago
  // The block_load_mask may be wrong, but the executing_load mask must be accurate
  val block_load_mask    = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B)))
  val p1_block_load_mask = RegNext(block_load_mask)
  val p2_block_load_mask = RegNext(p1_block_load_mask)

 // Prioritize emptying the store queue when it is almost full
  // val stq_almost_full = RegNext(WrapInc(WrapInc(st_enq_idx, numStqEntries), numStqEntries) === stq_head ||
  //                               WrapInc(st_enq_idx, numStqEntries) === stq_head)

  // The store at the commit head needs the DCache to appear ordered
  // Delay firing load wakeups and retries now
  // val store_needs_order = WireInit(false.B)

  val ldq_incoming_idx = widthMap(i => exe_req(i).bits.uop.buf_idx)
  val ldq_incoming_e   = widthMap(i => buf(ldq_incoming_idx(i)).bits.ld)

  val stq_incoming_idx = widthMap(i => exe_req(i).bits.uop.buf_idx)
  val stq_incoming_e   = widthMap(i => buf(stq_incoming_idx(i)).bits.st)
  val buf_incoming_idx = widthMap(i => exe_req(i).bits.uop.buf_idx)
  val buf_incoming_e   = widthMap(i => buf(buf_incoming_idx(i)))

  val ldq_retry_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i => {
    val e = buf(i).bits.ld.bits
    val block = block_load_mask(i) || p1_block_load_mask(i)
    e.addr.valid && e.addr_is_virtual && !block && buf(i).bits.is_ld
  }), ldq_head))

  //SM: if a load needs to retry, it must be the one at queue head
  val ldq_retry_e            = buf(ldq_retry_idx).bits.ld

  val stq_retry_idx = RegNext(AgePriorityEncoder((0 until numStqEntries).map(i => {
    val e = buf(i).bits.st.bits
    e.addr.valid && e.addr_is_virtual && !buf(i).bits.is_ld
  }), stq_commit_head))

  //SM: if a store needs to retry, it must be the one at queue head
  // val stq_retry_e   = stq(stq_retry_idx)
  // val stq_commit_e  = stq(stq_execute_head)

  val stq_retry_e   = buf(stq_retry_idx).bits.st

  val stq_commit_e  = buf(stq_execute_head).bits.st

  val ldq_wakeup_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i=> {
    val e = buf(i).bits.ld.bits
    val block = block_load_mask(i) || p1_block_load_mask(i)
    e.addr.valid && !e.executed && !e.succeeded && !e.addr_is_virtual && !block && buf(i).bits.is_ld
  }), ldq_head))

  val ldq_wakeup_e   = buf(ldq_wakeup_idx).bits.ld

  // -----------------------
  // Determine what can fire

  // Can we fire a incoming load
  val can_fire_load_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_load)

  // Can we fire an incoming store addrgen + store datagen
  val can_fire_stad_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
                                                              && exe_req(w).bits.uop.ctrl.is_std)

  // Can we fire an incoming store addrgen
  val can_fire_sta_incoming  = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
                                                              && !exe_req(w).bits.uop.ctrl.is_std)

  // Can we fire an incoming store datagen
  val can_fire_std_incoming  = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_std
                                                              && !exe_req(w).bits.uop.ctrl.is_sta)

  // Can we fire an incoming sfence
  val can_fire_sfence        = widthMap(w => exe_req(w).valid && exe_req(w).bits.sfence.valid)

  // Can we fire a request from dcache to release a line
  // This needs to go through LDQ search to mark loads as dangerous
  val can_fire_release       = widthMap(w => (w == memWidth-1).B && io.dmem.release.valid)
  io.dmem.release.ready     := will_fire_release.reduce(_||_)

  // Can we retry a load that missed in the TLB
  val can_fire_load_retry    = widthMap(w =>
                               ( 
                                //  buf(buf_head).bits.is_ld                     &&
                                  buf(ldq_retry_idx).valid                    &&
                                  buf(ldq_retry_idx).bits.is_ld               &&
                                 ldq_retry_e.valid                            &&
                                 ldq_retry_e.bits.addr.valid                  &&
                                 ldq_retry_e.bits.addr_is_virtual             &&
                                !p1_block_load_mask(ldq_retry_idx)            &&
                                !p2_block_load_mask(ldq_retry_idx)            &&
                                RegNext(dtlb.io.miss_rdy)                     &&
                                // !store_needs_order                            &&
                                (w == memWidth-1).B                           && // TODO: Is this best scheduling?
                                !ldq_retry_e.bits.order_fail))

  // Can we retry a store addrgen that missed in the TLB
  // - Weird edge case when sta_retry and std_incoming for same entry in same cycle. Delay this
  val can_fire_sta_retry     = widthMap(w =>
                               ( !buf(buf_head).bits.is_ld                    &&
                                 stq_retry_e.valid                            &&
                                 stq_retry_e.bits.addr.valid                  &&
                                 stq_retry_e.bits.addr_is_virtual             &&
                                 (w == memWidth-1).B                          &&
                                 RegNext(dtlb.io.miss_rdy)                    &&
                                 !(widthMap(i => (i != w).B               &&
                                                 can_fire_std_incoming(i) &&
                                                 stq_incoming_idx(i) === stq_retry_idx).reduce(_||_))
                               ))
  // Can we commit a store
  val can_fire_store_commit  = widthMap(w =>
                               ( !buf(buf_head).bits.is_ld                    &&
                                 stq_commit_e.valid                           &&
                                !stq_commit_e.bits.uop.is_fence               &&
                                !mem_xcpt_valid                               &&
                                !stq_commit_e.bits.uop.exception              &&
                                (w == 0).B                                    &&
                                (stq_commit_e.bits.committed || ( stq_commit_e.bits.uop.is_amo      &&
                                                                  stq_commit_e.bits.addr.valid      &&
                                                                 !stq_commit_e.bits.addr_is_virtual &&
                                                                  stq_commit_e.bits.data.valid))))

  // Can we wakeup a load that was nack'd
  val block_load_wakeup = WireInit(false.B)
  //SM: change the 
  val can_fire_load_wakeup = widthMap(w =>
                             ( buf(buf_head).bits.is_ld                                &&
                               ldq_wakeup_e.valid                                      &&
                               ldq_wakeup_e.bits.addr.valid                            &&
                              !ldq_wakeup_e.bits.succeeded                             &&
                              !ldq_wakeup_e.bits.addr_is_virtual                       &&
                              !ldq_wakeup_e.bits.executed                              &&
                              !ldq_wakeup_e.bits.order_fail                            &&
                              !p1_block_load_mask(ldq_wakeup_idx)                      &&
                              !p2_block_load_mask(ldq_wakeup_idx)                      &&
                              // !store_needs_order                                       &&
                              !block_load_wakeup                                       &&
                              (w == memWidth-1).B                                      &&
                              (!ldq_wakeup_e.bits.addr_is_uncacheable || (io.core.commit_load_at_rob_head &&
                                                                          ldq_head === ldq_wakeup_idx 
                                                                          ))))

  // Can we fire an incoming hellacache request
  val can_fire_hella_incoming  = WireInit(widthMap(w => false.B)) // This is assigned to in the hellashim ocntroller

  // Can we fire a hellacache request that the dcache nack'd
  val can_fire_hella_wakeup    = WireInit(widthMap(w => false.B)) // This is assigned to in the hellashim controller

  //SM: load and store process in order, no need to decide any precedence

  //---------------------------------------------------------
  // Controller logic. Arbitrate which request actually fires

  val exe_tlb_valid = Wire(Vec(memWidth, Bool()))
  for (w <- 0 until memWidth) {
    var tlb_avail  = true.B
    var dc_avail   = true.B
    var lcam_avail = true.B
    var rob_avail  = true.B

    def lsu_sched(can_fire: Bool, uses_tlb:Boolean, uses_dc:Boolean, uses_lcam: Boolean, uses_rob:Boolean): Bool = {
      val will_fire = can_fire && !(uses_tlb.B && !tlb_avail) &&
                                  !(uses_lcam.B && !lcam_avail) &&
                                  !(uses_dc.B && !dc_avail) &&
                                  !(uses_rob.B && !rob_avail)
      tlb_avail  = tlb_avail  && !(will_fire && uses_tlb.B)
      lcam_avail = lcam_avail && !(will_fire && uses_lcam.B)
      dc_avail   = dc_avail   && !(will_fire && uses_dc.B)
      rob_avail  = rob_avail  && !(will_fire && uses_rob.B)
      dontTouch(will_fire) // dontTouch these so we can inspect the will_fire signals
      will_fire
    }

    // The order of these statements is the priority
    // Some restrictions
    //  - Incoming ops must get precedence, can't backpresure memaddrgen
    //  - Incoming hellacache ops must get precedence over retrying ops (PTW must get precedence over retrying translation)
    // Notes on performance
    //  - Prioritize releases, this speeds up cache line writebacks and refills
    //  - Store commits are lowest priority, since they don't "block" younger instructions unless stq fills up
    will_fire_load_incoming (w) := lsu_sched(can_fire_load_incoming (w) , true , true , true , false) // TLB , DC , LCAM
    will_fire_stad_incoming (w) := lsu_sched(can_fire_stad_incoming (w) , true , false, true , true)  // TLB ,    , LCAM , ROB
    will_fire_sta_incoming  (w) := lsu_sched(can_fire_sta_incoming  (w) , true , false, true , true)  // TLB ,    , LCAM , ROB
    will_fire_std_incoming  (w) := lsu_sched(can_fire_std_incoming  (w) , false, false, false, true)  //                 , ROB
    will_fire_sfence        (w) := lsu_sched(can_fire_sfence        (w) , true , false, false, true)  // TLB ,    ,      , ROB
    will_fire_release       (w) := lsu_sched(can_fire_release       (w) , false, false, true , false) //            LCAM
    will_fire_hella_incoming(w) := lsu_sched(can_fire_hella_incoming(w) , true , true , false, false) // TLB , DC
    will_fire_hella_wakeup  (w) := lsu_sched(can_fire_hella_wakeup  (w) , false, true , false, false) //     , DC
    will_fire_load_retry    (w) := lsu_sched(can_fire_load_retry    (w) , true , true , true , false) // TLB , DC , LCAM
    will_fire_sta_retry     (w) := lsu_sched(can_fire_sta_retry     (w) , true , false, true , true)  // TLB ,    , LCAM , ROB // TODO: This should be higher priority
    will_fire_load_wakeup   (w) := lsu_sched(can_fire_load_wakeup   (w) , false, true , true , false) //     , DC , LCAM1
    will_fire_store_commit  (w) := lsu_sched(can_fire_store_commit  (w) , false, true , false, false) //     , DC


    assert(!(exe_req(w).valid && !(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) || will_fire_std_incoming(w) || will_fire_sfence(w))))

    when (will_fire_load_wakeup(w)) {
      block_load_mask(ldq_wakeup_idx)           := true.B
    } .elsewhen (will_fire_load_incoming(w)) {
      block_load_mask(exe_req(w).bits.uop.ldq_idx) := true.B
    } .elsewhen (will_fire_load_retry(w)) {
      block_load_mask(ldq_retry_idx)            := true.B
    }
    exe_tlb_valid(w) := !tlb_avail
  }
  // assert((memWidth == 1).B ||
  //   (!(will_fire_sfence.reduce(_||_) && !will_fire_sfence.reduce(_&&_)) &&
  //    !will_fire_hella_incoming.reduce(_&&_) &&
  //    !will_fire_hella_wakeup.reduce(_&&_)   &&
  //    !will_fire_load_retry.reduce(_&&_)     &&
  //    !will_fire_sta_retry.reduce(_&&_)      &&
  //    !will_fire_store_commit.reduce(_&&_)   &&
  //    !will_fire_load_wakeup.reduce(_&&_)),
  //   "Some operations is proceeding down multiple pipes")

  require(memWidth <= 2)

  //--------------------------------------------
  // TLB Access

  assert(!(hella_state =/= h_ready && hella_req.cmd === rocket.M_SFENCE),
    "SFENCE through hella interface not supported")

  val exe_tlb_uop = widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w) ||
                        will_fire_sfence        (w)  , exe_req(w).bits.uop,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.uop,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.uop,
                    Mux(will_fire_hella_incoming(w)  , NullMicroOp,
                                                       NullMicroOp)))))

  val exe_tlb_vaddr = widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w)  , exe_req(w).bits.addr,
                    Mux(will_fire_sfence        (w)  , exe_req(w).bits.sfence.bits.addr,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.addr.bits,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.addr.bits,
                    Mux(will_fire_hella_incoming(w)  , hella_req.addr,
                                                       0.U))))))

  val exe_sfence = WireInit((0.U).asTypeOf(Valid(new rocket.SFenceReq)))
  for (w <- 0 until memWidth) {
    when (will_fire_sfence(w)) {
      exe_sfence := exe_req(w).bits.sfence
    }
  }

  val exe_size   = widthMap(w =>
                   Mux(will_fire_load_incoming (w) ||
                       will_fire_stad_incoming (w) ||
                       will_fire_sta_incoming  (w) ||
                       will_fire_sfence        (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_sta_retry     (w)  , exe_tlb_uop(w).mem_size,
                   Mux(will_fire_hella_incoming(w)  , hella_req.size,
                                                      0.U)))
  val exe_cmd    = widthMap(w =>
                   Mux(will_fire_load_incoming (w) ||
                       will_fire_stad_incoming (w) ||
                       will_fire_sta_incoming  (w) ||
                       will_fire_sfence        (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_sta_retry     (w)  , exe_tlb_uop(w).mem_cmd,
                   Mux(will_fire_hella_incoming(w)  , hella_req.cmd,
                                                      0.U)))

  val exe_passthr= widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , hella_req.phys,
                                                      false.B))
  val exe_kill   = widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , io.hellacache.s1_kill,
                                                      false.B))
  for (w <- 0 until memWidth) {
    dtlb.io.req(w).valid            := exe_tlb_valid(w)
    dtlb.io.req(w).bits.vaddr       := exe_tlb_vaddr(w)
    dtlb.io.req(w).bits.size        := exe_size(w)
    dtlb.io.req(w).bits.cmd         := exe_cmd(w)
    dtlb.io.req(w).bits.passthrough := exe_passthr(w)
    dtlb.io.req(w).bits.v           := io.ptw.status.v
    dtlb.io.req(w).bits.prv         := io.ptw.status.prv
  }
  dtlb.io.kill                      := exe_kill.reduce(_||_)
  dtlb.io.sfence                    := exe_sfence

  // exceptions
  val ma_ld = widthMap(w => will_fire_load_incoming(w) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
  val ma_st = widthMap(w => (will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
  val pf_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.ld && exe_tlb_uop(w).uses_ldq)
  val pf_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.st && exe_tlb_uop(w).uses_stq)
  val ae_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.ld && exe_tlb_uop(w).uses_ldq)
  val ae_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.st && exe_tlb_uop(w).uses_stq)

  // TODO check for xcpt_if and verify that never happens on non-speculative instructions.
  val mem_xcpt_valids = RegNext(widthMap(w =>
                     (pf_ld(w) || pf_st(w) || ae_ld(w) || ae_st(w) || ma_ld(w) || ma_st(w)) &&
                     !io.core.exception &&
                     !IsKilledByBranch(io.core.brupdate, exe_tlb_uop(w))))
  val mem_xcpt_uops   = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, exe_tlb_uop(w))))
  val mem_xcpt_causes = RegNext(widthMap(w =>
    Mux(ma_ld(w), rocket.Causes.misaligned_load.U,
    Mux(ma_st(w), rocket.Causes.misaligned_store.U,
    Mux(pf_ld(w), rocket.Causes.load_page_fault.U,
    Mux(pf_st(w), rocket.Causes.store_page_fault.U,
    Mux(ae_ld(w), rocket.Causes.load_access.U,
                  rocket.Causes.store_access.U)))))))
  val mem_xcpt_vaddrs = RegNext(exe_tlb_vaddr)

  for (w <- 0 until memWidth) {
    assert (!(dtlb.io.req(w).valid && exe_tlb_uop(w).is_fence), "Fence is pretending to talk to the TLB")
    assert (!((will_fire_load_incoming(w) || will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) &&
      exe_req(w).bits.mxcpt.valid && dtlb.io.req(w).valid &&
    !(exe_tlb_uop(w).ctrl.is_load || exe_tlb_uop(w).ctrl.is_sta)),
      "A uop that's not a load or store-address is throwing a memory exception.")
  }

  mem_xcpt_valid := mem_xcpt_valids.reduce(_||_)
  mem_xcpt_cause := mem_xcpt_causes(0)
  mem_xcpt_uop   := mem_xcpt_uops(0)
  mem_xcpt_vaddr := mem_xcpt_vaddrs(0)
  var xcpt_found = mem_xcpt_valids(0)
  var oldest_xcpt_rob_idx = mem_xcpt_uops(0).rob_idx
  for (w <- 1 until memWidth) {
    val is_older = WireInit(false.B)
    when (mem_xcpt_valids(w) &&
      (IsOlder(mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx, io.core.rob_head_idx) || !xcpt_found)) {
      is_older := true.B
      mem_xcpt_cause := mem_xcpt_causes(w)
      mem_xcpt_uop   := mem_xcpt_uops(w)
      mem_xcpt_vaddr := mem_xcpt_vaddrs(w)
    }
    xcpt_found = xcpt_found || mem_xcpt_valids(w)
    oldest_xcpt_rob_idx = Mux(is_older, mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx)
  }

  val exe_tlb_miss  = widthMap(w => dtlb.io.req(w).valid && (dtlb.io.resp(w).miss || !dtlb.io.req(w).ready))
  val exe_tlb_paddr = widthMap(w => Cat(dtlb.io.resp(w).paddr(paddrBits-1,corePgIdxBits),
                                        exe_tlb_vaddr(w)(corePgIdxBits-1,0)))
  val exe_tlb_uncacheable = widthMap(w => !(dtlb.io.resp(w).cacheable))

  for (w <- 0 until memWidth) {
    assert (exe_tlb_paddr(w) === dtlb.io.resp(w).paddr || exe_req(w).bits.sfence.valid, "[lsu] paddrs should match.")

    // SM: ignore this exception for now 
    // when (mem_xcpt_valids(w))
    // {
    //   assert(RegNext(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) ||
    //     will_fire_load_retry(w) || will_fire_sta_retry(w)))
    //   // Technically only faulting AMOs need this
    //   assert(mem_xcpt_uops(w).uses_ldq ^ mem_xcpt_uops(w).uses_stq)
    //   when (mem_xcpt_uops(w).uses_ldq)
    //   {
    //     ldq(mem_xcpt_uops(w).ldq_idx).bits.uop.exception := true.B
    //   }
    //     .otherwise
    //   {
    //     stq(mem_xcpt_uops(w).stq_idx).bits.uop.exception := true.B
    //   }
    // }
  }



  //------------------------------
  // Issue Someting to Memory
  //
  // A memory op can come from many different places
  // The address either was freshly translated, or we are
  // reading a physical address from the LDQ,STQ, or the HellaCache adapter


  // defaults
  io.dmem.brupdate         := io.core.brupdate
  io.dmem.exception      := io.core.exception
  io.dmem.rob_head_idx   := io.core.rob_head_idx
  io.dmem.rob_pnr_idx    := io.core.rob_pnr_idx

  val dmem_req = Wire(Vec(memWidth, Valid(new BoomDCacheReq)))
  io.dmem.req.valid := dmem_req.map(_.valid).reduce(_||_)
  io.dmem.req.bits  := dmem_req
  val dmem_req_fire = widthMap(w => dmem_req(w).valid && io.dmem.req.fire)

  val s0_executing_loads = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B)))


  for (w <- 0 until memWidth) {
    dmem_req(w).valid := false.B
    dmem_req(w).bits.uop   := NullMicroOp
    dmem_req(w).bits.addr  := 0.U
    dmem_req(w).bits.data  := 0.U
    dmem_req(w).bits.is_hella := false.B

    io.dmem.s1_kill(w) := false.B

    when (will_fire_load_incoming(w)) {
      dmem_req(w).valid      := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
      dmem_req(w).bits.addr  := exe_tlb_paddr(w)
      dmem_req(w).bits.uop   := exe_tlb_uop(w)

      s0_executing_loads(ldq_incoming_idx(w)) := dmem_req_fire(w)
      assert(!ldq_incoming_e(w).bits.executed)
    } .elsewhen (will_fire_load_retry(w)) {
      dmem_req(w).valid      := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
      dmem_req(w).bits.addr  := exe_tlb_paddr(w)
      dmem_req(w).bits.uop   := exe_tlb_uop(w)

      s0_executing_loads(ldq_retry_idx) := dmem_req_fire(w)
      assert(!ldq_retry_e.bits.executed)
    } .elsewhen (will_fire_store_commit(w)) {
      dmem_req(w).valid         := true.B
      dmem_req(w).bits.addr     := stq_commit_e.bits.addr.bits
      dmem_req(w).bits.data     := (new freechips.rocketchip.rocket.StoreGen(
                                    stq_commit_e.bits.uop.mem_size, 0.U,
                                    stq_commit_e.bits.data.bits,
                                    coreDataBytes)).data
      dmem_req(w).bits.uop      := stq_commit_e.bits.uop

      stq_execute_head                     := Mux(dmem_req_fire(w),
                                                WrapInc(stq_execute_head, numStqEntries),
                                                stq_execute_head)

      buf(stq_execute_head).bits.st.bits.succeeded := false.B
    } .elsewhen (will_fire_load_wakeup(w)) {
      dmem_req(w).valid      := true.B
      dmem_req(w).bits.addr  := ldq_wakeup_e.bits.addr.bits
      dmem_req(w).bits.uop   := ldq_wakeup_e.bits.uop

      s0_executing_loads(ldq_wakeup_idx) := dmem_req_fire(w)

      assert(!ldq_wakeup_e.bits.executed && !ldq_wakeup_e.bits.addr_is_virtual)
    } .elsewhen (will_fire_hella_incoming(w)) {
      assert(hella_state === h_s1)

      dmem_req(w).valid               := !io.hellacache.s1_kill && (!exe_tlb_miss(w) || hella_req.phys)
      dmem_req(w).bits.addr           := exe_tlb_paddr(w)
      dmem_req(w).bits.data           := (new freechips.rocketchip.rocket.StoreGen(
        hella_req.size, 0.U,
        io.hellacache.s1_data.data,
        coreDataBytes)).data
      dmem_req(w).bits.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).bits.uop.mem_size   := hella_req.size
      dmem_req(w).bits.uop.mem_signed := hella_req.signed
      dmem_req(w).bits.is_hella       := true.B

      hella_paddr := exe_tlb_paddr(w)
    }
      .elsewhen (will_fire_hella_wakeup(w))
    {
      assert(hella_state === h_replay)
      dmem_req(w).valid               := true.B
      dmem_req(w).bits.addr           := hella_paddr
      dmem_req(w).bits.data           := (new freechips.rocketchip.rocket.StoreGen(
        hella_req.size, 0.U,
        hella_data.data,
        coreDataBytes)).data
      dmem_req(w).bits.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).bits.uop.mem_size   := hella_req.size
      dmem_req(w).bits.uop.mem_signed := hella_req.signed
      dmem_req(w).bits.is_hella       := true.B
    }

    //-------------------------------------------------------------
    // Write Addr into the LAQ/SAQ
    when (will_fire_load_incoming(w) || will_fire_load_retry(w))
    {
      val ldq_idx = Mux(will_fire_load_incoming(w), ldq_incoming_idx(w), ldq_retry_idx)
      buf(ldq_idx).bits.ld.bits.addr.valid          := true.B
      buf(ldq_idx).bits.ld.bits.addr.bits           := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      buf(ldq_idx).bits.ld.bits.uop.pdst            := exe_tlb_uop(w).pdst
      buf(ldq_idx).bits.ld.bits.addr_is_virtual     := exe_tlb_miss(w)
      buf(ldq_idx).bits.ld.bits.addr_is_uncacheable := exe_tlb_uncacheable(w) && !exe_tlb_miss(w)
      printf("Line 839:Write Addr into the LAQ entry %d, with addr %x.\n",ldq_idx,buf(ldq_idx).bits.ld.bits.addr.bits )

      assert(!(will_fire_load_incoming(w) && ldq_incoming_e(w).bits.addr.valid),
        "[lsu] Incoming load is overwriting a valid address")
    }

    when (will_fire_sta_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_retry(w))
    {
      val stq_idx = Mux(will_fire_sta_incoming(w) || will_fire_stad_incoming(w),
        stq_incoming_idx(w), stq_retry_idx)

      buf(stq_idx).bits.st.bits.addr.valid := !pf_st(w) // Prevent AMOs from executing!
      buf(stq_idx).bits.st.bits.addr.bits  := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      buf(stq_idx).bits.st.bits.uop.pdst   := exe_tlb_uop(w).pdst // Needed for AMOs
      buf(stq_idx).bits.st.bits.addr_is_virtual := exe_tlb_miss(w)
      printf("Line 860:Write Addr into the SAQ with entry %d, with addr %x.\n",stq_idx,buf(stq_idx).bits.st.bits.addr.bits)

      assert(!(will_fire_sta_incoming(w) && stq_incoming_e(w).bits.addr.valid),
        "[lsu] Incoming store is overwriting a valid address")

    }

    //-------------------------------------------------------------
    // Write data into the STQ
    if (w == 0)
      io.core.fp_stdata.ready := !will_fire_std_incoming(w) && !will_fire_stad_incoming(w)
    val fp_stdata_fire = io.core.fp_stdata.fire && (w == 0).B
    when (will_fire_std_incoming(w) || will_fire_stad_incoming(w) || fp_stdata_fire)
    {
      val sidx = Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        stq_incoming_idx(w),
        io.core.fp_stdata.bits.uop.stq_idx)
      buf(sidx).bits.st.bits.data.valid := true.B
      buf(sidx).bits.st.bits.data.bits  := Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        exe_req(w).bits.data,
        io.core.fp_stdata.bits.data)

      printf("Line 882: Write data into the STQ in entry %d, with data being: %x.\n",sidx, buf(sidx).bits.st.bits.data.bits)

      assert(!(buf(sidx).bits.st.bits.data.valid),
        "[lsu] Incoming store is overwriting a valid data entry")
    }
  }
  val will_fire_stdf_incoming = io.core.fp_stdata.fire
  require (xLen >= fLen) // for correct SDQ size

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Cache Access Cycle (Mem)
  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Note the DCache may not have accepted our request

  val exe_req_killed = widthMap(w => IsKilledByBranch(io.core.brupdate, exe_req(w).bits.uop))
  val stdf_killed = IsKilledByBranch(io.core.brupdate, io.core.fp_stdata.bits.uop)

  val fired_load_incoming  = widthMap(w => RegNext(will_fire_load_incoming(w) && !exe_req_killed(w)))
  val fired_stad_incoming  = widthMap(w => RegNext(will_fire_stad_incoming(w) && !exe_req_killed(w)))
  val fired_sta_incoming   = widthMap(w => RegNext(will_fire_sta_incoming (w) && !exe_req_killed(w)))
  val fired_std_incoming   = widthMap(w => RegNext(will_fire_std_incoming (w) && !exe_req_killed(w)))
  val fired_stdf_incoming  = RegNext(will_fire_stdf_incoming && !stdf_killed)
  val fired_sfence         = RegNext(will_fire_sfence)
  val fired_release        = RegNext(will_fire_release)
  val fired_load_retry     = widthMap(w => RegNext(will_fire_load_retry   (w) && !IsKilledByBranch(io.core.brupdate, ldq_retry_e.bits.uop)))
  val fired_sta_retry      = widthMap(w => RegNext(will_fire_sta_retry    (w) && !IsKilledByBranch(io.core.brupdate, stq_retry_e.bits.uop)))
  val fired_store_commit   = RegNext(will_fire_store_commit)
  val fired_load_wakeup    = widthMap(w => RegNext(will_fire_load_wakeup  (w) && !IsKilledByBranch(io.core.brupdate, ldq_wakeup_e.bits.uop)))
  val fired_hella_incoming = RegNext(will_fire_hella_incoming)
  val fired_hella_wakeup   = RegNext(will_fire_hella_wakeup)

  val mem_incoming_uop     = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, exe_req(w).bits.uop)))
  val mem_ldq_incoming_e   = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, ldq_incoming_e(w))))
  val mem_stq_incoming_e   = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, stq_incoming_e(w))))
  val mem_ldq_wakeup_e     = RegNext(UpdateBrMask(io.core.brupdate, ldq_wakeup_e))
  val mem_ldq_retry_e      = RegNext(UpdateBrMask(io.core.brupdate, ldq_retry_e))
  val mem_stq_retry_e      = RegNext(UpdateBrMask(io.core.brupdate, stq_retry_e))
  val mem_ldq_e            = widthMap(w =>
                             Mux(fired_load_incoming(w), mem_ldq_incoming_e(w),
                             Mux(fired_load_retry   (w), mem_ldq_retry_e,
                             Mux(fired_load_wakeup  (w), mem_ldq_wakeup_e, (0.U).asTypeOf(Valid(new LDQEntry))))))
  val mem_stq_e            = widthMap(w =>
                             Mux(fired_stad_incoming(w) ||
                                 fired_sta_incoming (w), mem_stq_incoming_e(w),
                             Mux(fired_sta_retry    (w), mem_stq_retry_e, (0.U).asTypeOf(Valid(new STQEntry)))))
  val mem_stdf_uop         = RegNext(UpdateBrMask(io.core.brupdate, io.core.fp_stdata.bits.uop))


  val mem_tlb_miss             = RegNext(exe_tlb_miss)
  val mem_tlb_uncacheable      = RegNext(exe_tlb_uncacheable)
  val mem_paddr                = RegNext(widthMap(w => dmem_req(w).bits.addr))

  // Task 1: Clr ROB busy bit
  val clr_bsy_valid   = RegInit(widthMap(w => false.B))
  val clr_bsy_rob_idx = Reg(Vec(memWidth, UInt(robAddrSz.W)))
  val clr_bsy_brmask  = Reg(Vec(memWidth, UInt(maxBrCount.W)))

  for (w <- 0 until memWidth) {
    clr_bsy_valid   (w) := false.B
    clr_bsy_rob_idx (w) := 0.U
    clr_bsy_brmask  (w) := 0.U


    when (fired_stad_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid           &&
                            !mem_tlb_miss(w)                       &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_sta_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid            &&
                             mem_stq_incoming_e(w).bits.data.valid  &&
                            !mem_tlb_miss(w)                        &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo  &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_std_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid                 &&
                             mem_stq_incoming_e(w).bits.addr.valid       &&
                            !mem_stq_incoming_e(w).bits.addr_is_virtual  &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo       &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_sfence(w)) {
      clr_bsy_valid   (w) := (w == 0).B // SFence proceeds down all paths, only allow one to clr the rob
      clr_bsy_rob_idx (w) := mem_incoming_uop(w).rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_incoming_uop(w))
    } .elsewhen (fired_sta_retry(w)) {
      clr_bsy_valid   (w) := mem_stq_retry_e.valid            &&
                             mem_stq_retry_e.bits.data.valid  &&
                            !mem_tlb_miss(w)                  &&
                            !mem_stq_retry_e.bits.uop.is_amo  &&
                            !IsKilledByBranch(io.core.brupdate, mem_stq_retry_e.bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_retry_e.bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brupdate, mem_stq_retry_e.bits.uop)
    }

    io.core.clr_bsy(w).valid := clr_bsy_valid(w) &&
                               !IsKilledByBranch(io.core.brupdate, clr_bsy_brmask(w)) &&
                               !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
    io.core.clr_bsy(w).bits  := clr_bsy_rob_idx(w)
  }

  val stdf_clr_bsy_valid   = RegInit(false.B)
  val stdf_clr_bsy_rob_idx = Reg(UInt(robAddrSz.W))
  val stdf_clr_bsy_brmask  = Reg(UInt(maxBrCount.W))
  stdf_clr_bsy_valid   := false.B
  stdf_clr_bsy_rob_idx := 0.U
  stdf_clr_bsy_brmask  := 0.U
  when (fired_stdf_incoming) {
    val s_idx = mem_stdf_uop.stq_idx
    stdf_clr_bsy_valid   := buf(s_idx).bits.st.valid                 &&
                            buf(s_idx).bits.st.bits.addr.valid       &&
                            !buf(s_idx).bits.st.bits.addr_is_virtual &&
                            !buf(s_idx).bits.st.bits.uop.is_amo      &&
                            !IsKilledByBranch(io.core.brupdate, mem_stdf_uop)
    stdf_clr_bsy_rob_idx := mem_stdf_uop.rob_idx
    stdf_clr_bsy_brmask  := GetNewBrMask(io.core.brupdate, mem_stdf_uop)
  }



  io.core.clr_bsy(memWidth).valid := stdf_clr_bsy_valid &&
                                    !IsKilledByBranch(io.core.brupdate, stdf_clr_bsy_brmask) &&
                                    !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
  io.core.clr_bsy(memWidth).bits  := stdf_clr_bsy_rob_idx



  // Task 2: Do LD-LD. ST-LD searches for ordering failures
  //         Do LD-ST search for forwarding opportunities
  // We have the opportunity to kill a request we sent last cycle. Use it wisely!

  // // We translated a store last cycle
  val do_st_search = widthMap(w => (fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w)) && !mem_tlb_miss(w))
  // We translated a load last cycle
  val do_ld_search = widthMap(w => ((fired_load_incoming(w) || fired_load_retry(w)) && !mem_tlb_miss(w)) ||
                     fired_load_wakeup(w))
  // We are making a local line visible to other harts
  val do_release_search = widthMap(w => fired_release(w))

  // Store addrs don't go to memory yet, get it from the TLB response
  // Load wakeups don't go through TLB, get it through memory
  // Load incoming and load retries go through both

  val lcam_addr  = widthMap(w => Mux(fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w),
                                     RegNext(exe_tlb_paddr(w)),
                                     Mux(fired_release(w), RegNext(io.dmem.release.bits.address),
                                         mem_paddr(w))))
  val lcam_uop   = widthMap(w => Mux(do_st_search(w), mem_stq_e(w).bits.uop,
                                 Mux(do_ld_search(w), mem_ldq_e(w).bits.uop, NullMicroOp)))

  val lcam_mask  = widthMap(w => GenByteMask(lcam_addr(w), lcam_uop(w).mem_size))
  val lcam_st_dep_mask = widthMap(w => mem_ldq_e(w).bits.st_dep_mask)
  val lcam_is_release = widthMap(w => fired_release(w))
  val lcam_ldq_idx  = widthMap(w =>
                      Mux(fired_load_incoming(w), mem_incoming_uop(w).ldq_idx,
                      Mux(fired_load_wakeup  (w), RegNext(ldq_wakeup_idx),
                      Mux(fired_load_retry   (w), RegNext(ldq_retry_idx), 0.U))))
  val lcam_stq_idx  = widthMap(w =>
                      Mux(fired_stad_incoming(w) ||
                          fired_sta_incoming (w), mem_incoming_uop(w).stq_idx,
                      Mux(fired_sta_retry    (w), RegNext(stq_retry_idx), 0.U)))

  // val can_forward = WireInit(widthMap(w =>
  //   Mux(fired_load_incoming(w) || fired_load_retry(w), !mem_tlb_uncacheable(w),
  //     !ldq(lcam_ldq_idx(w)).bits.addr_is_uncacheable)))

  // // Mask of stores which we conflict on address with
  // val ldst_addr_matches    = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x=>false.B))))
  // // Mask of stores which we can forward from
  // val ldst_forward_matches = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x=>false.B))))

  val failed_loads     = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which we will report as failures (throws a mini-exception)
  val nacking_loads    = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which are being nacked by dcache in the next stage

  val s1_executing_loads = RegNext(s0_executing_loads)
  val s1_set_execute     = WireInit(s1_executing_loads)

  // val mem_forward_valid   = Wire(Vec(memWidth, Bool()))
  // val mem_forward_ldq_idx = lcam_ldq_idx
  // val mem_forward_ld_addr = lcam_addr
  // val mem_forward_stq_idx = Wire(Vec(memWidth, UInt(log2Ceil(numStqEntries).W)))

  // val wb_forward_valid    = RegNext(mem_forward_valid)
  // val wb_forward_ldq_idx  = RegNext(mem_forward_ldq_idx)
  // val wb_forward_ld_addr  = RegNext(mem_forward_ld_addr)
  // val wb_forward_stq_idx  = RegNext(mem_forward_stq_idx)

  // for (i <- 0 until numLdqEntries) {
  //   val l_valid = ldq(i).valid
  //   val l_bits  = ldq(i).bits
  //   val l_addr  = ldq(i).bits.addr.bits
  //   val l_mask  = GenByteMask(l_addr, l_bits.uop.mem_size)

  //   val l_forwarders      = widthMap(w => wb_forward_valid(w) && wb_forward_ldq_idx(w) === i.U)
  //   val l_is_forwarding   = l_forwarders.reduce(_||_)
  //   val l_forward_stq_idx = Mux(l_is_forwarding, Mux1H(l_forwarders, wb_forward_stq_idx), l_bits.forward_stq_idx)


  //   val block_addr_matches = widthMap(w => lcam_addr(w) >> blockOffBits === l_addr >> blockOffBits)
  //   val dword_addr_matches = widthMap(w => block_addr_matches(w) && lcam_addr(w)(blockOffBits-1,3) === l_addr(blockOffBits-1,3))
  //   val mask_match   = widthMap(w => (l_mask & lcam_mask(w)) === l_mask)
  //   val mask_overlap = widthMap(w => (l_mask & lcam_mask(w)).orR)

  //   // Searcher is a store
  //   for (w <- 0 until memWidth) {

  //     when (do_release_search(w) &&
  //           l_valid              &&
  //           l_bits.addr.valid    &&
  //           block_addr_matches(w)) {
  //       // This load has been observed, so if a younger load to the same address has not
  //       // executed yet, this load must be squashed
  //       ldq(i).bits.observed := true.B
  //     } .elsewhen (do_st_search(w)                                                                                                &&
  //                  l_valid                                                                                                        &&
  //                  l_bits.addr.valid                                                                                              &&
  //                  (l_bits.executed || l_bits.succeeded || l_is_forwarding)                                                       &&
  //                  !l_bits.addr_is_virtual                                                                                        &&
  //                  l_bits.st_dep_mask(lcam_stq_idx(w))                                                                            &&
  //                  dword_addr_matches(w)                                                                                          &&
  //                  mask_overlap(w)) {

  //       val forwarded_is_older = IsOlder(l_forward_stq_idx, lcam_stq_idx(w), l_bits.youngest_stq_idx)
  //       // We are older than this load, which overlapped us.
  //       when (!l_bits.forward_std_val || // If the load wasn't forwarded, it definitely failed
  //         ((l_forward_stq_idx =/= lcam_stq_idx(w)) && forwarded_is_older)) { // If the load forwarded from us, we might be ok
  //         //SM: allow LD-ST,arch3
  //         // ldq(i).bits.order_fail := true.B
  //         // failed_loads(i)        := true.B
  //       }
  //     } .elsewhen (do_ld_search(w)            &&
  //                  l_valid                    &&
  //                  l_bits.addr.valid          &&
  //                  !l_bits.addr_is_virtual    &&
  //                  dword_addr_matches(w)      &&
  //                  mask_overlap(w)) {
  //       val searcher_is_older = IsOlder(lcam_ldq_idx(w), i.U, ldq_head)
  //       when (searcher_is_older) {
  //         when ((l_bits.executed || l_bits.succeeded || l_is_forwarding) &&
  //               !s1_executing_loads(i) && // If the load is proceeding in parallel we don't need to kill it
  //               l_bits.observed) {        // Its only a ordering failure if the cache line was observed between the younger load and us
  //           //SM: probably LD-LD detect,arch3
  //           ldq(i).bits.order_fail := true.B
  //           failed_loads(i)        := true.B
  //         }
  //       } .elsewhen (lcam_ldq_idx(w) =/= i.U) {
  //         // The load is older, and either it hasn't executed, it was nacked, or it is ignoring its response
  //         // we need to kill ourselves, and prevent forwarding
  //         val older_nacked = nacking_loads(i) || RegNext(nacking_loads(i))
  //         when (!(l_bits.executed || l_bits.succeeded) || older_nacked) {
  //           s1_set_execute(lcam_ldq_idx(w))    := false.B
  //           io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
  //           can_forward(w)                     := false.B
  //         }
  //       }
  //     }
  //   }
  // }

  // for (i <- 0 until numStqEntries) {
  //   val s_addr = stq(i).bits.addr.bits
  //   val s_uop  = stq(i).bits.uop
  //   val dword_addr_matches = widthMap(w =>
  //                            ( stq(i).bits.addr.valid      &&
  //                             !stq(i).bits.addr_is_virtual &&
  //                             (s_addr(corePAddrBits-1,3) === lcam_addr(w)(corePAddrBits-1,3))))
  //   val write_mask = GenByteMask(s_addr, s_uop.mem_size)
  //   for (w <- 0 until memWidth) {
  //     when (do_ld_search(w) && stq(i).valid && lcam_st_dep_mask(w)(i)) {
  //       when (((lcam_mask(w) & write_mask) === lcam_mask(w)) && !s_uop.is_fence && dword_addr_matches(w) && can_forward(w))
  //       {
  //         printf("Line 1158: ldst_forward_matches set to TRUE. \n")
  //         ldst_addr_matches(w)(i)            := true.B
  //         ldst_forward_matches(w)(i)         := true.B
  //         io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
  //         s1_set_execute(lcam_ldq_idx(w))    := false.B
  //       }
  //         .elsewhen (((lcam_mask(w) & write_mask) =/= 0.U) && dword_addr_matches(w))
  //       {
  //         ldst_addr_matches(w)(i)            := true.B
  //         io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
  //         s1_set_execute(lcam_ldq_idx(w))    := false.B
  //       }
  //         .elsewhen (s_uop.is_fence || s_uop.is_amo)
  //       {
  //         ldst_addr_matches(w)(i)            := true.B
  //         io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
  //         s1_set_execute(lcam_ldq_idx(w))    := false.B
  //       }
  //     }
  //   }
  // }

  // WE don't do any forward or match in this version of strong memory model

  // Set execute bit in LDQ
  //SM: the store queue entry should just be ignored?
  for (i <- 0 until numLdqEntries) {
    when (s1_set_execute(i) && buf(i).bits.is_ld ) { buf(i).bits.ld.bits.executed := true.B }
  }

  // // Find the youngest store which the load is dependent on
  // val forwarding_age_logic = Seq.fill(memWidth) { Module(new ForwardingAgeLogic(numStqEntries)) }
  // for (w <- 0 until memWidth) {
  //   forwarding_age_logic(w).io.addr_matches    := ldst_addr_matches(w).asUInt
  //   forwarding_age_logic(w).io.youngest_st_idx := lcam_uop(w).stq_idx
  // }
  // val forwarding_idx = widthMap(w => forwarding_age_logic(w).io.forwarding_idx)

  // // Forward if st-ld forwarding is possible from the writemask and loadmask
  // mem_forward_valid       := widthMap(w =>
  //                                 (ldst_forward_matches(w)(forwarding_idx(w))        &&
  //                                !IsKilledByBranch(io.core.brupdate, lcam_uop(w))    &&
  //                                !io.core.exception && !RegNext(io.core.exception)))
  // mem_forward_stq_idx     := forwarding_idx

  // // Avoid deadlock with a 1-w LSU prioritizing load wakeups > store commits
  // // On a 2W machine, load wakeups and store commits occupy separate pipelines,
  // // so only add this logic for 1-w LSU
  // if (memWidth == 1) {
  //   // Wakeups may repeatedly find a st->ld addr conflict and fail to forward,
  //   // repeated wakeups may block the store from ever committing
  //   // Disallow load wakeups 1 cycle after this happens to allow the stores to drain
  //   when (RegNext(ldst_addr_matches(0).reduce(_||_) && !mem_forward_valid(0))) {
  //     block_load_wakeup := true.B
  //   }

  //   // If stores remain blocked for 15 cycles, block load wakeups to get a store through
  //   val store_blocked_counter = Reg(UInt(4.W))
  //   when (will_fire_store_commit(0) || !can_fire_store_commit(0)) {
  //     store_blocked_counter := 0.U
  //   } .elsewhen (can_fire_store_commit(0) && !will_fire_store_commit(0)) {
  //     store_blocked_counter := Mux(store_blocked_counter === 15.U, store_blocked_counter + 1.U, 15.U)
  //   }
  //   when (store_blocked_counter === 15.U) {
  //     block_load_wakeup := true.B
  //   }
  // }


  // Task 3: Clr unsafe bit in ROB for succesful translations
  //         Delay this a cycle to avoid going ahead of the exception broadcast
  //         The unsafe bit is cleared on the first translation, so no need to fire for load wakeups
  for (w <- 0 until memWidth) {
    io.core.clr_unsafe(w).valid := RegNext((do_st_search(w) || do_ld_search(w)) && !fired_load_wakeup(w)) && false.B
    io.core.clr_unsafe(w).bits  := RegNext(lcam_uop(w).rob_idx)
  }

  // detect which loads get marked as failures, but broadcast to the ROB the oldest failing load
  // TODO encapsulate this in an age-based  priority-encoder
  //   val l_idx = AgePriorityEncoder((Vec(Vec.tabulate(numLdqEntries)(i => failed_loads(i) && i.U >= laq_head)
  //   ++ failed_loads)).asUInt)
  val temp_bits = (VecInit(VecInit.tabulate(numLdqEntries)(i =>
    failed_loads(i) && i.U >= ldq_head) ++ failed_loads)).asUInt
  val l_idx = PriorityEncoder(temp_bits)

  // one exception port, but multiple causes!
  // - 1) the incoming store-address finds a faulting load (it is by definition younger)
  // - 2) the incoming load or store address is excepting. It must be older and thus takes precedent.
  val r_xcpt_valid = RegInit(false.B)
  val r_xcpt       = Reg(new Exception)

  val ld_xcpt_valid = failed_loads.reduce(_|_)
  val ld_xcpt_uop   = buf(Mux(l_idx >= numLdqEntries.U, l_idx - numLdqEntries.U, l_idx)).bits.ld.bits.uop

  val use_mem_xcpt = (mem_xcpt_valid && IsOlder(mem_xcpt_uop.rob_idx, ld_xcpt_uop.rob_idx, io.core.rob_head_idx)) || !ld_xcpt_valid

  val xcpt_uop = Mux(use_mem_xcpt, mem_xcpt_uop, ld_xcpt_uop)

  r_xcpt_valid := (ld_xcpt_valid || mem_xcpt_valid) &&
                   !io.core.exception &&
                   !IsKilledByBranch(io.core.brupdate, xcpt_uop)
  r_xcpt.uop         := xcpt_uop
  r_xcpt.uop.br_mask := GetNewBrMask(io.core.brupdate, xcpt_uop)
  r_xcpt.cause       := Mux(use_mem_xcpt, mem_xcpt_cause, MINI_EXCEPTION_MEM_ORDERING)
  r_xcpt.badvaddr    := mem_xcpt_vaddr // TODO is there another register we can use instead?

  io.core.lxcpt.valid := r_xcpt_valid && !io.core.exception && !IsKilledByBranch(io.core.brupdate, r_xcpt.uop)
  io.core.lxcpt.bits  := r_xcpt

  // Task 4: Speculatively wakeup loads 1 cycle before they come back
  for (w <- 0 until memWidth) {
    io.core.spec_ld_wakeup(w).valid := enableFastLoadUse.B          &&
                                       fired_load_incoming(w)       &&
                                       !mem_incoming_uop(w).fp_val  &&
                                       mem_incoming_uop(w).pdst =/= 0.U
    io.core.spec_ld_wakeup(w).bits  := mem_incoming_uop(w).pdst
  }


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Writeback Cycle (St->Ld Forwarding Path)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Handle Memory Responses and nacks
  //----------------------------------
  for (w <- 0 until memWidth) {
    io.core.exe(w).iresp.valid := false.B
    io.core.exe(w).fresp.valid := false.B
  }

  val dmem_resp_fired = WireInit(widthMap(w => false.B))

  for (w <- 0 until memWidth) {
    // Handle nacks
    when (io.dmem.nack(w).valid)
    {
      printf("Line 1356: NACK Dmem response. \n")
      // We have to re-execute this!
      when (io.dmem.nack(w).bits.is_hella)
      {
        assert(hella_state === h_wait || hella_state === h_dead)
      }
        .elsewhen (io.dmem.nack(w).bits.uop.uses_ldq)
      {
        assert(buf(io.dmem.nack(w).bits.uop.buf_idx).bits.ld.bits.executed)
        buf(io.dmem.nack(w).bits.uop.buf_idx).bits.ld.bits.executed  := false.B
        nacking_loads(io.dmem.nack(w).bits.uop.buf_idx) := true.B
      }
        .otherwise
      {
        assert(io.dmem.nack(w).bits.uop.uses_stq)
        when (IsOlder(io.dmem.nack(w).bits.uop.buf_idx, stq_execute_head, stq_head)) {
          stq_execute_head := io.dmem.nack(w).bits.uop.buf_idx
        }
      }
    }
    // Handle the response
    when (io.dmem.resp(w).valid)
    {
      printf("Line 1379: dmem response valid.\n")
      when (io.dmem.resp(w).bits.uop.uses_ldq)
      {
        assert(!io.dmem.resp(w).bits.is_hella)
        val ldq_idx = io.dmem.resp(w).bits.uop.buf_idx
        val send_iresp = buf(ldq_idx).bits.ld.bits.uop.dst_rtype === RT_FIX
        val send_fresp = buf(ldq_idx).bits.ld.bits.uop.dst_rtype === RT_FLT
        printf("Line 1384: set LDQ response load with entry %d \n",ldq_idx)

        io.core.exe(w).iresp.bits.uop  := buf(ldq_idx).bits.ld.bits.uop
        io.core.exe(w).fresp.bits.uop  := buf(ldq_idx).bits.ld.bits.uop
        io.core.exe(w).iresp.valid     := send_iresp
        io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data
        io.core.exe(w).fresp.valid     := send_fresp
        io.core.exe(w).fresp.bits.data := io.dmem.resp(w).bits.data

        assert(send_iresp ^ send_fresp)
        dmem_resp_fired(w) := true.B

        buf(ldq_idx).bits.ld.bits.succeeded      := io.core.exe(w).iresp.valid || io.core.exe(w).fresp.valid
        buf(ldq_idx).bits.ld.bits.debug_wb_data  := io.dmem.resp(w).bits.data
      }
        .elsewhen (io.dmem.resp(w).bits.uop.uses_stq)
      {
        assert(!io.dmem.resp(w).bits.is_hella)
        printf("Line 1401: set STQ request store with entry %d succeed\n", io.dmem.resp(w).bits.uop.stq_idx)
        buf(io.dmem.resp(w).bits.uop.stq_idx).bits.st.bits.succeeded := true.B
        when (io.dmem.resp(w).bits.uop.is_amo) {
          dmem_resp_fired(w) := true.B
          io.core.exe(w).iresp.valid     := true.B
          io.core.exe(w).iresp.bits.uop  := buf(io.dmem.resp(w).bits.uop.buf_idx).bits.st.bits.uop
          io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data

          buf(io.dmem.resp(w).bits.uop.buf_idx).bits.st.bits.debug_wb_data := io.dmem.resp(w).bits.data
        }
      }
    }


    // // SM: disable forwarding to observe effect on memory model
    // when (dmem_resp_fired(w) && wb_forward_valid(w))
    // {
    //   printf("Line 1353: an't forward because dcache response takes precedence.\n")
    //   // Twiddle thumbs. Can't forward because dcache response takes precedence
    // }
    //   .elsewhen (!dmem_resp_fired(w) && wb_forward_valid(w))
    // {
    //   printf("Line 1352 reached with forwarding.\n")
    //   printf("Line 1353: wb_forward_ldq_idx is %d\n", wb_forward_ldq_idx(w))
    //   printf("Line 1353: wb_forward_stq_idx is %d\n", wb_forward_stq_idx(w))
    //   val f_idx       = wb_forward_ldq_idx(w)
    //   val forward_uop = ldq(f_idx).bits.uop
    //   val stq_e       = stq(wb_forward_stq_idx(w))
    //   val data_ready  = stq_e.bits.data.valid
    //   val live        = !IsKilledByBranch(io.core.brupdate, forward_uop)
    //   val storegen = new freechips.rocketchip.rocket.StoreGen(
    //                             stq_e.bits.uop.mem_size, stq_e.bits.addr.bits,
    //                             stq_e.bits.data.bits, coreDataBytes)
    //   val loadgen  = new freechips.rocketchip.rocket.LoadGen(
    //                             forward_uop.mem_size, forward_uop.mem_signed,
    //                             wb_forward_ld_addr(w),
    //                             storegen.data, false.B, coreDataBytes)

    //   io.core.exe(w).iresp.valid := (forward_uop.dst_rtype === RT_FIX) && data_ready && live
    //   io.core.exe(w).fresp.valid := (forward_uop.dst_rtype === RT_FLT) && data_ready && live
    //   io.core.exe(w).iresp.bits.uop  := forward_uop
    //   io.core.exe(w).fresp.bits.uop  := forward_uop
    //   io.core.exe(w).iresp.bits.data := loadgen.data
    //   io.core.exe(w).fresp.bits.data := loadgen.data

    //   when (data_ready && live) {
    //     printf("Line 1374: forward Succeeded with f_idx %d.\n",f_idx)
    //     ldq(f_idx).bits.succeeded := data_ready
    //     ldq(f_idx).bits.forward_std_val := true.B
    //     ldq(f_idx).bits.forward_stq_idx := wb_forward_stq_idx(w)

    //     ldq(f_idx).bits.debug_wb_data   := loadgen.data
    //   }
    // }
  }

  // Initially assume the speculative load wakeup failed
  io.core.ld_miss         := RegNext(io.core.spec_ld_wakeup.map(_.valid).reduce(_||_))
  val spec_ld_succeed = widthMap(w =>
    !RegNext(io.core.spec_ld_wakeup(w).valid) ||
    (io.core.exe(w).iresp.valid &&
      io.core.exe(w).iresp.bits.uop.ldq_idx === RegNext(mem_incoming_uop(w).ldq_idx)
    )
  ).reduce(_&&_)
  when (spec_ld_succeed) {
    io.core.ld_miss := false.B
  }


  //-------------------------------------------------------------
  // Kill speculated entries on branch mispredict
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Kill stores
  val st_brkilled_mask = Wire(Vec(numStqEntries, Bool()))
  for (i <- 0 until numStqEntries)
  {
    st_brkilled_mask(i) := false.B

    when (buf(i).valid && buf(i).bits.st.valid && !buf(i).bits.is_ld)
    {
      print("Line 1473: Kill store.\n")
      buf(i).bits.st.bits.uop.br_mask := GetNewBrMask(io.core.brupdate, buf(i).bits.st.bits.uop.br_mask)

      when (IsKilledByBranch(io.core.brupdate, buf(i).bits.st.bits.uop))
      {
        buf(i).bits.st.valid           := false.B
        buf(i).bits.st.bits.addr.valid := false.B
        buf(i).bits.st.bits.data.valid := false.B
        st_brkilled_mask(i)    := true.B
      }
    }

    assert (!(IsKilledByBranch(io.core.brupdate, buf(i).bits.st.bits.uop) && buf(i).bits.st.valid && buf(i).bits.st.bits.committed),
      "Branch is trying to clear a committed store.")
  }

  // Kill loads
  for (i <- 0 until numLdqEntries)
  {
    when (buf(i).valid && buf(i).bits.ld.valid && buf(i).bits.is_ld)
    {
      print("Line 1493: Kill load\n")
      buf(i).bits.ld.bits.uop.br_mask := GetNewBrMask(io.core.brupdate, buf(i).bits.ld.bits.uop.br_mask)
      when (IsKilledByBranch(io.core.brupdate, buf(i).bits.ld.bits.uop))
      {
        buf(i).valid                   := false.B
        buf(i).bits.ld.valid           := false.B
        buf(i).bits.ld.bits.addr.valid := false.B
      }
    }
  }

  //-------------------------------------------------------------
  when (io.core.brupdate.b2.mispredict && !io.core.exception)
  {
    printf("Line 1521: exception!!!\n")
    // var stq_tail := io.core.brupdate.b2.uop.stq_idx
    // var ldq_tail := io.core.brupdate.b2.uop.ldq_idx
    // var buf_tail : = Mux(stq_tail > ldq_tail, stq_tail, ldq_tail)
    // buf_tail := io.core.brupdate.b2.uop.buf_idx
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // dequeue old entries on commit
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var temp_stq_commit_head = stq_commit_head
  var temp_ldq_head        = ldq_head
  var temp_buf_head        = buf_head
  for (w <- 0 until coreWidth)
  {
    val commit_store = io.core.commit.valids(w) && io.core.commit.uops(w).uses_stq
    val commit_load  = io.core.commit.valids(w) && io.core.commit.uops(w).uses_ldq
    val idx = Mux(commit_store, temp_stq_commit_head, temp_ldq_head)
    when (commit_store)
    {
      buf(idx).bits.st.bits.committed := true.B
      printf("Line 1464: deque: mark store queue as commited on STQ %d.\n", idx)
    } .elsewhen (commit_load) {
      assert (buf(idx).bits.ld.valid, "[lsu] trying to commit an un-allocated load entry.")
      // assert ((ldq(idx).bits.executed || ldq(idx).bits.forward_std_val) && ldq(idx).bits.succeeded ,
      //   "[lsu] trying to commit an un-executed load entry.")

      buf(idx).valid                 := false.B
      buf(idx).bits.ld.valid                 := false.B
      buf(idx).bits.ld.bits.addr.valid       := false.B
      buf(idx).bits.ld.bits.executed         := false.B
      buf(idx).bits.ld.bits.succeeded        := false.B
      buf(idx).bits.ld.bits.order_fail       := false.B
      buf(idx).bits.ld.bits.forward_std_val  := false.B
      printf("Line 1475: finish deque load queue on LDQ entry %d.\n", idx)

    }

    // if (MEMTRACE_PRINTF) {
      when (commit_store || commit_load) {
        val uop    = Mux(commit_store, buf(idx).bits.st.bits.uop, buf(idx).bits.ld.bits.uop)
        val addr   = Mux(commit_store, buf(idx).bits.st.bits.addr.bits, buf(idx).bits.ld.bits.addr.bits)
        val stdata = Mux(commit_store, buf(idx).bits.st.bits.data.bits, 0.U)
        val wbdata = Mux(commit_store, buf(idx).bits.st.bits.debug_wb_data, buf(idx).bits.ld.bits.debug_wb_data)
        printf("MT %x %x %x %x %x %x %x\n",
          io.core.tsc_reg, uop.uopc, uop.mem_cmd, uop.mem_size, addr, stdata, wbdata)
      }
    // }

    temp_stq_commit_head = Mux(commit_store,
                               WrapInc(temp_stq_commit_head, numStqEntries),
                               temp_stq_commit_head)

    temp_ldq_head        = Mux(commit_load,
                               WrapInc(temp_ldq_head, numLdqEntries),
                               temp_ldq_head)

    // temp_buf_head        = Mux(commit_load,
    //                            temp_ldq_head,
    //                            temp_stq_commit_head)
  }
  stq_commit_head := temp_stq_commit_head
  ldq_head        := temp_ldq_head
  // buf_head        := temp_buf_head

  // store has been committed AND successfully sent data to memory
  when (buf(stq_head).valid && !buf(stq_head).bits.is_ld && buf(stq_head).bits.st.valid && buf(stq_head).bits.st.bits.committed)
  {
    // printf("store has been committed AND successfully sent data to memory on entry %d.\n",stq_head)
    when (buf(buf_head).bits.st.bits.uop.is_fence && !io.dmem.ordered) {
      io.dmem.force_order := true.B
      // store_needs_order   := true.B
    }
    clear_store := Mux(buf(stq_head).bits.st.bits.uop.is_fence, io.dmem.ordered,
                                                        buf(stq_head).bits.st.bits.succeeded)
    printf("Line 1597: Committed, Success/Fail: %d \n",buf(stq_head).bits.st.bits.succeeded)
    printf("Line 1598: stq_head is %d; buf_head is %d \n", stq_head, buf_head)
  }

  when (clear_store)
  {
    printf("Line 1593: clear store.\n")
    buf(buf_head).valid           := false.B
    buf(buf_head).bits.st.valid           := false.B
    buf(buf_head).bits.st.valid           := false.B
    buf(buf_head).bits.st.bits.addr.valid := false.B
    buf(buf_head).bits.st.bits.data.valid := false.B
    buf(buf_head).bits.st.bits.succeeded  := false.B
    buf(buf_head).bits.st.bits.committed  := false.B

    buf_head := WrapInc(buf_head, numStqEntries)
    stq_head := WrapInc(stq_head, numStqEntries)
    printf("Line 1520: finish deque store queue on STQ entry %d.\n", stq_head)
    when (buf(buf_head).bits.st.bits.uop.is_fence)
    {
      stq_execute_head := WrapInc(stq_execute_head, numStqEntries)
    }
  }


  // -----------------------
  // Hellacache interface
  // We need to time things like a HellaCache would
  io.hellacache.req.ready := false.B
  io.hellacache.s2_nack   := false.B
  io.hellacache.s2_xcpt   := (0.U).asTypeOf(new rocket.HellaCacheExceptions)
  io.hellacache.resp.valid := false.B
  when (hella_state === h_ready) {
    io.hellacache.req.ready := true.B
    when (io.hellacache.req.fire) {
      hella_req   := io.hellacache.req.bits
      hella_state := h_s1
    }
  } .elsewhen (hella_state === h_s1) {
    can_fire_hella_incoming(memWidth-1) := true.B

    hella_data := io.hellacache.s1_data
    hella_xcpt := dtlb.io.resp(memWidth-1)

    when (io.hellacache.s1_kill) {
      when (will_fire_hella_incoming(memWidth-1) && dmem_req_fire(memWidth-1)) {
        hella_state := h_dead
      } .otherwise {
        hella_state := h_ready
      }
    } .elsewhen (will_fire_hella_incoming(memWidth-1) && dmem_req_fire(memWidth-1)) {
      hella_state := h_s2
    } .otherwise {
      hella_state := h_s2_nack
    }
  } .elsewhen (hella_state === h_s2_nack) {
    io.hellacache.s2_nack := true.B
    hella_state := h_ready
  } .elsewhen (hella_state === h_s2) {
    io.hellacache.s2_xcpt := hella_xcpt
    when (io.hellacache.s2_kill || hella_xcpt.asUInt =/= 0.U) {
      hella_state := h_dead
    } .otherwise {
      hella_state := h_wait
    }
  } .elsewhen (hella_state === h_wait) {
    for (w <- 0 until memWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).bits.is_hella) {
        hella_state := h_ready

        io.hellacache.resp.valid       := true.B
        io.hellacache.resp.bits.addr   := hella_req.addr
        io.hellacache.resp.bits.tag    := hella_req.tag
        io.hellacache.resp.bits.cmd    := hella_req.cmd
        io.hellacache.resp.bits.signed := hella_req.signed
        io.hellacache.resp.bits.size   := hella_req.size
        io.hellacache.resp.bits.data   := io.dmem.resp(w).bits.data
      } .elsewhen (io.dmem.nack(w).valid && io.dmem.nack(w).bits.is_hella) {
        hella_state := h_replay
      }
    }
  } .elsewhen (hella_state === h_replay) {
    can_fire_hella_wakeup(memWidth-1) := true.B

    when (will_fire_hella_wakeup(memWidth-1) && dmem_req_fire(memWidth-1)) {
      hella_state := h_wait
    }
  } .elsewhen (hella_state === h_dead) {
    for (w <- 0 until memWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).bits.is_hella) {
        hella_state := h_ready
      }
    }
  }

  //-------------------------------------------------------------
  // Exception / Reset

  // for the live_store_mask, need to kill stores that haven't been committed
  val st_exc_killed_mask = WireInit(VecInit((0 until numStqEntries).map(x=>false.B)))

  when (reset.asBool || io.core.exception)
  {
    ldq_head := 0.U
    ldq_tail := 0.U
    buf_head := 0.U 
    buf_tail := 0.U 

    when (reset.asBool)
    {
      stq_head := 0.U
      // stq_tail := 0.U
      buf_head := 0.U 
      buf_tail := 0.U 
      stq_commit_head  := 0.U
      stq_execute_head := 0.U

      for (i <- 0 until numStqEntries)
      {
        buf(i).bits.st.valid           := false.B
        buf(i).bits.st.bits.addr.valid := false.B
        buf(i).bits.st.bits.data.valid := false.B
        buf(i).bits.st.bits.uop        := NullMicroOp
      }
    }
      .otherwise // exception
    {
      // printf("Line 1706: buf_tail set to stq_commit_head \n");
      buf_tail := stq_commit_head

      for (i <- 0 until numStqEntries)
      {
        when (!buf(i).bits.st.bits.committed && !buf(i).bits.st.bits.succeeded)
        {
          buf(i).valid           := false.B
          buf(i).bits.st.valid           := false.B
          buf(i).bits.st.bits.addr.valid := false.B
          buf(i).bits.st.bits.data.valid := false.B
          st_exc_killed_mask(i)  := true.B
        }
      }
    }

    for (i <- 0 until numLdqEntries)
    {
      buf(i).valid                   := false.B
      buf(i).bits.ld.valid           := false.B
      buf(i).bits.ld.bits.addr.valid := false.B
      buf(i).bits.ld.bits.executed   := false.B
    }
  }

  //-------------------------------------------------------------
  // Live Store Mask
  // track a bit-array of stores that are alive
  // (could maybe be re-produced from the stq_head/stq_tail, but need to know include spec_killed entries)

  // TODO is this the most efficient way to compute the live store mask?
  // live_store_mask := next_live_store_mask &
  //                   ~(st_brkilled_mask.asUInt) &
  //                   ~(st_exc_killed_mask.asUInt)


}

/**
 * Object to take an address and generate an 8-bit mask of which bytes within a
 * double-word.
 */
object GenByteMask
{
   def apply(addr: UInt, size: UInt): UInt =
   {
      val mask = Wire(UInt(8.W))
      mask := MuxCase(255.U(8.W), Array(
                   (size === 0.U) -> (1.U(8.W) << addr(2,0)),
                   (size === 1.U) -> (3.U(8.W) << (addr(2,1) << 1.U)),
                   (size === 2.U) -> Mux(addr(2), 240.U(8.W), 15.U(8.W)),
                   (size === 3.U) -> 255.U(8.W)))
      mask
   }
}

/**
 * ...
 */
class ForwardingAgeLogic(num_entries: Int)(implicit p: Parameters) extends BoomModule()(p)
{
   val io = IO(new Bundle
   {
      val addr_matches    = Input(UInt(num_entries.W)) // bit vector of addresses that match
                                                       // between the load and the SAQ
      val youngest_st_idx = Input(UInt(stqAddrSz.W)) // needed to get "age"

      val forwarding_val  = Output(Bool())
      val forwarding_idx  = Output(UInt(stqAddrSz.W))
   })

   // generating mask that zeroes out anything younger than tail
   val age_mask = Wire(Vec(num_entries, Bool()))
   for (i <- 0 until num_entries)
   {
      age_mask(i) := true.B
      when (i.U >= io.youngest_st_idx) // currently the tail points PAST last store, so use >=
      {
         age_mask(i) := false.B
      }
   }

   // Priority encoder with moving tail: double length
   val matches = Wire(UInt((2*num_entries).W))
   matches := Cat(io.addr_matches & age_mask.asUInt,
                  io.addr_matches)

   val found_match = Wire(Bool())
   found_match       := false.B
   io.forwarding_idx := 0.U

   // look for youngest, approach from the oldest side, let the last one found stick
   for (i <- 0 until (2*num_entries))
   {
      when (matches(i))
      {
         found_match := true.B
         io.forwarding_idx := (i % num_entries).U
      }
   }

   io.forwarding_val := found_match
}
