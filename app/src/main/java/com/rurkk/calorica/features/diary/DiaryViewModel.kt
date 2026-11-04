package com.rurkk.calorica.features.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.DiaryRepository
import com.rurkk.calorica.core.data.ProductRepository
import com.rurkk.calorica.core.navigation.ProductSelectionPurpose
import com.rurkk.calorica.core.navigation.ProductSelectionRequest
import com.rurkk.calorica.core.navigation.ProductSelectionResult
import com.rurkk.calorica.core.ui.ReadState
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiaryForm(
    val id: String? = null, val productId: String? = null, val productName: String = "",
    val grams: String = "", val date: String, val time: String,
    val mealLabelIds: Set<String> = emptySet(), val pickerRequest: ProductSelectionRequest? = null,
    val error: String? = null, val originalOccurredAt: String? = null, val offsetSeconds: Int? = null,
    val timeNeedsReview: Boolean = false,
)
data class DiaryState(
    val date: LocalDate, val zone: ZoneId, val day: ReadState<DiaryDay> = ReadState.Idle,
    val labels: ReadState<List<MealLabel>> = ReadState.Idle, val form: DiaryForm? = null,
    val busy: Boolean = false, val uncertain: Boolean = false, val resolvingProduct: Boolean = false, val message: String? = null,
    val correlationId: String? = null,
)

