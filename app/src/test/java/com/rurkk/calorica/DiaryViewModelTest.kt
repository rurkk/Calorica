package com.rurkk.calorica

import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.*
import com.rurkk.calorica.core.navigation.*
import com.rurkk.calorica.core.ui.ReadState
import com.rurkk.calorica.features.diary.*
import java.math.BigDecimal
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {
  private val clock=Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"),ZoneOffset.UTC)
  private val zone=ZoneId.of("Europe/Moscow")
  private val id="00000000-0000-4000-8000-000000000001"
  private val zero=Nutrition(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)
  private val products=object : ProductRepository {
    override suspend fun search(q: String?,limit: Int,cursor: String?)=ApiResult.Success(ProductPage(emptyList(),null))
    override suspend fun get(id: String)=ApiResult.Success(Product(id,null,"Молоко",false,"LEAF",null,zero,
        emptyList(),null,BigDecimal.ONE,"DEFAULT",emptyList()))
    override suspend fun create(request: ProductCreate): ApiResult<Product> = error("not used")
    override suspend fun patch(id: String,request: ProductPatch): ApiResult<Product> = error("not used")
    override suspend fun writeComposition(id: String,request: CompositionWrite): ApiResult<Product> = error("not used")
    override suspend fun delete(id: String,mode: String)=ApiResult.Success(Unit)
  }
  private inner class Repo : DiaryRepository {
    var reads=0; var writes=0; var deletes=0
    var received: DiaryWrite?=null
    var patched: DiaryPatch?=null
    var gate: CompletableDeferred<Unit>?=null
    var result: ApiResult<DiaryEntry> = ApiResult.Success(entry())
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var readResult: ApiResult<DiaryDay>?=null
    override suspend fun mealLabels()=ApiResult.Success(listOf(MealLabel(id,"Завтрак")))
    override suspend fun day(date: String,zone: String): ApiResult<DiaryDay> {
      reads++; return readResult ?: ApiResult.Success(DiaryDay(date,zone,emptyList(),zero))
    }
    override suspend fun create(request: DiaryWrite): ApiResult<DiaryEntry> {
      writes++; received=request; gate?.await(); return result
    }
    override suspend fun patch(id: String,request: DiaryPatch): ApiResult<DiaryEntry> {
      writes++; patched=request; gate?.await(); return result
    }
    override suspend fun delete(id: String): ApiResult<Unit> { deletes++; gate?.await(); return deleteResult }
  }
  private fun entry()=DiaryEntry(id,id,"Молоко",false,BigDecimal("200"),"2026-11-03T08:00:00Z",listOf(id),zero)
  private fun fill(vm: DiaryViewModel) {
    vm.newEntry(); vm.update { it.copy(productId=id,productName="Молоко",grams="200") }
  }
  private fun onMain(block: suspend TestScope.() -> Unit)=runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try { block() } finally { Dispatchers.resetMain() }
  }
  private fun withDeviceZone(block: suspend TestScope.() -> Unit)=onMain {
    val previous=TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    try { block() } finally { TimeZone.setDefault(previous) }
  }
  @Test fun todayUsesLocalCalendarAndEmptyDiffersFromFailure()=onMain {
    val repo=Repo(); val vm=DiaryViewModel(repo,products,clock,zone)
    vm.start(); runCurrent()
    assertEquals(LocalDate.parse("2026-11-03"),vm.state.value.date)
    assertTrue(vm.state.value.day is ReadState.Empty)
    repo.readResult=ApiResult.Failure(ApiError(ApiError.Kind.NETWORK))
    vm.reload(); runCurrent(); assertTrue(vm.state.value.day is ReadState.Error)
  }
  @Test fun resumeUsesCurrentSystemZoneForSelectedDayAndNewEntry()=onMain {
    val previous=TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      val requests=mutableListOf<Pair<String,String>>()
      val base=Repo()
      val repo=object : DiaryRepository by base {
        override suspend fun day(date:String,zone:String):ApiResult<DiaryDay> {
          requests += date to zone
          return base.day(date,zone)
        }
      }
      val vm=DiaryViewModel(repo,products,clock)
      vm.resumeReads(); runCurrent()
      vm.selectDate(LocalDate.parse("2026-11-01")); runCurrent()
      vm.pauseReads()
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
      vm.resumeReads(); runCurrent()
      assertEquals(ZoneId.of("Asia/Tokyo"),vm.state.value.zone)
      assertEquals("2026-11-01" to "Asia/Tokyo",requests.last())
      assertEquals("Asia/Tokyo",(vm.state.value.day as ReadState.Empty<DiaryDay>).value.zone)
      vm.newEntry()
      assertEquals("2026-11-01",vm.state.value.form!!.date)
      assertEquals("17:00",vm.state.value.form!!.time)
    } finally { TimeZone.setDefault(previous) }
  }
  @Test fun changingZoneDuringEditKeepsTheEntryInstantAndOtherDraftFields()=withDeviceZone {
    val repo=Repo(); val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent()
    vm.edit(entry()); vm.update { it.copy(grams="50",mealLabelIds=emptySet()) }
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    vm.resumeReads(); runCurrent()
    val form=vm.state.value.form!!
    assertEquals("2026-11-02",form.date); assertEquals("22:00",form.time)
    assertEquals("50",form.grams); assertTrue(form.mealLabelIds.isEmpty())
    assertEquals(LocalDate.parse("2026-11-03"),vm.state.value.date)
    vm.save(); runCurrent()
    assertEquals("\"2026-11-03T08:00:00Z\"",repo.patched!!.json()["occurredAt"].toString())
    assertEquals(LocalDate.parse("2026-11-02"),vm.state.value.date)
  }
  @Test fun newDraftKeepsItsInstantWhenRebasedIntoSecondDstOccurrence()=withDeviceZone {
    val repo=Repo(); val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); fill(vm)
    vm.update { it.copy(date="2026-10-25",time="04:30") }
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    vm.resumeReads(); runCurrent()
    val form=vm.state.value.form!!
    assertNull(form.id); assertEquals("2026-10-25",form.date); assertEquals("02:30",form.time)
    assertEquals("200",form.grams); assertEquals(id,form.productId)
    vm.save(); runCurrent()
    assertEquals("2026-10-25T01:30:00Z",repo.received!!.occurredAt)
  }
  @Test fun invalidOldZoneTimeCannotSilentlyBecomeValidAfterZoneChange()=withDeviceZone {
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    val repo=Repo(); val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); fill(vm)
    vm.update { it.copy(date="2026-03-29",time="02:30") }
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    vm.resumeReads(); runCurrent()
    assertEquals("02:30",vm.state.value.form!!.time)
    assertTrue(vm.state.value.form!!.timeNeedsReview)
    vm.update { it.copy(grams="50") }; vm.save(); runCurrent()
    assertEquals(0,repo.writes)
    assertTrue(vm.state.value.form!!.error!!.contains("Часовой пояс изменился"))
    vm.update { it.copy(time="03:30") }; vm.save(); runCurrent()
    assertEquals(1,repo.writes)
    assertEquals("2026-03-28T18:30:00Z",repo.received!!.occurredAt)
  }
  @Test fun pendingWriteUsesItsInstantInTheNewZoneWhenCheckingUncertainResult()=withDeviceZone {
    val repo=Repo().apply { gate=CompletableDeferred(); result=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) }
    val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); fill(vm); vm.save(); runCurrent()
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    vm.resumeReads(); runCurrent()
    repo.gate!!.complete(Unit); runCurrent()
    assertEquals(1,repo.writes); assertEquals("2026-11-03T08:00:00Z",repo.received!!.occurredAt)
    assertTrue(vm.state.value.uncertain)
    assertEquals(LocalDate.parse("2026-11-02"),vm.state.value.date)
    val day=(vm.state.value.day as ReadState.Empty<DiaryDay>).value
    assertEquals("2026-11-02",day.date); assertEquals("Pacific/Honolulu",day.zone)
    vm.resumeReads(); runCurrent(); assertEquals(1,repo.writes)
  }
  @Test fun completedUncertainWriteFollowsItsUtcDayUntilAcknowledged()=withDeviceZone {
    val repo=Repo().apply { result=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) }
    val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); fill(vm); vm.save(); runCurrent()
    assertTrue(vm.state.value.uncertain); assertNull(vm.state.value.form)
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    vm.resumeReads()
    vm.acknowledgeUncertain(); assertTrue(vm.state.value.uncertain)
    runCurrent()
    assertEquals(LocalDate.parse("2026-11-02"),vm.state.value.date)
    assertEquals("2026-11-02",(vm.state.value.day as ReadState.Empty<DiaryDay>).value.date)
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    vm.resumeReads(); runCurrent()
    assertEquals(LocalDate.parse("2026-11-03"),vm.state.value.date)
    vm.acknowledgeUncertain(); assertFalse(vm.state.value.uncertain)
    vm.selectDate(LocalDate.parse("2026-10-01")); runCurrent(); vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    vm.resumeReads(); runCurrent()
    assertEquals(LocalDate.parse("2026-10-01"),vm.state.value.date)
    assertEquals(1,repo.writes)
  }
  @Test fun patchBecomingUncertainWhilePausedIsCheckedInCurrentZoneOnReturn()=withDeviceZone {
    val repo=Repo().apply { gate=CompletableDeferred(); result=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) }
    val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); vm.edit(entry())
    vm.update { it.copy(grams="50") }; vm.save(); runCurrent(); vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    repo.gate!!.complete(Unit); runCurrent()
    assertTrue(vm.state.value.uncertain); assertEquals(ReadState.Idle,vm.state.value.day)
    vm.resumeReads(); runCurrent()
    assertEquals(LocalDate.parse("2026-11-02"),vm.state.value.date)
    val day=(vm.state.value.day as ReadState.Empty<DiaryDay>).value
    assertEquals("2026-11-02",day.date); assertEquals("Pacific/Honolulu",day.zone)
    assertEquals("\"2026-11-03T08:00:00Z\"",repo.patched!!.json()["occurredAt"].toString())
    vm.save(); runCurrent(); assertEquals(1,repo.writes)
  }
  @Test fun uncertainDeletionKeepsEntryInstantAndRejectsLateOldZoneCheck()=withDeviceZone {
    val old=CompletableDeferred<Unit>()
    var holdOldCheck=false
    val base=Repo().apply { deleteResult=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) }
    val repo=object : DiaryRepository by base {
      override suspend fun day(date:String,zone:String):ApiResult<DiaryDay> {
        if(holdOldCheck && zone=="Europe/Moscow") withContext(NonCancellable) { old.await() }
        return base.day(date,zone)
      }
    }
    val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); holdOldCheck=true
    vm.delete(entry()); runCurrent(); assertTrue(vm.state.value.uncertain)
    vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    vm.resumeReads(); vm.acknowledgeUncertain(); assertTrue(vm.state.value.uncertain)
    runCurrent(); old.complete(Unit); runCurrent()
    val day=(vm.state.value.day as ReadState.Empty<DiaryDay>).value
    assertEquals("2026-11-02",day.date); assertEquals("Pacific/Honolulu",day.zone)
    vm.delete(entry()); runCurrent(); assertEquals(1,base.deletes)
    vm.acknowledgeUncertain(); assertFalse(vm.state.value.uncertain)
  }
  @Test fun uncertainWriteCanOnlyBeAcknowledgedAfterReadingItsCurrentDayAndZone()=onMain {
    val repo=Repo().apply {
      result=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT))
      readResult=ApiResult.Success(DiaryDay("2026-11-04",zone.id,emptyList(),zero))
    }
    val vm=DiaryViewModel(repo,products,clock,zone)
    fill(vm); vm.save(); runCurrent(); vm.acknowledgeUncertain()
    assertTrue(vm.state.value.uncertain)
    repo.readResult=ApiResult.Success(DiaryDay("2026-11-03","Asia/Tokyo",emptyList(),zero))
    vm.reload(); runCurrent(); vm.acknowledgeUncertain(); assertTrue(vm.state.value.uncertain)
    repo.readResult=null
    vm.reload(); runCurrent(); vm.acknowledgeUncertain(); assertFalse(vm.state.value.uncertain)
  }
  @Test fun lateOldZoneResponseCannotReplaceDayLoadedAfterResume()=withDeviceZone {
    val old=CompletableDeferred<Unit>()
    val base=Repo()
    val repo=object : DiaryRepository by base {
      override suspend fun day(date:String,zone:String):ApiResult<DiaryDay> {
        if(zone=="Europe/Moscow") withContext(NonCancellable) { old.await() }
        return ApiResult.Success(DiaryDay(date,zone,emptyList(),zero))
      }
    }
    val vm=DiaryViewModel(repo,products,clock)
    vm.resumeReads(); runCurrent(); vm.pauseReads()
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    vm.resumeReads(); runCurrent()
    old.complete(Unit); runCurrent()
    assertEquals("Asia/Tokyo",(vm.state.value.day as ReadState.Empty<DiaryDay>).value.zone)
  }
  @Test fun validationUsesExactGramsAndConvertsLocalTimeToUtc() {
    val form=DiaryForm(productId=id,grams="12.345",date="2026-11-03",time="11:00")
    val write=DiaryViewModel.validated(form,zone)
    assertEquals(BigDecimal("12.345"),write.quantity); assertEquals("g",write.unit)
    assertEquals("2026-11-03T08:00:00Z",write.occurredAt)
    for(value in listOf("","0","-1","1.0001","1e2","10000000","NaN")) {
      assertThrows(IllegalArgumentException::class.java) { DiaryViewModel.validated(form.copy(grams=value),zone) }
    }
  }
  @Test fun dstGapIsRejectedAndUnchangedOverlapKeepsOriginalInstant() {
    val berlin=ZoneId.of("Europe/Berlin")
    val form=DiaryForm(productId=id,grams="1",date="2026-03-29",time="02:30")
    assertThrows(IllegalArgumentException::class.java) { DiaryViewModel.validated(form,berlin) }
    val overlap=form.copy(date="2026-10-25",originalOccurredAt="2026-10-25T01:30:00Z")
    assertEquals("2026-10-25T01:30:00Z",DiaryViewModel.validated(overlap,berlin).occurredAt)
  }
  @Test fun repeatedLocalTimeUsesFirstOccurrenceForNewOrChangedTime() {
    val berlin=ZoneId.of("Europe/Berlin")
    val form=DiaryForm(productId=id,grams="1",date="2026-10-25",time="02:30")
    assertEquals("2026-10-25T00:30:00Z",DiaryViewModel.validated(form,berlin).occurredAt)
    assertEquals("2026-10-25T00:30:00Z",DiaryViewModel.validated(form.copy(offsetSeconds=3600),berlin).occurredAt)
  }
  @Test fun doubleSaveIsOneWriteAndServerDayIsReloadedWithoutLocalSum()=onMain {
    val repo=Repo().apply { gate=CompletableDeferred() }
    val serverTotal=zero.copy(calories=BigDecimal("123.456789"))
    repo.readResult=ApiResult.Success(DiaryDay("2026-11-03",zone.id,listOf(entry()),serverTotal))
    val vm=DiaryViewModel(repo,products,clock,zone)
    fill(vm); vm.save(); vm.save(); runCurrent(); assertEquals(1,repo.writes)
    assertTrue(vm.state.value.busy)
    repo.gate!!.complete(Unit); runCurrent()
    assertEquals(1,repo.reads); assertNull(vm.state.value.form)
    assertEquals(serverTotal,(vm.state.value.day as ReadState.Data<DiaryDay>).value.total)
  }
  @Test fun unknownWriteIsNeverRetriedAndRequiresReadAndExplicitAcknowledgement()=onMain {
    val repo=Repo().apply { result=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)) }
    val vm=DiaryViewModel(repo,products,clock,zone)
    fill(vm); vm.save(); runCurrent()
    assertTrue(vm.state.value.uncertain); assertNull(vm.state.value.form)
    vm.save(); vm.newEntry(); runCurrent(); assertEquals(1,repo.writes); assertNull(vm.state.value.form)
    vm.acknowledgeUncertain(); assertFalse(vm.state.value.uncertain)
    vm.newEntry(); assertNotNull(vm.state.value.form)
  }
  @Test fun editSendsAllMutableFieldsIncludingEmptyLabelsAndDateMove()=onMain {
    val repo=Repo(); val vm=DiaryViewModel(repo,products,clock,zone)
    vm.edit(entry()); vm.update { it.copy(grams="50",date="2026-11-04",mealLabelIds=emptySet()) }
    vm.save(); runCurrent()
    val patch=repo.patched!!.json()
    assertEquals("[]",patch["mealLabelIds"].toString())
    assertEquals("\"50\"",patch["quantity"].toString())
    assertEquals(LocalDate.parse("2026-11-04"),vm.state.value.date)
  }
  @Test fun sharedPickerOnlyConsumesMatchingRequestAndDoesNotFillRecipeMass()=onMain {
    val vm=DiaryViewModel(Repo(),products,clock,zone)
    vm.newEntry(); val request=vm.chooseProduct()!!
    vm.acceptProduct(ProductSelectionResult(ProductSelectionRequest.create(ProductSelectionPurpose.DIARY),ProductSelection(id)))
    runCurrent(); assertNull(vm.state.value.form!!.productId)
    vm.acceptProduct(ProductSelectionResult(request,ProductSelection(id)))
    runCurrent(); assertEquals(id,vm.state.value.form!!.productId); assertEquals("",vm.state.value.form!!.grams)
  }
  @Test fun selectedReplacementCannotSaveOldProductWhileLookupIsPending()=onMain {
    val gate=CompletableDeferred<Unit>()
    val delayed=object : ProductRepository by products {
      override suspend fun get(id:String):ApiResult<Product> { gate.await(); return products.get(id) }
    }
    val repo=Repo(); val vm=DiaryViewModel(repo,delayed,clock,zone)
    vm.edit(entry())
    val request=vm.chooseProduct()!!
    vm.acceptProduct(ProductSelectionResult(request,ProductSelection(id)))
    assertTrue(vm.state.value.resolvingProduct)
    assertNull(vm.state.value.form!!.productId)
    vm.save(); runCurrent(); assertEquals(0,repo.writes)
    assertNull(vm.chooseProduct())
    gate.complete(Unit); runCurrent()
    assertFalse(vm.state.value.resolvingProduct)
    assertEquals(id,vm.state.value.form!!.productId)
    vm.save(); runCurrent(); assertEquals(1,repo.writes)
  }
  @Test fun cancellationOfPickerFormRejectsLateProductLookup()=onMain {
    val gate=CompletableDeferred<Unit>()
    val delayed=object : ProductRepository by products {
      override suspend fun get(id:String):ApiResult<Product> { withContext(NonCancellable) { gate.await() }; return products.get(id) }
    }
    val vm=DiaryViewModel(Repo(),delayed,clock,zone)
    vm.newEntry(); val request=vm.chooseProduct()!!
    vm.acceptProduct(ProductSelectionResult(request,ProductSelection(id))); runCurrent()
    vm.closeForm(); vm.newEntry()
    gate.complete(Unit); runCurrent()
    assertNull(vm.state.value.form!!.productId)
    assertFalse(vm.state.value.resolvingProduct)
  }
  @Test fun uncertainWriteCannotBeAcknowledgedThroughAnUnrelatedDay()=onMain {
    val repo=Repo().apply { result=ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT)); readResult=ApiResult.Failure(ApiError(ApiError.Kind.NETWORK)) }
    val vm=DiaryViewModel(repo,products,clock,zone)
    fill(vm); vm.save(); runCurrent()
    assertTrue(vm.state.value.uncertain)
    val original=vm.state.value.date
    vm.selectDate(original.plusDays(1)); runCurrent()
    assertEquals(original,vm.state.value.date)
    vm.acknowledgeUncertain(); assertTrue(vm.state.value.uncertain)
    repo.readResult=null; vm.reload(); runCurrent(); vm.acknowledgeUncertain()
    assertFalse(vm.state.value.uncertain)
  }
  @Test fun deletionReloadsTheServerDay()=onMain {
    val repo=Repo(); val vm=DiaryViewModel(repo,products,clock,zone)
    vm.delete(entry()); vm.delete(entry()); runCurrent()
    assertEquals(1,repo.deletes); assertEquals(1,repo.reads)
  }
  @Test fun leavingDiaryCancelsReadsAndReturningReloadsWithoutReplayingWrites()=onMain {
    val base=Repo()
    var cancelledDay=false
    var cancelledLabels=false
    var pausedProbe=true
    val repo=object : DiaryRepository by base {
      override suspend fun day(date:String,zone:String):ApiResult<DiaryDay> {
        if(pausedProbe) try { awaitCancellation() } finally { cancelledDay=true }
        return base.day(date,zone)
      }
      override suspend fun mealLabels():ApiResult<List<MealLabel>> {
        if(pausedProbe) try { awaitCancellation() } finally { cancelledLabels=true }
        return base.mealLabels()
      }
    }
    val vm=DiaryViewModel(repo,products,clock,zone)
    vm.resumeReads(); vm.resumeReads(); runCurrent()
    vm.pauseReads(); runCurrent()
    assertTrue(cancelledDay); assertTrue(cancelledLabels)
    assertEquals(ReadState.Idle,vm.state.value.day)
    assertEquals(ReadState.Idle,vm.state.value.labels)
    pausedProbe=false
    vm.resumeReads(); runCurrent()
    assertTrue(vm.state.value.day is ReadState.Empty)
    assertTrue(vm.state.value.labels is ReadState.Data)
    assertEquals(0,base.writes)
  }
  @Test fun leavingDiaryDoesNotCancelOrRepeatPendingWrite()=onMain {
    val repo=Repo().apply { gate=CompletableDeferred() }
    val vm=DiaryViewModel(repo,products,clock,zone)
    fill(vm); vm.save(); runCurrent()
    vm.pauseReads()
    repo.gate!!.complete(Unit); runCurrent()
    assertEquals(1,repo.writes); assertEquals(0,repo.reads)
    vm.resumeReads(); runCurrent()
    assertEquals(1,repo.writes); assertEquals(1,repo.reads)
  }
  @Test fun lateResponseCannotReplaceTheNewlySelectedDay()=onMain {
    val old=CompletableDeferred<Unit>()
    val base=Repo()
    val repo=object : DiaryRepository by base {
      override suspend fun day(date: String,zone: String): ApiResult<DiaryDay> {
        if(date=="2026-11-03") withContext(NonCancellable) { old.await() }
        return ApiResult.Success(DiaryDay(date,zone,emptyList(),zero))
      }
    }
    val vm=DiaryViewModel(repo,products,clock,zone)
    vm.reload(); runCurrent()
    vm.selectDate(LocalDate.parse("2026-11-04")); runCurrent()
    old.complete(Unit); runCurrent()
    assertEquals("2026-11-04",(vm.state.value.day as ReadState.Empty<DiaryDay>).value.date)
  }
}
