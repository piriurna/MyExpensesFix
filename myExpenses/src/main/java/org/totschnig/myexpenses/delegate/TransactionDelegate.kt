package org.totschnig.myexpenses.delegate

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.core.view.isVisible
import com.evernote.android.state.State
import com.evernote.android.state.StateSaver
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.ExpenseEdit
import org.totschnig.myexpenses.activity.HELP_VARIANT_SPLIT_PART_CATEGORY
import org.totschnig.myexpenses.activity.HELP_VARIANT_TEMPLATE_CATEGORY
import org.totschnig.myexpenses.activity.HELP_VARIANT_TRANSACTION
import org.totschnig.myexpenses.adapter.CrStatusAdapter
import org.totschnig.myexpenses.adapter.IdAdapter
import org.totschnig.myexpenses.adapter.NothingSelectedSpinnerAdapter
import org.totschnig.myexpenses.adapter.RecurrenceAdapter
import org.totschnig.myexpenses.contract.TransactionsContract.Transactions.TYPE_SPLIT
import org.totschnig.myexpenses.contract.TransactionsContract.Transactions.TYPE_TRANSACTION
import org.totschnig.myexpenses.contract.TransactionsContract.Transactions.TYPE_TRANSFER
import org.totschnig.myexpenses.databinding.DateEditBinding
import org.totschnig.myexpenses.databinding.MethodRowBinding
import org.totschnig.myexpenses.databinding.OneExpenseBinding
import org.totschnig.myexpenses.di.AppComponent
import org.totschnig.myexpenses.model.*
import org.totschnig.myexpenses.model.PreDefinedPaymentMethod.Companion.translateIfPredefined
import org.totschnig.myexpenses.myApplication
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.preference.PrefKey
import org.totschnig.myexpenses.provider.DatabaseConstants
import org.totschnig.myexpenses.ui.AmountInput
import org.totschnig.myexpenses.ui.DateButton
import org.totschnig.myexpenses.ui.MyTextWatcher
import org.totschnig.myexpenses.ui.SpinnerHelper
import org.totschnig.myexpenses.util.*
import org.totschnig.myexpenses.util.TextUtils.appendCurrencyDescription
import org.totschnig.myexpenses.util.TextUtils.appendCurrencySymbol
import org.totschnig.myexpenses.util.locale.HomeCurrencyProvider
import org.totschnig.myexpenses.util.ui.UiUtils
import org.totschnig.myexpenses.util.ui.addChipsBulk
import org.totschnig.myexpenses.util.ui.getDateMode
import org.totschnig.myexpenses.util.ui.validateAmountInput
import org.totschnig.myexpenses.viewmodel.data.Account
import org.totschnig.myexpenses.viewmodel.data.Currency
import org.totschnig.myexpenses.viewmodel.data.IIconInfo
import org.totschnig.myexpenses.viewmodel.data.PaymentMethod
import org.totschnig.myexpenses.viewmodel.data.Tag
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