/** All totals are server values. Local arithmetic is limited to input validation and calendar conversion. */
class DiaryViewModel(private val repository: DiaryRepository, private val products: ProductRepository,
    private val clock: Clock = Clock.systemDefaultZone(), zone: ZoneId? = null) : ViewModel() {
  // An explicitly supplied zone is a fixed test input; production resolves the device zone on resume.
  private val currentZone = { zone ?: ZoneId.systemDefault() }
  private val initialZone = currentZone()
  private val mutable = MutableStateFlow(DiaryState(LocalDate.now(clock.withZone(initialZone)), initialZone))
  val state = mutable.asStateFlow()
  private var dayJob: Job? = null
  private var labelsJob: Job? = null
  private var pickerJob: Job? = null
  private var labelsRevision = 0
  private var readsPaused = false
  private var visible = false
  private var readRevision = 0
  private var pickerRevision = 0
  private var initialized = false

  fun resumeReads() {
    val input = state.value
    val nextZone = currentZone()
    if (visible && nextZone == input.zone) return
    if (nextZone != input.zone) {
      // A selected calendar date stays selected; draft instants are displayed in the new zone.
      mutable.value = input.copy(zone = nextZone, form = input.form?.inZone(input.zone, nextZone))
    }
    visible = true
    readsPaused = false
    if (!initialized) start() else { reload(); loadLabels() }
  }
  fun pauseReads() {
    visible = false
    readsPaused = true
    readRevision++; labelsRevision++; pickerRevision++
    dayJob?.cancel(); labelsJob?.cancel(); pickerJob?.cancel()
    val input = state.value
    mutable.value = input.copy(day = ReadState.Idle,
        labels = if (input.labels == ReadState.Loading) ReadState.Idle else input.labels,
        resolvingProduct = false,
        form = if (input.resolvingProduct) input.form?.copy(error="Выбор продукта не завершён. Выберите продукт ещё раз.") else input.form)
  }

  fun start() {
    if (initialized) return
    initialized = true
    reload(); loadLabels()
  }
  fun loadLabels() {
    if (readsPaused || state.value.labels == ReadState.Loading) return
    val revision = ++labelsRevision
    mutable.value = state.value.copy(labels = ReadState.Loading)
    labelsJob = viewModelScope.launch {
      val result = repository.mealLabels()
      if (labelsRevision != revision) return@launch
      mutable.value = state.value.copy(labels = when (result) {
        is ApiResult.Success -> ReadState.Data(result.value)
        is ApiResult.Failure -> ReadState.Error(result.error)
      })
    }
  }
  fun selectDate(date: LocalDate) {
    if (state.value.busy || state.value.uncertain) return
    pickerRevision++
    pickerJob?.cancel()
    mutable.value = state.value.copy(date = date, form = null, resolvingProduct = false)
    reload()
  }
  fun reload() {
    if (readsPaused) return
    val revision = ++readRevision
    dayJob?.cancel()
    val input = state.value
    mutable.value = input.copy(day = ReadState.Loading)
    dayJob = viewModelScope.launch {
      val result = repository.day(input.date.toString(), input.zone.id)
      if (readRevision != revision) return@launch
      mutable.value = state.value.copy(day = when (result) {
        is ApiResult.Success -> if (result.value.entries.isEmpty()) ReadState.Empty(result.value) else ReadState.Data(result.value)
        is ApiResult.Failure -> ReadState.Error(result.error)
      })
    }
  }
  fun newEntry() {
    if (state.value.busy || state.value.uncertain || state.value.resolvingProduct) return
    pickerRevision++
    val now = LocalTime.now(clock.withZone(state.value.zone)).withSecond(0).withNano(0)
    mutable.value = state.value.copy(form = DiaryForm(date = state.value.date.toString(), time = now.toString()), message = null)
  }
  fun edit(entry: DiaryEntry) {
    if (state.value.busy || state.value.uncertain || state.value.resolvingProduct) return
    pickerRevision++
    val local = Instant.parse(entry.occurredAt).atZone(state.value.zone)
    mutable.value = state.value.copy(form = DiaryForm(entry.id,entry.productId,entry.productName,
        entry.consumedGrams.toPlainString(),local.toLocalDate().toString(),local.toLocalTime().toString(),entry.mealLabelIds.toSet(),
        originalOccurredAt=entry.occurredAt,offsetSeconds=local.offset.totalSeconds), message = null)
  }
  fun update(transform: (DiaryForm) -> DiaryForm) {
    val current=state.value
    if (!current.busy && !current.uncertain) current.form?.let { previous ->
      val changed = transform(previous)
      val needsReview = previous.timeNeedsReview && changed.date == previous.date && changed.time == previous.time
      mutable.value=current.copy(form=changed.copy(timeNeedsReview=needsReview,error=if(needsReview) previous.error else null))
    }
  }
  fun closeForm() {
    if (state.value.busy) return
    pickerRevision++
    pickerJob?.cancel()
    mutable.value=state.value.copy(form=null,resolvingProduct=false)
  }
  fun chooseProduct(): ProductSelectionRequest? {
    val form=state.value.form ?: return null
    if (state.value.busy || state.value.uncertain || state.value.resolvingProduct) return null
    val request=ProductSelectionRequest.create(ProductSelectionPurpose.DIARY)
    mutable.value=state.value.copy(form=form.copy(pickerRequest=request))
    return request
  }
  fun acceptProduct(result: ProductSelectionResult) {
    val form=state.value.form ?: return
    if (form.pickerRequest != result.request || state.value.busy || state.value.uncertain || state.value.resolvingProduct) return
    val revision=++pickerRevision
    // The requested replacement is not the old product. Prevent a save while its GET is pending.
    mutable.value=state.value.copy(form=form.copy(pickerRequest=null,productId=null,productName=""),resolvingProduct=true)
    pickerJob = viewModelScope.launch {
      val product=products.get(result.selection.productId)
      if (revision != pickerRevision || state.value.form == null) return@launch
      val current=state.value.form!!
      mutable.value=state.value.copy(resolvingProduct=false,form=when(product) {
        is ApiResult.Success -> if (product.value.isDeleted) current.copy(error="Этот продукт удалён. Выберите другой.")
          else current.copy(productId=product.value.id,productName=product.value.name,error=null)
        is ApiResult.Failure -> current.copy(error=product.error.message())
      })
    }
  }
  fun toggleLabel(id: String) = update { it.copy(mealLabelIds=if (id in it.mealLabelIds) it.mealLabelIds-id else it.mealLabelIds+id) }

  fun save() {
    val input=state.value
    val form=input.form ?: return
    if (input.busy || input.uncertain || input.resolvingProduct) return
    val write=try { validated(form,input.zone) } catch (error: IllegalArgumentException) {
      mutable.value=input.copy(form=form.copy(error=error.message)); return
    }
    mutable.value=input.copy(busy=true,message=null,correlationId=null)
    viewModelScope.launch {
      try {
        val result=if (form.id == null) repository.create(write) else repository.patch(form.id,DiaryPatch(
            productId=PatchField.Set(write.productId),quantity=PatchField.Set(write.quantity),unit=PatchField.Set("g"),
            occurredAt=PatchField.Set(write.occurredAt),mealLabelIds=PatchField.Set(write.mealLabelIds)))
        // A write can finish after a pause and a device-zone change. Follow its original UTC instant.
        mutable.value=state.value.copy(date=Instant.parse(write.occurredAt).atZone(state.value.zone).toLocalDate())
        mutationResult(result)
      } finally { mutable.value=state.value.copy(busy=false) }
    }
  }
  fun delete(entry: DiaryEntry) {
    if (state.value.busy || state.value.uncertain || state.value.resolvingProduct) return
    mutable.value=state.value.copy(busy=true,message=null,correlationId=null)
    viewModelScope.launch {
      try { mutationResult(repository.delete(entry.id)) } finally { mutable.value=state.value.copy(busy=false) }
    }
  }
  private fun mutationResult(result: ApiResult<*>) {
    mutable.value=when(result) {
      is ApiResult.Success -> state.value.copy(form=null,message="Данные сохранены.",uncertain=false)
      is ApiResult.Failure -> {
        val uncertain=result.error.kind != ApiError.Kind.HTTP || (result.error.status ?: 500) >= 500
        state.value.copy(form=if(uncertain) null else state.value.form,
            uncertain=uncertain,message=result.error.writeMessage(),correlationId=result.error.correlationId)
      }
    }
    // A read is safe even when the result of a write is unknown. Never repeat the write.
    reload()
  }
  fun acknowledgeUncertain() {
    if (!state.value.busy && (state.value.day is ReadState.Data || state.value.day is ReadState.Empty))
      mutable.value=state.value.copy(uncertain=false,message=null)
  }

  companion object {
    private fun DiaryForm.inZone(previousZone: ZoneId, nextZone: ZoneId): DiaryForm {
      if (timeNeedsReview) return this
      val instant = try { occurredAt(this, previousZone) } catch (_: IllegalArgumentException) {
        return copy(timeNeedsReview=true,error="Часовой пояс изменился. Проверьте и исправьте дату или время.")
      }
      val local = instant.atZone(nextZone)
      return copy(date=local.toLocalDate().toString(),time=local.toLocalTime().toString(),
          // Anchor the displayed draft instant, including the second occurrence of a DST overlap.
          originalOccurredAt=instant.toString(),offsetSeconds=local.offset.totalSeconds)
    }

    private fun occurredAt(form: DiaryForm, zone: ZoneId): Instant {
      val local=try { LocalDateTime.of(LocalDate.parse(form.date),LocalTime.parse(form.time)) }
        catch (_: DateTimeException) { throw IllegalArgumentException("Введите дату ГГГГ-ММ-ДД и время ЧЧ:ММ.") }
      val offsets=zone.rules.getValidOffsets(local)
      require(offsets.isNotEmpty()) { "Это время отсутствует из-за перехода на летнее время. Выберите другое." }
      val previous=form.originalOccurredAt?.let(Instant::parse)
      val originalLocal=previous?.atZone(zone)?.toLocalDateTime()
      return if (previous != null && originalLocal == local) previous else {
        ZonedDateTime.ofLocal(local,zone,offsets.first()).toInstant()
      }
    }

    fun validated(form: DiaryForm, zone: ZoneId): DiaryWrite {
      val productId=form.productId ?: throw IllegalArgumentException("Выберите продукт.")
      require(Regex("(0|[1-9][0-9]*)(\\.[0-9]{1,3})?").matches(form.grams) && form.grams.length<=11) {
        "Введите массу в граммах, не более 3 знаков после точки."
      }
      val grams=form.grams.toBigDecimal()
      require(grams>BigDecimal.ZERO && grams<=BigDecimal("9999999.999")) { "Масса должна быть больше нуля и не больше 9999999.999 г." }
      require(!form.timeNeedsReview) { "Часовой пояс изменился. Проверьте и исправьте дату или время." }
      val instant=occurredAt(form,zone)
      require(form.mealLabelIds.size<=4) { "Можно выбрать не более четырёх пищевых лейблов." }
      return DiaryWrite(productId,grams,"g",instant.toString(),form.mealLabelIds.sorted())
    }
  }
}
