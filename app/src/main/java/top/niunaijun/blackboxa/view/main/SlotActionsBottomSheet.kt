package top.niunaijun.blackboxa.view.main

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.niunaijun.blackboxa.R

/**
 * Long-press actions for a slot row. Rename / Delete.
 *
 * Callers don't pass a SlotData reference — they pass the userId + display
 * name and two lambdas. This keeps the fragment surviving recreation without
 * needing to make SlotData Parcelable.
 */
class SlotActionsBottomSheet : BottomSheetDialogFragment() {

    private var onRename: (() -> Unit)? = null
    private var onDelete: (() -> Unit)? = null

    fun configure(
        slotName: String,
        onRename: () -> Unit,
        onDelete: () -> Unit,
    ): SlotActionsBottomSheet {
        this.onRename = onRename
        this.onDelete = onDelete
        arguments = (arguments ?: Bundle()).apply {
            putString(ARG_SLOT_NAME, slotName)
        }
        return this
    }

    override fun getTheme(): Int = R.style.PhantomBottomSheetTheme

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), R.style.PhantomBottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_slot_actions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val slotName = arguments?.getString(ARG_SLOT_NAME)
        if (!slotName.isNullOrBlank()) {
            view.findViewById<TextView>(R.id.sheet_title).text = slotName
        }
        view.findViewById<View>(R.id.action_rename).setOnClickListener {
            dismissAllowingStateLoss()
            onRename?.invoke()
        }
        view.findViewById<View>(R.id.action_delete).setOnClickListener {
            dismissAllowingStateLoss()
            onDelete?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Drop lambda refs to avoid leaking the host activity if the fragment
        // outlives the activity (e.g. recreation).
        onRename = null
        onDelete = null
    }

    companion object {
        private const val ARG_SLOT_NAME = "slot_name"
    }
}