abstract class TransactionDelegate<T : ITransaction>(
    val viewBinding: OneExpenseBinding,
    private val dateEditBinding: DateEditBinding,
    private val methodRowBinding: MethodRowBinding,
    val isTemplate: Boolean
) : AdapterView.OnItemSelectedListener {

    @State
    var label: String? = null

    @State
    var categoryIcon: String? = null

    @State
    var catId: Long? = null

    @Inject
    lateinit var prefHandler: PrefHandler

    @Inject
    lateinit var currencyFormatter: ICurrencyFormatter

    @Inject
    lateinit var currencyContext: CurrencyContext

    @Inject
    lateinit var homeCurrencyProvider: HomeCurrencyProvider

    val homeCurrency by lazy {
        homeCurrencyProvider.homeCurrencyUnit
    }

    private val methodSpinner = SpinnerHelper(methodRowBinding.Method.MethodSpinner)
    val accountSpinner = SpinnerHelper(viewBinding.Account)
    private val statusSpinner = SpinnerHelper(viewBinding.Status)
    private val operationTypeSpinner = SpinnerHelper(viewBinding.toolbar.OperationType)
    val recurrenceSpinner = SpinnerHelper(viewBinding.Recurrence)
    private lateinit var methodsAdapter: ArrayAdapter<PaymentMethod>
    private lateinit var operationTypeAdapter: ArrayAdapter<OperationType>

    init {
        createMethodAdapter()
        viewBinding.advanceExecutionSeek.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                seekBar.requestFocusFromTouch() //prevent jump to first EditText https://stackoverflow.com/a/6177270/1199911
                viewBinding.advanceExecutionValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }

        })
    }

    open val helpVariant: String
        get() = when {
            isTemplate -> HELP_VARIANT_TEMPLATE_CATEGORY
            isSplitPart -> HELP_VARIANT_SPLIT_PART_CATEGORY
            else -> HELP_VARIANT_TRANSACTION
        }

    fun title(newInstance: Boolean) = with(context) {
        if (newInstance) {
            labelForNewInstance(operationType)
        } else {
            when {
                isTemplate -> getString(R.string.menu_edit_template) + " (" + getString(typeResId) + ")"
                isSplitPart -> getString(editPartResId)
                else -> getString(editResId)
            }
        }
    }

    open val typeResId = R.string.transaction
    open val editResId = R.string.menu_edit_transaction
    open val editPartResId = R.string.menu_edit_split_part_category

    private val isMainTransaction: Boolean
        get() = !isSplitPart && !isTemplate
    open val shouldAutoFill
        get() = !isTemplate

    val isSplitPart
        get() = parentId != null
    private val isMainTemplate
        get() = isTemplate && !isSplitPart

    var isProcessingLinkedAmountInputs = false

    @State
    var originalAmountVisible = false

    @State
    var equivalentAmountVisible = false

    @State
    var originalCurrencyCode: String? = null

    @State
    var accountId: Long? = null

    @State
    var methodId: Long? = null

    @State
    var methodLabel: String? = null

    @State
    var _crStatus: CrStatus? = CrStatus.UNRECONCILED

    @State
    var parentId: Long? = null

    @State
    var rowId: Long = 0L

    @State
    var planId: Long? = null

    @State
    var originTemplateId: Long? = null

    @State
    var uuid: String? = null

    @State
    var payeeId: Long? = null

    @State
    var debtId: Long? = null

    val crStatus
        get() = _crStatus ?: CrStatus.UNRECONCILED

    protected var mAccounts = mutableListOf<Account>()

    val planButton: DateButton
        get() = viewBinding.PB
    private val planExecutionButton: CompoundButton
        get() = viewBinding.TB

    open fun bindUnsafe(
        transaction: ITransaction?,
        newInstance: Boolean,
        savedInstanceState: Bundle?,
        recurrence: Plan.Recurrence?,
        withAutoFill: Boolean,
        isCached: Boolean = false
    ) {
        bind(
            transaction as T?,
            newInstance,
            savedInstanceState,
            recurrence,
            withAutoFill
        )
    }

    open fun bind(
        transaction: T?,
        withTypeSpinner: Boolean,
        savedInstanceState: Bundle?,
        recurrence: Plan.Recurrence?,
        withAutoFill: Boolean
    ) {
        viewBinding.Category.setOnClickListener { host.startSelectCategory() }
        if (transaction != null) {
            label = transaction.categoryPath
            categoryIcon = transaction.categoryIcon
            catId = transaction.catId
            rowId = transaction.id
            parentId = transaction.parentId
            accountId = transaction.accountId
            methodId = transaction.methodId
            methodLabel = transaction.methodLabel
            planId = (transaction as? Template)?.plan?.id
            _crStatus = transaction.crStatus
            originTemplateId = transaction.originTemplateId
            uuid = transaction.uuid
            payeeId = transaction.payeeId
            debtId = transaction.debtId
            //Setting this early instead of waiting for call to setAccounts
            //works around a bug in some legacy virtual keyboards where configuring the
            //editText too late corrupt inputType
            viewBinding.Amount.setFractionDigits(transaction.amount.currencyUnit.fractionDigits)
        } else {
            StateSaver.restoreInstanceState(this, savedInstanceState)
        }
        viewBinding.toolbar.OperationType.isVisible = withTypeSpinner
        originTemplateId?.let { host.loadOriginTemplate(it) }

        if (isMainTemplate) {
            viewBinding.TitleRow.visibility = View.VISIBLE
            viewBinding.DefaultActionRow.visibility = View.VISIBLE
            setPlannerRowVisibility(true)
            planButton.setOnClickListener {
                planId?.let {
                    host.launchPlanView(false, it)
                } ?: run {
                    planButton.onClick()
                }
            }
        }
        if (!isSplitPart) {
            //we set adapter even if spinner is not immediately visible, since it might become visible
            //after SAVE_AND_NEW action
            val recurrenceAdapter = RecurrenceAdapter(context)
            recurrenceSpinner.adapter = recurrenceAdapter
            recurrence?.let {
                recurrenceSpinner.setSelection(recurrenceAdapter.getPosition(it))
                if (isTemplate && it != Plan.Recurrence.NONE) {
                    configurePlanDependents(true)
                }
                configureLastDayButton()
            }
            recurrenceSpinner.setOnItemSelectedListener(this)
        }
        if (isSplitPart || isTemplate) {
            viewBinding.DateTimeRow.visibility = View.GONE
            viewBinding.AttachmentsRow.visibility = View.GONE
        }

        createAdapters(withTypeSpinner, withAutoFill)

        //when we have a savedInstance, fields have already been populated
        if (savedInstanceState == null) {
            isProcessingLinkedAmountInputs = true
            populateFields(transaction!!, withAutoFill)
            isProcessingLinkedAmountInputs = false
            if (!isSplitPart) {
                setLocalDateTime(transaction)
            }
        } else {
            populateStatusSpinner()
        }
        viewBinding.Amount.visibility = View.VISIBLE
        //}
        //after setLocalDateTime, so that the plan info can override the date
        configurePlan((transaction as? Template)?.plan, false)
        configureLastDayButton()

        viewBinding.Amount.addTextChangedListener(object : MyTextWatcher() {
            override fun afterTextChanged(s: Editable) {
                viewBinding.EquivalentAmount.setCompoundResultInput(
                    viewBinding.Amount.validate(
                        false
                    )
                )
            }
        })
        viewBinding.OriginalAmount.setCompoundResultOutListener { amount: BigDecimal ->
            viewBinding.Amount.setAmount(
                amount,
                false
            )
        }
        if (isSplitPart) {
            disableAccountSpinner()
            host.parentOriginalAmountExchangeRate?.let {
                originalAmountVisible = true
                originalCurrencyCode = it.second.code
                with(viewBinding.OriginalAmount) {
                    exchangeRate = it.first
                    disableCurrencySelection()
                    disableExchangeRateEdit()
                    requestFocus()
                }
            }
        }

        if (originalAmountVisible) {
            configureOriginalAmountVisibility()
        }
        if (equivalentAmountVisible) {
            configureEquivalentAmountVisibility()
        }

        viewBinding.ClearCategory.setOnClickListener {
            resetCategory()
        }
        setCategoryButton()
    }

    /**
     * set label on category button
     */
    fun setCategoryButton() {
        if (label.isNullOrEmpty()) {
            viewBinding.Category.setText(R.string.select)
            viewBinding.ClearCategory.visibility = View.GONE
        } else {
            viewBinding.Category.text = label
            viewBinding.ClearCategory.visibility = View.VISIBLE

        }
        val startDrawable = categoryIcon?.let {
            IIconInfo.resolveIcon(it)?.asDrawable(context, androidx.appcompat.R.attr.colorPrimary)
        }
        viewBinding.Category.setCompoundDrawablesRelativeWithIntrinsicBounds(
            startDrawable,
            null,
            null,
            null
        )
    }

    fun setCategory(label: String?, categoryIcon: String?, catId: Long?) {
        this.label = label
        this.categoryIcon = categoryIcon
        this.catId = catId
        setCategoryButton()
    }

    fun resetCategory() {
        setCategory(null, null, null)
    }

    protected fun hideRowsSpecificToMain() {
        viewBinding.PayeeRow.visibility = View.GONE
        methodRowBinding.MethodRow.visibility = View.GONE
    }

    private fun setLocalDateTime(transaction: ITransaction) {
        val zonedDateTime = epoch2ZonedDateTime(transaction.date)
        val localDate = zonedDateTime.toLocalDate()
        if (transaction is Template) {
            planButton.setDate(localDate)
        } else {
            dateEditBinding.DateButton.setDate(localDate)
            dateEditBinding.Date2Button.setDate(epoch2ZonedDateTime(transaction.valueDate).toLocalDate())
            dateEditBinding.TimeButton.setTime(zonedDateTime.toLocalTime())
        }
    }

    private fun setPlannerRowVisibility(visibility: Boolean) {
        viewBinding.PlanRow.isVisible = visibility
    }

    /**
     * populates the input fields with a transaction from the database or a new one
     */
    open fun populateFields(transaction: T, withAutoFill: Boolean) {
        populateStatusSpinner()
        viewBinding.Comment.setText(transaction.comment)
        if (isMainTemplate) {
            (transaction as Template).let { template ->
                viewBinding.Title.setText(template.title)
                planExecutionButton.isChecked = template.isPlanExecutionAutomatic
                viewBinding.advanceExecutionSeek.progress = template.planExecutionAdvance
                viewBinding.DefaultAction.setSelection(template.defaultAction.ordinal)
            }
        } else {
            methodRowBinding.Number.setText(transaction.referenceNumber)
        }
        fillAmount(transaction.amount.amountMajor)
        transaction.originalAmount?.let {
            originalAmountVisible = true
            configureOriginalAmountVisibility()
            viewBinding.OriginalAmount.setFractionDigits(it.currencyUnit.fractionDigits)
            viewBinding.OriginalAmount.setAmount(it.amountMajor)
            originalCurrencyCode = it.currencyUnit.code
        } ?: run {
            originalCurrencyCode = prefHandler.getString(PrefKey.LAST_ORIGINAL_CURRENCY, null)
        }

        populateOriginalCurrency()
        transaction.equivalentAmount?.let {
            if (transaction.equivalentAmount != null) {
                equivalentAmountVisible = true
                viewBinding.EquivalentAmount.setFractionDigits(it.currencyUnit.fractionDigits)
                viewBinding.EquivalentAmount.setAmount(it.amountMajor.abs())
            }
        }
        if (withAutoFill && isMainTemplate) {
            viewBinding.Title.requestFocus()
        }
    }

    private fun populateStatusSpinner() {
        statusSpinner.setSelection(crStatus.ordinal, false)
    }

    fun fillAmount(amount: BigDecimal) {
        with(viewBinding.Amount) {
            if (amount.signum() != 0) {
                setAmount(amount)
            }
            requestFocus()
            selectAll()
        }
    }

    private fun configureEquivalentAmountVisibility() {
        viewBinding.EquivalentAmountRow.isVisible = equivalentAmountVisible
        viewBinding.EquivalentAmount.setCompoundResultInput(
            if (equivalentAmountVisible) viewBinding.Amount.validate(
                false
            ) else null
        )
    }

    private fun configureOriginalAmountVisibility() {
        viewBinding.OriginalAmountRow.isVisible = originalAmountVisible
    }

    private fun populateOriginalCurrency() {
        viewBinding.OriginalAmount.setSelectedCurrency(originalCurrencyCode?.let { currencyContext[it] }
            ?: homeCurrency)
    }

    protected fun addCurrencyToInput(
        label: TextView,
        amountInput: AmountInput,
        currencyUnit: CurrencyUnit,
        textResId: Int
    ) {
        val text = appendCurrencySymbol(label.context, textResId, currencyUnit)
        label.text = text
        amountInput.contentDescription =
            appendCurrencyDescription(label.context, textResId, currencyUnit)
    }

    fun setCurrencies(currencies: List<Currency?>?) {
        viewBinding.OriginalAmount.setCurrencies(currencies)
        populateOriginalCurrency()
    }

    fun toggleOriginalAmount() {
        originalAmountVisible = !originalAmountVisible
        configureOriginalAmountVisibility()
        if (originalAmountVisible) {
            viewBinding.OriginalAmount.requestFocus()
        } else {
            viewBinding.OriginalAmount.clear()
        }
    }

    val originalAmountExchangeRate: Pair<BigDecimal, Currency>?
        get() {
            if (originalAmountVisible) {
                val exchangeRate = viewBinding.OriginalAmount.exchangeRate
                val currency = viewBinding.OriginalAmount.selectedCurrency
                if (exchangeRate != null && currency != null) {
                    return exchangeRate to currency
                }
            }
            return null
        }

    fun toggleEquivalentAmount() {
        equivalentAmountVisible = !equivalentAmountVisible
        configureEquivalentAmount()
    }

    fun configureEquivalentAmount() {
        configureEquivalentAmountVisibility()
        if (equivalentAmountVisible) {
            currentAccount()?.let {
                if (viewBinding.EquivalentAmount.validateAmountInput(
                        showToUser = false,
                        ifPresent = true
                    ) == null
                ) {
                    val rate = BigDecimal(it.exchangeRate)
                    viewBinding.EquivalentAmount.exchangeRate = rate
                }
            }
            viewBinding.EquivalentAmount.requestFocus()
        } else {
            viewBinding.EquivalentAmount.clear()
        }
    }

    private fun setMethodSelection(methodId: Long?) {
        this.methodId = methodId
        setMethodSelection()
    }

    fun setMethodSelection() {
        if (methodId != null) {
            var found = false
            for (i in 0 until methodsAdapter.count) {
                val pm = methodsAdapter.getItem(i)
                if (pm != null) {
                    if (pm.id() == methodId) {
                        methodSpinner.setSelection(i + 1)
                        found = true
                        break
                    }
                }
            }
            if (found) {
                methodSpinner.spinner.isVisible = true
                methodRowBinding.Method.MethodOutlier.isVisible = false
            } else {
                methodSpinner.setSelection(0)
                if (methodLabel != null) {
                    methodSpinner.spinner.isVisible = false
                    with(methodRowBinding.Method.MethodOutlier) {
                        text = methodLabel!!.translateIfPredefined(context)
                        isVisible = true
                    }
                } else {
                    methodId = null
                }
            }
        } else {
            methodSpinner.spinner.isVisible = true
            methodRowBinding.Method.MethodOutlier.isVisible = false
            methodSpinner.setSelection(0)
        }
        methodRowBinding.ClearMethod.root.isVisible = methodId != null
        setReferenceNumberVisibility()
    }

    private fun setReferenceNumberVisibility() {
        if (isTemplate) return
        //ignore first row "select" merged in
        val position = methodSpinner.selectedItemPosition
        val visibility = if (position > 0) {
            val pm = methodsAdapter.getItem(position - 1)
            if (pm != null && pm.isNumbered) View.VISIBLE else View.GONE
        } else {
            View.GONE
        }
        (methodRowBinding.ReferenceNumberRow ?: methodRowBinding.Number).visibility = visibility
    }

    val context: Context
        get() = viewBinding.root.context

    val host: ExpenseEdit
        get() = context as ExpenseEdit

    abstract fun createAdapters(withTypeSpinner: Boolean, withAutoFill: Boolean)

    private fun labelForNewInstance(type: Int) = context.getString(
        when (type) {
            TYPE_SPLIT -> if (isTemplate) R.string.menu_create_template_for_split else R.string.menu_create_split
            TYPE_TRANSFER -> if (isSplitPart) R.string.menu_create_split_part_transfer else if (isTemplate) R.string.menu_create_template_for_transfer else R.string.menu_create_transfer
            TYPE_TRANSACTION -> if (isSplitPart) R.string.menu_create_split_part_category else if (isTemplate) R.string.menu_create_template_for_transaction else R.string.menu_create_transaction
            else -> throw IllegalStateException("Unknown operationType $type")
        }
    )

    protected fun createOperationTypeAdapter() {
        val allowedOperationTypes: MutableList<Int> = ArrayList()
        allowedOperationTypes.add(TYPE_TRANSACTION)
        allowedOperationTypes.add(TYPE_TRANSFER)
        if (parentId == null) {
            allowedOperationTypes.add(TYPE_SPLIT)
        }
        val objects = allowedOperationTypes.map {
            OperationType(it).apply {
                label = labelForNewInstance(it)
            }
        }
        operationTypeAdapter =
            ArrayAdapter<OperationType>(context, android.R.layout.simple_spinner_item, objects)
        operationTypeAdapter.setDropDownViewResource(androidx.appcompat.R.layout.support_simple_spinner_dropdown_item)
        operationTypeSpinner.adapter = operationTypeAdapter
        resetOperationType()
        operationTypeSpinner.setOnItemSelectedListener(this)
    }

    protected fun createStatusAdapter() {
        val sAdapter: CrStatusAdapter = object : CrStatusAdapter(context) {
            override fun isEnabled(position: Int): Boolean { //if the transaction is reconciled, the status can not be changed
                //otherwise only unreconciled and cleared can be set
                return _crStatus != CrStatus.RECONCILED && position != CrStatus.RECONCILED.ordinal
            }
        }
        statusSpinner.adapter = sAdapter
    }

    private fun createMethodAdapter() {
        methodsAdapter =
                //TODO Use IdAdapter
            object : ArrayAdapter<PaymentMethod>(context, android.R.layout.simple_spinner_item) {
                override fun getItemId(position: Int): Long {
                    return getItem(position)?.id() ?: 0L
                }
            }
        methodsAdapter.setDropDownViewResource(androidx.appcompat.R.layout.support_simple_spinner_dropdown_item)
        methodSpinner.adapter = NothingSelectedSpinnerAdapter(
            methodsAdapter,
            android.R.layout.simple_spinner_item,  // R.layout.contact_spinner_nothing_selected_dropdown, // Optional
            context
        )
    }

    fun resetOperationType() {
        operationTypeSpinner.setSelection(
            operationTypeAdapter.getPosition(OperationType(operationType))
        )
    }

    fun setMethods(paymentMethods: List<PaymentMethod>?) {
        if (paymentMethods.isNullOrEmpty()) {
            methodId = null
            methodRowBinding.MethodRow.visibility = View.GONE
        } else {
            methodRowBinding.MethodRow.visibility = View.VISIBLE
            methodRowBinding.ClearMethod.root.setOnClickListener {
                setMethodSelection(null)
            }
            methodsAdapter.clear()
            methodsAdapter.addAll(paymentMethods)
            setMethodSelection()
        }
    }

    open fun missingRecurrenceFeature() = if (prefHandler.getBoolean(
            PrefKey.NEW_PLAN_ENABLED,
            true
        )
    ) null else ContribFeature.PLANS_UNLIMITED

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
        val host = context as ExpenseEdit
        if (parent.id != R.id.OperationType) {
            host.setDirty()
        }
        when (parent.id) {
            recurrenceSpinner.id -> {
                var planVisibility = false
                if (id > 0) {
                    if (PermissionHelper.PermissionGroup.CALENDAR.hasPermission(context)) {
                        missingRecurrenceFeature()?.let {
                            recurrenceSpinner.setSelection(0)
                            host.showContribDialog(it, null)
                        } ?: run {
                            planVisibility = true
                            showCustomRecurrenceInfo()
                            configureLastDayButton()
                        }
                    }
                    host.checkPermissionsForPlaner()
                }
                if (isTemplate) {
                    configurePlanDependents(planVisibility)
                }
            }

            methodSpinner.id -> {
                val hasSelection = position > 0
                methodId = if (hasSelection) parent.selectedItemId.takeIf { it > 0 } else null
                methodRowBinding.ClearMethod.root.isVisible = hasSelection
                setReferenceNumberVisibility()
            }

            accountSpinner.id -> {
                val account = mAccounts[position]
                updateAccount(account)
                host.maybeApplyDynamicColor()
            }

            operationTypeSpinner.id -> {
                val newType =
                    (operationTypeSpinner.getItemAtPosition(position) as OperationType).type
                if (host.isValidType(newType)) {
                    if (newType == TYPE_TRANSFER && !checkTransferEnabled()) { //reset to previous
                        resetOperationType()
                    } else if (newType == TYPE_SPLIT) {
                        resetOperationType()
                        if (isTemplate) {
                            if (prefHandler.getBoolean(PrefKey.NEW_SPLIT_TEMPLATE_ENABLED, true)) {
                                host.restartWithType(newType)
                            } else {
                                host.contribFeatureRequested(ContribFeature.SPLIT_TEMPLATE)
                            }
                        } else {
                            host.contribFeatureRequested(ContribFeature.SPLIT_TRANSACTION)
                        }
                    } else {
                        host.restartWithType(newType)
                    }
                }
            }

            statusSpinner.id -> {
                (parent.selectedItem as? CrStatus)?.let {
                    _crStatus = it
                }
            }
        }
    }

    private fun checkTransferEnabled(): Boolean {
        if (currentAccount() == null) return false
        if (mAccounts.size <= 1) {
            (context as ExpenseEdit).showSnackBar(R.string.dialog_command_disabled_insert_transfer)
            return false
        }
        return true
    }

    private fun showCustomRecurrenceInfo() {
        if (recurrenceSpinner.selectedItem === Plan.Recurrence.CUSTOM) {
            (context as ExpenseEdit).showDismissibleSnackBar(R.string.plan_custom_recurrence_info)
        }
    }

    private val configuredDate: LocalDate
        get() = (if (isMainTemplate) planButton else dateEditBinding.DateButton).date

    fun configureLastDayButton() {
        val visible =
            recurrenceSpinner.selectedItem === Plan.Recurrence.MONTHLY && configuredDate.dayOfMonth > 28
        viewBinding.LastDay.isVisible = visible
        if (!visible) {
            viewBinding.LastDay.isChecked = false
        } else if (configuredDate.dayOfMonth == 31) {
            viewBinding.LastDay.isChecked = true
        }
    }

    open fun setupListeners(watcher: TextWatcher) {
        viewBinding.Comment.addTextChangedListener(watcher)
        viewBinding.Title.addTextChangedListener(watcher)
        viewBinding.Payee.addTextChangedListener(watcher)
        methodRowBinding.Number.addTextChangedListener(watcher)
        accountSpinner.setOnItemSelectedListener(this)
        methodSpinner.setOnItemSelectedListener(this)
        statusSpinner.setOnItemSelectedListener(this)
    }

    /**
     * @return true for income, false for expense
     */
    val isIncome: Boolean
        get() = viewBinding.Amount.type

    private fun readZonedDateTime(dateEdit: DateButton): ZonedDateTime {
        return ZonedDateTime.of(
            dateEdit.date,
            if (dateEditBinding.TimeButton.visibility == View.VISIBLE) dateEditBinding.TimeButton.time else LocalTime.now(),
            ZoneId.systemDefault()
        )
    }

    fun currentAccount() = getAccountFromSpinner(accountSpinner)

    protected fun getAccountFromSpinner(spinner: SpinnerHelper): Account? {
        val selected = spinner.selectedItemPosition
        if (selected == AdapterView.INVALID_POSITION) {
            return null
        }
        val selectedID = spinner.selectedItemId
        for (account in mAccounts) {
            if (account.id == selectedID) {
                return account
            }
        }
        return null
    }

    protected fun buildTemplate(account: Account) =
        Template.getTypedNewInstance(
            context.contentResolver,
            operationType,
            account.id,
            account.currency,
            false,
            parentId
        )!!

    abstract fun buildTransaction(
        forSave: Boolean,
        account: Account
    ): T?

    abstract val operationType: Int

    open fun syncStateAndValidate(forSave: Boolean): T? {
        return currentAccount()?.let {
            buildTransaction(
                forSave && !isMainTemplate,
                it
            )
        }?.apply {
            catId = this@TransactionDelegate.catId
            categoryPath = this@TransactionDelegate.label
            originTemplateId = this@TransactionDelegate.originTemplateId
            uuid = this@TransactionDelegate.uuid
            id = rowId
            if (isSplitPart) {
                status = DatabaseConstants.STATUS_UNCOMMITTED
            }
            comment = viewBinding.Comment.text.toString()
            if (isMainTransaction) {
                val transactionDate = readZonedDateTime(dateEditBinding.DateButton)
                setDate(transactionDate)
                setValueDate(
                    if (dateEditBinding.Date2Button.visibility == View.VISIBLE) readZonedDateTime(
                        dateEditBinding.Date2Button
                    ) else transactionDate
                )
            }
            if (isMainTemplate) {
                (this as Template).apply {
                    viewBinding.Title.text.toString().let {
                        if (it == "") {
                            if (forSave) {
                                viewBinding.Title.error = context.getString(R.string.required)
                                return null
                            }
                        }
                        this.title = it
                    }
                    this.isPlanExecutionAutomatic = planExecutionButton.isChecked
                    this.planExecutionAdvance = viewBinding.advanceExecutionSeek.progress
                    if (recurrenceSpinner.selectedItemPosition > 0 || this@TransactionDelegate.planId != null) {
                        plan = Plan(
                            this@TransactionDelegate.planId ?: 0L,
                            planButton.date,
                            selectedRecurrence,
                            title,
                            compileDescription(context.myApplication)
                        )
                    }
                    this.defaultAction =
                        Template.Action.entries[viewBinding.DefaultAction.selectedItemPosition]
                    if (this.amount.amountMinor == 0L &&
                        (this.transferAmount?.amountMinor ?: 0L) == 0L &&
                        forSave
                    ) {
                        if (plan == null && this.defaultAction == Template.Action.SAVE) {
                            host.showSnackBar(context.getString(R.string.template_default_action_without_amount_hint))
                            return null
                        }
                        if (plan != null && this.isPlanExecutionAutomatic) {
                            host.showSnackBar(context.getString(R.string.plan_automatic_without_amount_hint))
                            return null
                        }
                    }
                    prefHandler.putString(PrefKey.TEMPLATE_CLICK_DEFAULT, defaultAction.name)
                }
            } else {
                referenceNumber = methodRowBinding.Number.text.toString()
                if (forSave && !isSplitPart) {
                    if (host.createTemplate) {
                        setInitialPlan(
                            Triple(
                                viewBinding.Title.text.toString().takeIf { it.isNotEmpty() },
                                selectedRecurrence, dateEditBinding.DateButton.date
                            )
                        )
                    }
                }
            }
            crStatus = (statusSpinner.selectedItem as CrStatus)
        }
    }

    private val selectedRecurrence
        get() = (recurrenceSpinner.selectedItem as? Plan.Recurrence)?.let {
            if (it == Plan.Recurrence.MONTHLY && configuredDate.dayOfMonth > 28 && viewBinding.LastDay.isChecked)
                Plan.Recurrence.LAST_DAY_OF_MONTH else it
        } ?: Plan.Recurrence.NONE

    protected fun validateAmountInput(): BigDecimal? =
        viewBinding.Amount.validateAmountInput(showToUser = false, ifPresent = false)

    protected fun validateAmountInput(forSave: Boolean, currencyUnit: CurrencyUnit) =
        viewBinding.Amount.validateAmountInput(
            currencyUnit,
            showToUser = forSave,
            ifPresent = forSave
        )

    private fun configureAccountDependent(account: Account) {
        val currencyUnit = account.currency
        addCurrencyToInput(
            viewBinding.AmountLabel,
            viewBinding.Amount,
            currencyUnit,
            R.string.amount
        )
        viewBinding.OriginalAmount.configureExchange(currencyUnit)
        if (hasHomeCurrency(account)) {
            viewBinding.EquivalentAmountRow.visibility = View.GONE
            equivalentAmountVisible = false
        } else {
            viewBinding.EquivalentAmount.configureExchange(currencyUnit, homeCurrency)
        }
        configureDateInput(account)
        configureStatusSpinner()
        viewBinding.Amount.setFractionDigits(account.currency.fractionDigits)
        host.updateContentColor(account.color)
    }

    private fun hasHomeCurrency(account: Account): Boolean {
        return account.currency == homeCurrency
    }

    private fun configureDateInput(account: Account) {
        val dateMode = getDateMode(account.type, prefHandler)
        dateEditBinding.TimeButton.isVisible = dateMode == UiUtils.DateMode.DATE_TIME
        dateEditBinding.Date2Button.isVisible = dateMode == UiUtils.DateMode.BOOKING_VALUE
        dateEditBinding.DateLink.isVisible = dateMode == UiUtils.DateMode.BOOKING_VALUE

        viewBinding.DateTimeLabel.text = when (dateMode) {
            UiUtils.DateMode.BOOKING_VALUE -> context.getString(R.string.booking_date) + "/" + context.getString(
                R.string.value_date
            )

            UiUtils.DateMode.DATE_TIME -> context.getString(R.string.date) + " / " + context.getString(
                R.string.time
            )

            UiUtils.DateMode.DATE -> context.getString(R.string.date)
        }
    }

    open fun setAccount() {
        //if the accountId we have been passed does not exist, we select the first entry
        var selected = 0
        for (item in mAccounts.indices) {
            val account = mAccounts[item]
            if (account.id == accountId) {
                selected = item
                break
            }
        }
        accountSpinner.setSelection(selected)
        updateAccount(mAccounts[selected])
    }

    open fun setAccounts(data: List<Account>) {
        mAccounts.clear()
        mAccounts.addAll(data)
        accountSpinner.adapter = IdAdapter(context, data).apply {
            setDropDownViewResource(androidx.appcompat.R.layout.support_simple_spinner_dropdown_item)
        }

        viewBinding.Amount.setTypeEnabled(true)
        isProcessingLinkedAmountInputs = true
        configureType()
        isProcessingLinkedAmountInputs = false
        setAccount()
    }

    private fun configureStatusSpinner() {
        currentAccount()?.let {
            statusSpinner.spinner.isVisible =
                !isSplitPart && !isTemplate && it.type != AccountType.CASH
        }
    }

    open fun updateAccount(account: Account) {
        accountId = account.id
        host.loadActiveTags(account.id)
        configureAccountDependent(account)
    }

    fun setType(type: Boolean) {
        isProcessingLinkedAmountInputs = true
        viewBinding.Amount.type = type
        isProcessingLinkedAmountInputs = false
    }

    open fun configureType() {
        viewBinding.PayeeLabel.setText(if (viewBinding.Amount.type) R.string.payer else R.string.payee)
    }

    private fun updatePlanButton(plan: Plan) {
        planButton.overrideText(Plan.prettyTimeInfo(context, plan.rRule, plan.dtStart))
    }

    fun configurePlan(plan: Plan?, fromObserver: Boolean) {
        plan?.let {
            updatePlanButton(it)
            if (viewBinding.Title.text.toString() == "") viewBinding.Title.setText(it.title)
            if (!fromObserver) {
                recurrenceSpinner.spinner.visibility = View.GONE
                configurePlanDependents(true)
            }
            host.observePlan(it.id)
        }
    }

    private fun configurePlanDependents(visibility: Boolean) {
        planButton.isVisible = visibility
        planExecutionButton.isVisible = visibility
        viewBinding.advanceExecutionRow.isVisible = visibility
        viewBinding.DefaultActionRow.isVisible = !visibility
    }

    open fun onSaveInstanceState(outState: Bundle) {
        val originalInputSelectedCurrency = viewBinding.OriginalAmount.selectedCurrency
        if (originalInputSelectedCurrency != null) {
            originalCurrencyCode = originalInputSelectedCurrency.code
        }
        StateSaver.saveInstanceState(this, outState)
    }

    private fun disableAccountSpinner() {
        accountSpinner.isEnabled = false
    }

    open fun resetRecurrence() {
        recurrenceSpinner.spinner.visibility = View.VISIBLE
        recurrenceSpinner.setSelection(0)
        planButton.visibility = View.GONE
    }

    private fun resetAmounts() {
        isProcessingLinkedAmountInputs = true
        viewBinding.Amount.clear()
        viewBinding.TransferAmount.clear()
        isProcessingLinkedAmountInputs = false
    }

    open fun prepareForNew() {
        rowId = 0L
        uuid = null
        _crStatus = CrStatus.UNRECONCILED
        resetRecurrence()
        resetAmounts()
        populateStatusSpinner()
    }

    open fun onDestroy() {
    }

    fun onCalendarPermissionsResult(granted: Boolean) {
        if (granted) {
            if (isTemplate) {
                configurePlanDependents(true)
            }
            showCustomRecurrenceInfo()
            configureLastDayButton()
        } else {
            recurrenceSpinner.setSelection(0)
        }
    }

    fun originTemplateLoaded(template: Template) {
        template.plan?.let { plan ->
            setPlannerRowVisibility(true)
            recurrenceSpinner.spinner.visibility = View.GONE
            updatePlanButton(plan)
            with(planButton) {
                visibility = View.VISIBLE
                setOnClickListener {
                    currentAccount()?.let {
                        host.showPlanMonthFragment(template, it.color)
                    }
                }
            }
            viewBinding.EditPlan.isVisible = true
            viewBinding.EditPlan.setOnClickListener {
                host.launchPlanView(false, plan.id)
            }
            planId = plan.id
            host.observePlan(plan.id)
        }
    }

    fun showTags(tags: Iterable<Tag>?, closeFunction: (Tag) -> Unit) {
        with(viewBinding.TagRow.TagGroup) {
            removeAllViews()
            tags?.let { addChipsBulk(it, closeFunction) }
        }
    }

    fun setCreateTemplate(
        createTemplate: Boolean
    ) {
        viewBinding.TitleRow.isVisible = createTemplate
        setPlannerRowVisibility(createTemplate)
    }

    data class OperationType(val type: Int) {
        var label: String = ""

        override fun toString(): String {
            return label
        }
    }

    companion object {
        fun create(
            transaction: ITransaction,
            viewBinding: OneExpenseBinding,
            dateEditBinding: DateEditBinding,
            methodRowBinding: MethodRowBinding,
            injector: AppComponent
        ) =
            (transaction is Template).let { isTemplate ->
                with(transaction) {
                    when {
                        isTransfer -> TransferDelegate(
                            viewBinding,
                            dateEditBinding,
                            methodRowBinding,
                            isTemplate
                        ).also {
                            injector.inject(it)
                        }

                        isSplit -> SplitDelegate(
                            viewBinding,
                            dateEditBinding,
                            methodRowBinding,
                            isTemplate
                        ).also {
                            injector.inject(it)
                        }

                        else -> CategoryDelegate(
                            viewBinding,
                            dateEditBinding,
                            methodRowBinding,
                            isTemplate
                        ).also {
                            injector.inject(it)
                        }
                    }
                }
            }

        fun create(
            operationType: Int, isTemplate: Boolean, viewBinding: OneExpenseBinding,
            dateEditBinding: DateEditBinding, methodRowBinding: MethodRowBinding,
            injector: AppComponent
        ) = when (operationType) {
            TYPE_TRANSFER -> TransferDelegate(
                viewBinding,
                dateEditBinding,
                methodRowBinding,
                isTemplate
            ).also {
                injector.inject(it)
            }

            TYPE_SPLIT -> SplitDelegate(
                viewBinding,
                dateEditBinding,
                methodRowBinding,
                isTemplate
            ).also {
                injector.inject(it)
            }

            else -> CategoryDelegate(
                viewBinding,
                dateEditBinding,
                methodRowBinding,
                isTemplate
            ).also {
                injector.inject(it)
            }
        }
    }
}